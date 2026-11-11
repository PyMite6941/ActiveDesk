# ActiveDesk uninstaller for Windows.
#
#   irm https://pymite6941.github.io/ActiveDesk/uninstall.ps1 | iex
#
# Runs `activedesk uninstall --yes`, which stops ActiveDesk, puts the battery back to normal charging,
# removes the battery/power setup (one admin prompt, only if it was installed), the autostart entry, the
# Start-menu entry, the PATH entry and the program folder. Your data folder (~\.activedesk) is kept.
#
# If the program folder is already gone (deleted by hand), it fetches the latest release to a temp
# folder, verifies its SHA-256, and runs the uninstaller from there, so leftovers still get cleaned.
#
# Options (environment variables):
#   $env:ACTIVEDESK_PURGE = '1'   also delete your data folder (config, PIN, chat history, logs)

$ErrorActionPreference = 'Stop'
$Repo = 'PyMite6941/ActiveDesk'
function Say($m) { Write-Host "  $m" }

Write-Host "`n  == ActiveDesk uninstaller ==`n"
$java = $null
foreach ($n in 'java.exe', 'javaw.exe') {
    $c = Get-Command $n -ErrorAction SilentlyContinue
    if ($c) { $java = Join-Path (Split-Path $c.Source) 'java.exe'; break }
}
if (-not $java -or -not (Test-Path -LiteralPath $java)) {
    Say 'Java is not installed, so ActiveDesk cannot be running. To clean up by hand, delete:'
    Say "  $env:LOCALAPPDATA\ActiveDesk, and ActiveDesk.vbs in shell:startup, and ActiveDesk.lnk in the Start menu"
    Say 'If you set up battery control, run as admin:  Unregister-ScheduledTask -TaskPath ''\ActiveDesk\'' -TaskName Battery'
    return   # not `exit`: under `irm | iex` that would close your PowerShell window
}

$jar = Join-Path $env:LOCALAPPDATA 'ActiveDesk\activedesk.jar'
$tmp = $null
try {
    if (-not (Test-Path -LiteralPath $jar)) {
        Say 'The installed copy is gone; fetching the latest release to run the uninstaller from a temp folder.'
        $base = "https://github.com/$Repo/releases/latest/download"
        $tmp = Join-Path ([IO.Path]::GetTempPath()) ('activedesk-uninstall-' + [guid]::NewGuid())
        New-Item -ItemType Directory -Path $tmp | Out-Null
        Invoke-WebRequest -UseBasicParsing -Uri "$base/activedesk.jar" -OutFile "$tmp\activedesk.jar"
        Invoke-WebRequest -UseBasicParsing -Uri "$base/SHA256SUMS.txt" -OutFile "$tmp\SHA256SUMS.txt"
        $line = Get-Content "$tmp\SHA256SUMS.txt" | Where-Object { $_ -match ' activedesk\.jar$' } | Select-Object -First 1
        $want = if ($line) { ($line -split ' ')[0].ToLower() } else { '' }
        $got = (Get-FileHash "$tmp\activedesk.jar" -Algorithm SHA256).Hash.ToLower()
        if (-not $want -or $want -ne $got) { Say 'Checksum mismatch: the download is corrupt or was tampered with. Nothing was changed.'; return }
        $jar = "$tmp\activedesk.jar"
    }
    $opts = @('uninstall', '--yes')
    if ($env:ACTIVEDESK_PURGE -eq '1') { $opts += '--purge' }
    & $java -cp $jar dev.activedesk.ActiveDesk @opts
    if ($LASTEXITCODE -ne 0) { Write-Host "`n  The uninstaller reported a problem (exit $LASTEXITCODE). See the messages above." -ForegroundColor Yellow }
} finally {
    if ($tmp) { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }
}
