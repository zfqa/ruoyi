$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$workspaceRoot = Split-Path -Parent $projectRoot
$environmentRoot = if ([string]::IsNullOrWhiteSpace($env:RUOYI_ENV_ROOT)) {
    Join-Path $workspaceRoot "ruoyi-environment"
} else {
    $env:RUOYI_ENV_ROOT
}
$environmentRoot = (Resolve-Path -LiteralPath $environmentRoot).Path
$nativeRuntimeRoot = if ([string]::IsNullOrWhiteSpace($env:RUOYI_NATIVE_ROOT)) {
    "D:\ruoyi-master2-runtime"
} else {
    $env:RUOYI_NATIVE_ROOT
}
# The native runtime is only needed when Redis is not already running. Keep
# startup self-contained on machines that do not have the historical runtime
# directory by falling back to the project workspace instead of failing during
# Resolve-Path before the Redis health check.
if (-not (Test-Path -LiteralPath $nativeRuntimeRoot -PathType Container)) {
    $nativeRuntimeRoot = $workspaceRoot
}
$nativeRuntimeRoot = (Resolve-Path -LiteralPath $nativeRuntimeRoot).Path
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
$javaCandidates += (Join-Path $environmentRoot "jdk_extract\PFiles64\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe")
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
    $preferredProfile = Join-Path $environmentRoot "data\ruoyi_upload"
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

$envFiles = @((Join-Path $projectRoot ".env"))
foreach ($envFile in $envFiles) {
    if (Test-Path -LiteralPath $envFile) {
        foreach ($line in Get-Content -LiteralPath $envFile) {
            if ($line -match "^\s*([^#][^=]*)=(.*)$") {
                [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), "Process")
            }
        }
    }
}

# py.exe may be a Windows launcher without an installed interpreter. Resolve a
# real python.exe before startup so Java child processes can invoke it directly.
if ([string]::IsNullOrWhiteSpace($env:PYTHON) -or -not (Test-Path -LiteralPath $env:PYTHON -PathType Leaf)) {
    $pythonCandidates = @(
        (Join-Path $environmentRoot "python-market-agent\Scripts\python.exe"),
        (Join-Path $environmentRoot "python\python312\python.exe"),
        (Join-Path $env:LOCALAPPDATA "Programs\Python\Python313\python.exe"),
        (Join-Path $env:LOCALAPPDATA "Programs\Python\Python312\python.exe"),
        (Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe")
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

# Start bundled Redis when port 6379 is not already served. Prefer the legacy
# native runtime layout when present; otherwise use the project tools\redis
# package so machines without D:\ruoyi-master2-runtime can still self-start.
$redisReady = Test-LocalTcpPort 6379
if (-not $redisReady) {
    $redisCandidates = @(
        @{
            Exe = (Join-Path $nativeRuntimeRoot "redis\Redis-8.10.1-Windows-x64-msys2\redis-server.exe")
            Config = (Join-Path $nativeRuntimeRoot "redis.conf")
            WorkingDirectory = (Join-Path $nativeRuntimeRoot "redis\Redis-8.10.1-Windows-x64-msys2")
            ConfigArgument = "..\..\redis.conf"
        },
        @{
            Exe = (Join-Path $projectRoot "tools\redis\redis-server.exe")
            Config = (Join-Path $projectRoot "tools\redis\redis.windows.conf")
            WorkingDirectory = (Join-Path $projectRoot "tools\redis")
            ConfigArgument = "redis.windows.conf"
        }
    )
    $selectedRedis = $null
    foreach ($candidate in $redisCandidates) {
        if ((Test-Path -LiteralPath $candidate.Exe -PathType Leaf) -and (Test-Path -LiteralPath $candidate.Config -PathType Leaf)) {
            $selectedRedis = $candidate
            break
        }
    }
    if ($null -eq $selectedRedis) {
        throw "Bundled Redis is missing. Checked legacy runtime under $nativeRuntimeRoot and project tools under $(Join-Path $projectRoot 'tools\redis')"
    }
    $redisOut = Join-Path $environmentRoot "data\logs\redis.out.log"
    $redisErr = Join-Path $environmentRoot "data\logs\redis.err.log"
    New-Item -ItemType Directory -Path (Split-Path -Parent $redisOut) -Force | Out-Null
    Start-Process -FilePath $selectedRedis.Exe -ArgumentList $selectedRedis.ConfigArgument -WorkingDirectory $selectedRedis.WorkingDirectory -WindowStyle Hidden -RedirectStandardOutput $redisOut -RedirectStandardError $redisErr | Out-Null
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
if ($agentReady) {
    try {
        $agentHealth = Invoke-RestMethod -Uri "http://127.0.0.1:8000/health" -Method Get -TimeoutSec 2
        $agentReady = $agentHealth.status -eq "ok"
    } catch {
        $agentReady = $false
    }
    if (-not $agentReady) {
        throw "Port 8000 is occupied by a service that is not agent-service. Stop that service before starting RuoYi."
    }
}
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
    $agentOut = Join-Path $environmentRoot "data\logs\agent-service.out.log"
    $agentErr = Join-Path $environmentRoot "data\logs\agent-service.err.log"
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

# Start the independent vehicle-market service on 8001. The browser never
# connects to this port directly; Spring Boot remains the authenticated gateway.
$marketAgentRoot = Join-Path $projectRoot "ruoyi-business\market-agent"
$marketAgentPort = 8001
$marketAgentReady = Test-LocalTcpPort $marketAgentPort
if ($marketAgentReady) {
    try {
        $marketHealth = Invoke-RestMethod -Uri "http://127.0.0.1:$marketAgentPort/api/health" -Method Get -TimeoutSec 2
        $marketAgentReady = $marketHealth.status -eq "ok" -and $marketHealth.app_version -eq "21.0"
    } catch {
        $marketAgentReady = $false
    }
    if (-not $marketAgentReady) {
        throw "Port $marketAgentPort is occupied by a service that is not Market Agent 21.0. Stop that service or configure another port."
    }
}
if (-not $marketAgentReady) {
    if (-not (Test-Path -LiteralPath $marketAgentRoot -PathType Container)) {
        throw "Market Agent source is missing under $marketAgentRoot"
    }
    & $env:PYTHON -c "import fastapi, uvicorn, pandas, openpyxl, docx, pptx, pypdf, matplotlib" 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Market Agent dependencies are missing. Run: & '$env:PYTHON' -m pip install -r '$marketAgentRoot\requirements.txt'"
    }
    $fallbackMarketStorage = Join-Path $environmentRoot "data\market-agent"
    if ([string]::IsNullOrWhiteSpace($env:MARKET_AGENT_STORAGE_DIR)) {
        $env:MARKET_AGENT_STORAGE_DIR = $fallbackMarketStorage
    }
    try {
        $marketJobRoot = Join-Path $env:MARKET_AGENT_STORAGE_DIR "jobs"
        New-Item -ItemType Directory -Path $marketJobRoot -Force -ErrorAction Stop | Out-Null
        $marketProbeDir = Join-Path $marketJobRoot (".startup-probe-" + [Guid]::NewGuid().ToString("N"))
        New-Item -ItemType Directory -Path $marketProbeDir -ErrorAction Stop | Out-Null
        $marketProbeFile = Join-Path $marketProbeDir "write.tmp"
        Set-Content -LiteralPath $marketProbeFile -Value "ok" -Encoding Ascii -ErrorAction Stop
        Remove-Item -LiteralPath $marketProbeFile -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $marketProbeDir -Force -ErrorAction SilentlyContinue
    } catch {
        if ($env:MARKET_AGENT_STORAGE_DIR -ne $fallbackMarketStorage) {
            Write-Warning "$env:MARKET_AGENT_STORAGE_DIR is not writable; using $fallbackMarketStorage"
        }
        $env:MARKET_AGENT_STORAGE_DIR = $fallbackMarketStorage
        $marketJobRoot = Join-Path $env:MARKET_AGENT_STORAGE_DIR "jobs"
        New-Item -ItemType Directory -Path $marketJobRoot -Force -ErrorAction Stop | Out-Null
        $marketProbeDir = Join-Path $marketJobRoot (".startup-probe-" + [Guid]::NewGuid().ToString("N"))
        New-Item -ItemType Directory -Path $marketProbeDir -ErrorAction Stop | Out-Null
        Remove-Item -LiteralPath $marketProbeDir -Force -ErrorAction SilentlyContinue
    }
    $env:STORAGE_DIR = $env:MARKET_AGENT_STORAGE_DIR
    $env:MARKET_AGENT_HOST = "127.0.0.1"
    $env:MARKET_AGENT_PORT = [string]$marketAgentPort
    $env:MARKET_AGENT_BASE_URL = "http://127.0.0.1:$marketAgentPort/api"
    $marketOut = Join-Path $environmentRoot "data\logs\market-agent.out.log"
    $marketErr = Join-Path $environmentRoot "data\logs\market-agent.err.log"
    Start-Process -FilePath $env:PYTHON -ArgumentList "run_backend.py" -WorkingDirectory $marketAgentRoot -WindowStyle Hidden -RedirectStandardOutput $marketOut -RedirectStandardError $marketErr | Out-Null
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        Start-Sleep -Milliseconds 500
        try {
            $marketHealth = Invoke-RestMethod -Uri "http://127.0.0.1:$marketAgentPort/api/health" -Method Get -TimeoutSec 2
            $marketAgentReady = $marketHealth.status -eq "ok" -and $marketHealth.app_version -eq "21.0"
        } catch {
            $marketAgentReady = $false
        }
        if ($marketAgentReady) { break }
    }
    if (-not $marketAgentReady) { throw "Market Agent failed to start. Check $marketErr" }
}
if ([string]::IsNullOrWhiteSpace($env:MARKET_AGENT_BASE_URL)) {
    $env:MARKET_AGENT_BASE_URL = "http://127.0.0.1:$marketAgentPort/api"
}
Write-Host "Market Agent check passed: $env:MARKET_AGENT_BASE_URL (storage: $env:MARKET_AGENT_STORAGE_DIR)"

$jar = Join-Path $projectRoot "ruoyi-admin\target\ruoyi-admin.jar"
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "Backend package not found. Run Maven package first: $jar"
}

Write-Host ("Upload directory check passed: {0}" -f $env:RUOYI_PROFILE)
Write-Host "Starting backend service..."
& $javaExe -Xms256m -Xmx1024m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=512m -jar $jar
