$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
Set-Location -LiteralPath $projectRoot

function Test-LocalTcpPort([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(500)) { return $false }
        $client.EndConnect($pending)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

# The project is compiled for Java 17. Oracle's javapath on some Windows hosts
# still points to Java 8, so resolve and verify a compatible runtime explicitly.
$javaCandidates = @()
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $javaCandidates += (Join-Path $env:JAVA_HOME "bin\java.exe")
}
$javaCandidates += "D:\jdk-17.0.19+10\bin\java.exe"
$pathJava = Get-Command java.exe -ErrorAction SilentlyContinue
if ($null -ne $pathJava) {
    $javaCandidates += $pathJava.Source
}
$javaExe = $null
foreach ($candidate in ($javaCandidates | Select-Object -Unique)) {
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
    # Java writes its version banner to stderr. Windows PowerShell 5.1 turns
    # redirected native stderr into an ErrorRecord when ErrorActionPreference
    # is Stop, so relax it only for this read-only version probe.
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $versionText = (& $candidate -version 2>&1 | Out-String)
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($versionText -match 'version\s+"(?<major>\d+)(?:\.(?<minor>\d+))?') {
        $major = [int]$matches.major
        if ($major -eq 1 -and $matches.minor) { $major = [int]$matches.minor }
        if ($major -ge 17) { $javaExe = $candidate; break }
    }
}
if ($null -eq $javaExe) {
    throw "No Java 17+ runtime found. Set JAVA_HOME to a JDK 17 or newer installation."
}
Write-Host ("Java check passed: {0}" -f $javaExe)

if ([string]::IsNullOrWhiteSpace($env:RUOYI_PROFILE)) {
    $preferredProfile = "D:\ruoyi\uploadPath"
    try {
        New-Item -ItemType Directory -Path $preferredProfile -Force -ErrorAction Stop | Out-Null
        $probe = Join-Path $preferredProfile ".startup-write-probe.tmp"
        Set-Content -LiteralPath $probe -Value "ok" -Encoding Ascii -ErrorAction Stop
        Remove-Item -LiteralPath $probe -Force -ErrorAction SilentlyContinue
        $env:RUOYI_PROFILE = $preferredProfile
    } catch {
        $env:RUOYI_PROFILE = Join-Path $projectRoot "runtime-uploadPath"
        Write-Warning "D:\ruoyi\uploadPath is not writable; using $env:RUOYI_PROFILE"
    }
}

$requiredDirectories = @("import", "upload", "avatar", "download")
foreach ($directory in $requiredDirectories) {
    New-Item -ItemType Directory -Path (Join-Path $env:RUOYI_PROFILE $directory) -Force | Out-Null
}

$probe = Join-Path $env:RUOYI_PROFILE ".write-probe.tmp"
try {
    Set-Content -LiteralPath $probe -Value "ok" -Encoding Ascii
} finally {
    Remove-Item -LiteralPath $probe -Force -ErrorAction SilentlyContinue
}

$envFile = Join-Path $projectRoot "ruoyi-business\excel-agent\.env"
if (Test-Path -LiteralPath $envFile) {
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line -match "^\s*([^#][^=]*)=(.*)$") {
            [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), "Process")
        }
    }
}

# py.exe may be a Windows launcher without an installed interpreter. Resolve a
# real python.exe before startup so Java child processes can invoke it directly.
if ([string]::IsNullOrWhiteSpace($env:PYTHON) -or -not (Test-Path -LiteralPath $env:PYTHON -PathType Leaf)) {
    $pythonCandidates = @(
        (Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"),
        (Join-Path $env:LOCALAPPDATA "Programs\Python\Python313\python.exe")
    )
    $pathPython = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($null -ne $pathPython) {
        $pythonCandidates += $pathPython.Source
    }
    $selectedPython = $pythonCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } | Select-Object -First 1
    if ($null -eq $selectedPython) {
        throw "No usable Python interpreter found. Set PYTHON to the full path of python.exe."
    }
    $env:PYTHON = $selectedPython
}
Write-Host ("Python check passed: {0}" -f $env:PYTHON)
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"

# Start the bundled Redis used by the original RuoYi project when port 6379
# is not already served. This keeps the one-command startup self-contained.
$redisReady = Test-LocalTcpPort 6379
if (-not $redisReady) {
    $redisRoot = Join-Path $projectRoot "tools\redis"
    $redisExe = Join-Path $redisRoot "redis-server.exe"
    $redisConfig = Join-Path $redisRoot "redis.windows.conf"
    if (-not (Test-Path -LiteralPath $redisExe -PathType Leaf) -or -not (Test-Path -LiteralPath $redisConfig -PathType Leaf)) {
        throw "Bundled Redis is missing under $redisRoot"
    }
    $redisOut = Join-Path $projectRoot "runtime-logs\redis.out.log"
    $redisErr = Join-Path $projectRoot "runtime-logs\redis.err.log"
    New-Item -ItemType Directory -Path (Split-Path -Parent $redisOut) -Force | Out-Null
    Start-Process -FilePath $redisExe -ArgumentList $redisConfig -WorkingDirectory $redisRoot -WindowStyle Hidden -RedirectStandardOutput $redisOut -RedirectStandardError $redisErr | Out-Null
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        Start-Sleep -Milliseconds 250
        $redisReady = Test-LocalTcpPort 6379
        if ($redisReady) { break }
    }
    if (-not $redisReady) { throw "Redis failed to start. Check $redisErr" }
}
Write-Host "Redis check passed: 127.0.0.1:6379"

# Start the local parser/crawler sidecar. RuoYi remains the only owner of
# credentials and the MySQL knowledge base; the sidecar receives LLM settings
# per internal request and does not persist a second knowledge base.
$agentRoot = Join-Path $projectRoot "agent-service"
$agentReady = Test-LocalTcpPort 8000
if ($agentReady -and [string]::IsNullOrWhiteSpace($env:AGENT_INTERNAL_TOKEN)) {
    throw "Port 8000 is already in use, but AGENT_INTERNAL_TOKEN is not set. Stop the old agent-service or set the same token before starting RuoYi."
}
if (-not $agentReady) {
    if ([string]::IsNullOrWhiteSpace($env:AGENT_INTERNAL_TOKEN)) {
        $env:AGENT_INTERNAL_TOKEN = [Guid]::NewGuid().ToString("N")
    }
    & $env:PYTHON -c "import fastapi, uvicorn" 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Python agent dependencies are missing. Run: & '$env:PYTHON' -m pip install -r '$agentRoot\requirements.txt'"
    }
    $agentOut = Join-Path $projectRoot "runtime-logs\agent-service.out.log"
    $agentErr = Join-Path $projectRoot "runtime-logs\agent-service.err.log"
    New-Item -ItemType Directory -Path (Split-Path -Parent $agentOut) -Force | Out-Null
    Start-Process -FilePath $env:PYTHON -ArgumentList @("-m", "uvicorn", "api.routes:app", "--host", "127.0.0.1", "--port", "8000") -WorkingDirectory $agentRoot -WindowStyle Hidden -RedirectStandardOutput $agentOut -RedirectStandardError $agentErr | Out-Null
    $agentReady = $false
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        Start-Sleep -Milliseconds 500
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:8000/health" -Method Get -TimeoutSec 2
            $agentReady = $health.status -eq "ok"
        } catch {
            $agentReady = $false
        }
        if ($agentReady) { break }
    }
    if (-not $agentReady) { throw "Python agent-service failed to start. Check $agentErr" }
}
Write-Host "Agent service check passed: http://127.0.0.1:8000"

$jar = Join-Path $projectRoot "ruoyi-admin\target\ruoyi-admin.jar"
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "Backend package not found. Run Maven package first: $jar"
}

Write-Host ("Upload directory check passed: {0}" -f $env:RUOYI_PROFILE)
Write-Host "Starting backend service..."
& $javaExe -Xms256m -Xmx1024m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=512m -jar $jar
