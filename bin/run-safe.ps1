$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
Set-Location -LiteralPath $projectRoot

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
    $versionText = (& $candidate -version 2>&1 | Out-String)
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

$jar = Join-Path $projectRoot "ruoyi-admin\target\ruoyi-admin.jar"
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "Backend package not found. Run Maven package first: $jar"
}

Write-Host ("Upload directory check passed: {0}" -f $env:RUOYI_PROFILE)
Write-Host "Starting backend service..."
& $javaExe -Xms256m -Xmx1024m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=512m -jar $jar
