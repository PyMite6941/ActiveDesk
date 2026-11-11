# ActiveDesk battery guard for Windows.
#
# Runs elevated from the "\ActiveDesk\Battery" scheduled task that `activedesk battery install` creates once
# (one UAC prompt). The ActiveDesk node starts it on demand; it exits on its own, restoring normal charging,
# as soon as the node stops or the feature is switched off. So the range only applies while ActiveDesk runs.
#
# Windows has no vendor-neutral API to pause charging, so this detects the laptop's firmware interface:
#   ASUS  ATK ACPI WMI, device 0x00120057 (same control as MyASUS "Battery Health Charging").
#         Firmware has only a stop limit, so the range is emulated:
#           at/above max -> limit := min  (charging pauses, laptop runs on wall power, % holds)
#           at/below min -> limit := max  (charging resumes up to max)
#   Dell  Dell Command | Configure (cctk.exe), PrimaryBattChargeCfg=Custom:start-stop. A native range.
# Anything else reports "unsupported" and changes nothing.
#
# Security: runs with admin rights but only reads three keys from the user's config, parsed as integers and
# clamped. Nothing from the config is executed or passed to a shell.

param([Parameter(Mandatory = $true)][string]$UserHome)

$ErrorActionPreference = 'Stop'
$StateDir = Join-Path $env:ProgramData 'ActiveDesk'
$StatusFile = Join-Path $StateDir 'battery-status.json'
$ConfigFile = Join-Path $UserHome 'config.properties'
$PidFile = Join-Path $UserHome 'node.pid'
New-Item -ItemType Directory -Force -Path $StateDir | Out-Null

$mutex = New-Object System.Threading.Mutex($false, 'Global\ActiveDeskBatteryGuard')
if (-not $mutex.WaitOne(0)) { exit 0 }   # already running

function Write-Status([hashtable]$s) {
    $s['updated'] = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    [IO.File]::WriteAllText($StatusFile, ($s | ConvertTo-Json -Compress))   # UTF-8, no BOM
}

function Read-Settings {
    $r = @{ enabled = $false; min = 40; max = 80 }
    if (-not (Test-Path -LiteralPath $ConfigFile)) { return $r }
    foreach ($line in [IO.File]::ReadAllLines($ConfigFile)) {
        if ($line -match '^\s*battery\.enabled\s*[=:]\s*(true|false)\s*$') { $r.enabled = ($Matches[1] -eq 'true') }
        elseif ($line -match '^\s*battery\.min\s*[=:]\s*(\d{1,3})\s*$') { $r.min = [int]$Matches[1] }
        elseif ($line -match '^\s*battery\.max\s*[=:]\s*(\d{1,3})\s*$') { $r.max = [int]$Matches[1] }
    }
    $r.max = [Math]::Max(25, [Math]::Min(100, $r.max))
    $r.min = [Math]::Max(20, [Math]::Min($r.max - 5, $r.min))
    return $r
}

function Test-NodeAlive {
    if (-not (Test-Path -LiteralPath $PidFile)) { return $false }
    $id = ([IO.File]::ReadAllText($PidFile)).Trim()
    if ($id -notmatch '^\d{1,10}$') { return $false }
    $p = Get-Process -Id ([int]$id) -ErrorAction SilentlyContinue
    if ($null -eq $p -or $p.ProcessName -notmatch '^(javaw?|ActiveDesk)$') { return $false }
    # A PID can be reused after a crash. The node writes node.pid after it starts, so a process that
    # started later than the file was written is a different program: treat the node as gone.
    try { if ($p.StartTime -gt (Get-Item -LiteralPath $PidFile).LastWriteTime.AddSeconds(5)) { return $false } } catch { }
    return $true
}

# Wait up to $seconds, but return early (false) as soon as the range is switched off or the node is gone,
# so 'activedesk battery off' and quitting ActiveDesk restore normal charging within ~2 s.
function Wait-WhileWanted([int]$seconds) {
    for ($i = 0; $i -lt $seconds; $i += 2) {
        Start-Sleep -Seconds 2
        if (-not (Read-Settings).enabled -or -not (Test-NodeAlive)) { return $false }
    }
    return $true
}

# ------------------------------------------------------------------ vendor backends

$Backend = $null

$atk = Get-CimInstance -Namespace root/wmi -ClassName AsusAtkWmi_WMNB -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -ne $atk) {
    $Backend = @{
        name = 'ASUS firmware charge limit'; native = $false
        apply = { param($limit) Invoke-CimMethod -InputObject $atk -MethodName DEVS -Arguments @{ Device_ID = [uint32]0x00120057; Control_status = [uint32]$limit } | Out-Null }
        restore = { Invoke-CimMethod -InputObject $atk -MethodName DEVS -Arguments @{ Device_ID = [uint32]0x00120057; Control_status = [uint32]100 } | Out-Null }
    }
}

if ($null -eq $Backend) {
    $cctk = @("$env:ProgramFiles\Dell\Command Configure\X86_64\cctk.exe",
              "${env:ProgramFiles(x86)}\Dell\Command Configure\X86_64\cctk.exe") |
            Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
    if ($cctk) {
        # Remember the user's own setting ONCE, on disk: a guard started after a crash would otherwise
        # read back our custom range and "restore" that. Deleted after a successful restore.
        $origFile = Join-Path $StateDir 'dell-original.txt'
        if (Test-Path -LiteralPath $origFile) { $script:DellOriginal = ([IO.File]::ReadAllText($origFile)).Trim() }
        else {
            $before = (& $cctk '--PrimaryBattChargeCfg' 2>$null | Out-String)
            $script:DellOriginal = if ($before -match 'PrimaryBattChargeCfg=(Standard|Express|Adaptive|PrimAcUse|Custom:\d{1,3}-\d{1,3})') { $Matches[1] } else { 'Adaptive' }
            [IO.File]::WriteAllText($origFile, $script:DellOriginal)
        }
        if ($script:DellOriginal -notmatch '^(Standard|Express|Adaptive|PrimAcUse|Custom:\d{1,3}-\d{1,3})$') { $script:DellOriginal = 'Adaptive' }
        $Backend = @{
            name = 'Dell Command | Configure'; native = $true
            # Dell limits: start 50-95, stop 55-100, at least 5 apart
            apply = { param($min, $max)
                $hi = [Math]::Max(55, [Math]::Min(100, $max)); $lo = [Math]::Max(50, [Math]::Min($hi - 5, $min))
                $out = & $cctk "--PrimaryBattChargeCfg=Custom:$lo-$hi" 2>&1 | Out-String
                if ($LASTEXITCODE -ne 0) { throw "cctk failed ($LASTEXITCODE): $($out.Trim()) - a BIOS admin password blocks changes" }
                return "$lo-$hi" }
            restore = {
                $out = & $cctk "--PrimaryBattChargeCfg=$script:DellOriginal" 2>&1 | Out-String
                if ($LASTEXITCODE -ne 0) { throw "cctk failed ($LASTEXITCODE): $($out.Trim())" }
                Remove-Item -LiteralPath $origFile -Force -ErrorAction SilentlyContinue }
        }
    }
}

if ($null -eq $Backend) {
    Write-Status @{ running = $false; supported = $false
        message = 'This laptop has no charge-limit interface ActiveDesk can drive (supported on Windows: ASUS, Dell with Dell Command | Configure). Use the vendor app or BIOS battery setting instead.' }
    $mutex.ReleaseMutex()
    exit 0
}

# ------------------------------------------------------------------ loop

$applied = ''
$holding = $false
$remHist = @()
$first = $true
$failed = $false
try {
    while ($true) {
        $cfg = Read-Settings
        if (-not $cfg.enabled -or -not (Test-NodeAlive)) { break }

        $bat = Get-CimInstance Win32_Battery | Select-Object -First 1
        if ($null -eq $bat) { Write-Status @{ running = $true; supported = $true; backend = $Backend.name; message = 'no battery found' }; if (-not (Wait-WhileWanted 30)) { break }; continue }
        $pct = [int]$bat.EstimatedChargeRemaining
        $st = Get-CimInstance -Namespace root/wmi -ClassName BatteryStatus -ErrorAction SilentlyContinue | Select-Object -First 1

        if ($Backend.native) {
            $key = "$($cfg.min)-$($cfg.max)"
            if ($key -ne $applied) { $applied = $key; $eff = & $Backend.apply $cfg.min $cfg.max }
            $holding = $false   # the firmware decides; nothing to report beyond the range
            $msg = "firmware keeps the battery between $eff%"
        } else {
            if ($pct -ge $cfg.max) { $holding = $true }
            elseif ($pct -le $cfg.min) { $holding = $false }
            elseif ($first) { $holding = $false }   # starting in between: top up to max, then hold
            $want = if ($holding) { $cfg.min } else { $cfg.max }
            if ("$want" -ne $applied) { $applied = "$want"; & $Backend.apply $want }   # mark first: a failed apply still gets restored
            # Trust the battery, not the firmware's "OK": some models (several ASUS Vivobooks) accept the
            # limit but only apply it after the charger is reconnected. Say so instead of claiming a pause.
            # Judge by stored energy, not the Charging flag: Windows reports Charging=True even while the
            # battery only trickles a few mWh. "Still charging" = gained >0.5% of capacity over ~2 minutes.
            $full = (Get-CimInstance -Namespace root/wmi -ClassName BatteryFullChargedCapacity -ErrorAction SilentlyContinue | Select-Object -First 1).FullChargedCapacity
            if ($holding -and $st -and $st.PowerOnline -and $full) { $remHist += , [int64]$st.RemainingCapacity } else { $remHist = @() }
            if ($remHist.Count -gt 5) { $remHist = $remHist[-5..-1] }
            $stillCharging = $remHist.Count -ge 5 -and ($remHist[-1] - $remHist[0]) -gt ($full * 0.005)
            $msg = if ($stillCharging) { "limit set to $($cfg.min)% but the battery is still charging - unplug the charger and plug it back in to apply it" }
                   elseif ($holding) { "charging paused at $pct% until it drops to $($cfg.min)%" }
                   else { "charging allowed up to $($cfg.max)%" }
        }
        $first = $false

        Write-Status @{
            running = $true; supported = $true; backend = $Backend.name; percent = $pct; min = $cfg.min; max = $cfg.max
            holding = $holding; charging = [bool]($st -and $st.Charging); pluggedIn = [bool]($st -and $st.PowerOnline); message = $msg
        }
        if (-not (Wait-WhileWanted 30)) { break }
    }
}
catch {
    $failed = $true
    Write-Status @{ running = $false; supported = $true; backend = $Backend.name; message = "guard error: $($_.Exception.Message)" }
}
finally {
    # Always put the firmware back to normal charging, even if this run never changed anything (a run
    # with the range off is exactly how ActiveDesk repairs a limit left behind by a crash). Retried,
    # because a single failed WMI call must never leave the laptop stuck at a low limit.
    $restoreError = $null
    for ($try = 1; $try -le 5; $try++) {
        try { & $Backend.restore; $restoreError = $null; break }
        catch { $restoreError = $_.Exception.Message; Start-Sleep -Seconds 2 }
    }
    if ($restoreError) {
        Write-Status @{ running = $false; supported = $true; backend = $Backend.name; restored = $false
            message = "could not restore normal charging ($restoreError). Run 'activedesk battery reset', or restart the laptop." }
    } elseif (-not $failed) {
        Write-Status @{ running = $false; supported = $true; backend = $Backend.name; restored = $true; restoredAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            message = 'off - normal charging restored' }
    } else {
        $prev = try { [IO.File]::ReadAllText($StatusFile) | ConvertFrom-Json } catch { $null }
        Write-Status @{ running = $false; supported = $true; backend = $Backend.name; restored = $true; restoredAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            message = "$(if ($prev) { $prev.message } else { 'guard error' }) (normal charging restored)" }
    }
    $mutex.ReleaseMutex()
}
