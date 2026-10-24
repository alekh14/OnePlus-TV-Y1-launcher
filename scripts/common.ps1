# Shared helpers. Dot-source this from the other scripts:  . "$PSScriptRoot\common.ps1"
# 'Continue' on purpose: in Windows PowerShell 5.1, 'Stop' turns any adb stderr text (e.g. "unauthorized")
# into a terminating error. The scripts throw explicitly where a failure must stop the run.
$ErrorActionPreference = 'Continue'
$script:Root     = Split-Path -Parent $PSScriptRoot
$script:StateDir  = Join-Path $script:Root 'state'
$script:ShotDir   = Join-Path $script:Root 'screenshots'
$script:BackupDir = Join-Path $script:Root 'backups'
$script:Serial    = $null
foreach ($d in @($script:StateDir, $script:ShotDir, $script:BackupDir)) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d | Out-Null }
}

function Find-Adb {
    $local = Join-Path $script:Root 'platform-tools\adb.exe'
    if (Test-Path $local) { return $local }
    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "adb.exe not found. Download 'SDK Platform-Tools for Windows' from developer.android.com/tools/releases/platform-tools and unzip it so that platform-tools\adb.exe sits next to the scripts folder (or add it to PATH)."
}
$script:Adb = Find-Adb

function Set-Serial([string]$s) {
    $script:Serial = $s
    Set-Content -Path (Join-Path $script:StateDir 'device.txt') -Value $s
}

function Get-Serial {
    if ($script:Serial) { return $script:Serial }
    $f = Join-Path $script:StateDir 'device.txt'
    if (-not (Test-Path $f)) { throw "No TV saved yet. Run scripts\1-connect.ps1 first." }
    $script:Serial = (Get-Content $f -Raw).Trim()
    return $script:Serial
}

# Run adb against the saved TV. Usage: Invoke-Adb shell pm list packages
function Invoke-Adb {
    $s = Get-Serial
    & $script:Adb -s $s @args
}

# Run a shell command on the TV and return trimmed output lines (CRLF safe).
function Invoke-Shell {
    $out = Invoke-Adb shell @args 2>&1
    $lines = @()
    foreach ($l in $out) { $lines += ([string]$l).TrimEnd("`r", "`n") }
    return $lines
}

function Get-Packages([string]$flags) {
    # flags e.g. '' (all), '-s' (system), '-3' (third party), '-d' (disabled)
    $raw = if ($flags) { Invoke-Shell pm list packages $flags } else { Invoke-Shell pm list packages }
    $pk = @()
    foreach ($l in $raw) { if ($l -like 'package:*') { $pk += $l.Substring(8).Trim() } }
    return ($pk | Sort-Object -Unique)
}

# One pm command per shell call, then verify via the disabled list.
function Disable-Pkg([string]$pkg) {
    $r = Invoke-Shell pm disable-user --user 0 $pkg
    return ($r -join ' ')
}
function Enable-Pkg([string]$pkg) {
    $r = Invoke-Shell pm enable $pkg
    return ($r -join ' ')
}

function Save-Shot([string]$name) {
    $remote = '/sdcard/tv_shot.png'
    Invoke-Adb shell screencap -p $remote | Out-Null
    $file = Join-Path $script:ShotDir ("{0}-{1}.png" -f (Get-Date -Format 'HHmmss'), $name)
    Invoke-Adb pull $remote $file | Out-Null
    Invoke-Adb shell rm $remote | Out-Null
    if (Test-Path $file) {
        Write-Host "  screenshot -> $file"
    } else {
        Write-Host "  (screenshot failed - some TVs block screencap on protected content)" -ForegroundColor Yellow
    }
    return $file
}

# Android key codes
$script:Keys = @{ HOME = 3; BACK = 4; UP = 19; DOWN = 20; LEFT = 21; RIGHT = 22; OK = 23; MENU = 82 }
function Send-Key([string]$name, [int]$times = 1) {
    for ($i = 0; $i -lt $times; $i++) {
        Invoke-Adb shell input keyevent $script:Keys[$name] | Out-Null
        Start-Sleep -Milliseconds 650
    }
}

function Add-ToolkitLog([string]$pkg) {
    # Remember every package this toolkit disabled so restore.ps1 can undo exactly that.
    Add-Content -Path (Join-Path $script:StateDir 'disabled-by-toolkit.txt') -Value $pkg
}

function Confirm-Step([string]$question) {
    $a = Read-Host "$question [y/N]"
    return ($a -match '^(y|yes)$')
}
