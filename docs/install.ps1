# ActiveDesk installer for Windows 10/11.
#
#   irm https://pymite6941.github.io/ActiveDesk/install.ps1 | iex
#
# What it does, step by step (no admin rights needed for ActiveDesk itself):
#   1. checks for Java 17+ and offers to install it (free Eclipse Temurin, via winget)
#   2. checks for Tailscale and offers to install it (free personal plan, via winget)
#   3. downloads activedesk.jar from the latest GitHub release and verifies its SHA-256
#   4. adds an `activedesk` command to your user PATH
#   5. starts the setup wizard (`activedesk setup`)
#
# Options (environment variables):
#   $env:ACTIVEDESK_VERSION = 'v1.2.0'   install a specific release instead of the latest
#   $env:ACTIVEDESK_JAR = 'C:\x.jar'     install this local jar instead of downloading (for testing)
#   $env:ACTIVEDESK_YES = '1'            accept every default without asking

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is 10x slower with the progress bar
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Repo   = 'PyMite6941/ActiveDesk'
$AppDir = Join-Path $env:LOCALAPPDATA 'ActiveDesk'
$Yes    = [bool]$env:ACTIVEDESK_YES

function Say($t)        { Write-Host "  $t" }
function Step($n, $t)   { Write-Host ""; Write-Host "  [$n] $t" -ForegroundColor Cyan }
function Fail($t)       { Write-Host ""; Write-Host "  ERROR: $t" -ForegroundColor Red; throw $t }
function Ask($q, $def) {
    if ($Yes) { return $def }
    $hint = if ($def) { '[Y/n]' } else { '[y/N]' }
    $a = Read-Host "  $q $hint"
    if ([string]::IsNullOrWhiteSpace($a)) { return $def }
    return $a.Trim().ToLower().StartsWith('y')
}
function Has($cmd) { [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }
function Refresh-Path {
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
}
function Find-Java {
    $cands = @()
    if (Has 'java') { $cands += (Get-Command java).Source }
    $cands += Get-ChildItem "$env:ProgramFiles\Eclipse Adoptium\*\bin\java.exe", "$env:ProgramFiles\Java\*\bin\java.exe", "$env:ProgramFiles\Microsoft\jdk-*\bin\java.exe" -ErrorAction SilentlyContinue | ForEach-Object FullName
    foreach ($j in $cands) {
        $v = (& cmd /c "`"$j`" -version 2>&1") | Select-Object -First 1
        if ("$v" -match 'version "(\d+)') { if ([int]$Matches[1] -ge 17) { return $j } }
    }
    return $null
}

Write-Host ""
Write-Host "  == ActiveDesk installer ==" -ForegroundColor Cyan
Say "Installs into $AppDir"

# 1. Java ------------------------------------------------------------------------------------------
Step '1/5' 'Java 17 or newer'
$java = Find-Java
if ($java) {
    Say "Found: $java"
} else {
    Say 'Java 17+ is not installed. It is free (Eclipse Temurin).'
    if ((Has 'winget') -and (Ask 'Install it now?' $true)) {
        winget install --id EclipseAdoptium.Temurin.21.JRE -e --accept-source-agreements --accept-package-agreements
        Refresh-Path
        $java = Find-Java
    }
    if (-not $java) { Fail 'Java 17+ is still missing. Install it from https://adoptium.net and run this again.' }
}
$javaw = Join-Path (Split-Path $java) 'javaw.exe'

# 2. Tailscale -------------------------------------------------------------------------------------
Step '2/5' 'Tailscale (free private network between your devices)'
if (Test-Path "$env:ProgramFiles\Tailscale\tailscale.exe") {
    Say 'Found Tailscale.'
} else {
    Say 'Tailscale is not installed.'
    if ((Has 'winget') -and (Ask 'Install it now?' $true)) {
        winget install --id tailscale.tailscale -e --accept-source-agreements --accept-package-agreements
        Say 'Open Tailscale from the Start menu and sign in (use the same account on all your devices).'
    } else {
        Say 'Get it from https://tailscale.com/download/windows'
    }
}

# 3. Download --------------------------------------------------------------------------------------
Step '3/5' 'Download ActiveDesk'
New-Item -ItemType Directory -Force -Path $AppDir, (Join-Path $AppDir 'lib') | Out-Null
$jar = Join-Path $AppDir 'activedesk.jar'
if ($env:ACTIVEDESK_JAR) {
    Copy-Item -LiteralPath $env:ACTIVEDESK_JAR -Destination $jar -Force
    Say "Using local jar $env:ACTIVEDESK_JAR"
} else {
    $base = if ($env:ACTIVEDESK_VERSION) { "https://github.com/$Repo/releases/download/$env:ACTIVEDESK_VERSION" } else { "https://github.com/$Repo/releases/latest/download" }
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ('activedesk-' + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        Invoke-WebRequest -UseBasicParsing -Uri "$base/activedesk.jar" -OutFile "$tmp\activedesk.jar"
        Invoke-WebRequest -UseBasicParsing -Uri "$base/SHA256SUMS.txt" -OutFile "$tmp\SHA256SUMS.txt"
        $line = Get-Content "$tmp\SHA256SUMS.txt" | Where-Object { $_ -match ' activedesk\.jar$' } | Select-Object -First 1
        $want = if ($line) { ($line -split ' ')[0].ToLower() } else { '' }
        $got  = (Get-FileHash "$tmp\activedesk.jar" -Algorithm SHA256).Hash.ToLower()
        if (-not $want -or $want -ne $got) { Fail 'Checksum mismatch: the download is corrupt or was tampered with. Nothing was installed.' }
        Say "Checksum OK ($got)"
        Move-Item -Force "$tmp\activedesk.jar" $jar
    } finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }
}

# 4. Command ---------------------------------------------------------------------------------------
Step '4/5' "The 'activedesk' command"
$cmdFile = Join-Path $AppDir 'activedesk.cmd'
Set-Content -Encoding ASCII -Path $cmdFile -Value @(
    '@echo off',
    'rem created by the ActiveDesk installer',
    "`"$java`" -cp `"%~sdp0activedesk.jar;%~sdp0lib\*`" dev.activedesk.ActiveDesk %*"   # short path: non-ASCII user names stay safe
)
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (($userPath -split ';') -notcontains $AppDir) {
    [Environment]::SetEnvironmentVariable('Path', ($userPath.TrimEnd(';') + ';' + $AppDir).TrimStart(';'), 'User')
    Say "Added $AppDir to your PATH (new terminals will know 'activedesk')."
}
$env:Path += ";$AppDir"

# Start-menu shortcut: starts ActiveDesk if needed and opens the app (no console window)
$programs = [Environment]::GetFolderPath('Programs')
Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $programs 'ActiveDesk.url')   # older installers made a plain link
$sc = (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $programs 'ActiveDesk.lnk'))
$sc.TargetPath = $javaw
$sc.Arguments = "-cp `"$jar;$AppDir\lib\*`" dev.activedesk.ActiveDesk"
$sc.WorkingDirectory = $AppDir
$sc.Description = 'ActiveDesk: open the app'
$sc.Save()
Say "Start menu: 'ActiveDesk' starts it if needed and opens the app in your browser."

# 5. Setup wizard ----------------------------------------------------------------------------------
Step '5/5' 'Setup wizard'
$wizArgs = @('-cp', "$jar;$AppDir\lib\*", 'dev.activedesk.ActiveDesk', 'setup')
if ($Yes) { $wizArgs += '--yes' }
& $java @wizArgs
if ($LASTEXITCODE -ne 0) { Fail "The setup wizard stopped with an error (exit $LASTEXITCODE). Fix it, then run: activedesk setup" }
if (Ask 'Start ActiveDesk now (in the background)?' $true) {
    # no command = start the node and open the app in the browser
    Start-Process -FilePath $javaw -ArgumentList @('-cp', "`"$jar;$AppDir\lib\*`"", 'dev.activedesk.ActiveDesk') -WindowStyle Hidden
    Say 'Running. The app opens in your browser (http://127.0.0.1:47801/). Next time, just run: activedesk'
}
