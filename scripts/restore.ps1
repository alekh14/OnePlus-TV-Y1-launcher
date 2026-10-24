# Undo everything this toolkit did: re-enable every package it disabled (including the stock
# launcher), remove the dock setting, and optionally uninstall the custom launcher.
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\restore.ps1 [-RemoveLauncher]
param([switch]$RemoveLauncher)

. "$PSScriptRoot\common.ps1"

$log = Join-Path $script:StateDir 'disabled-by-toolkit.txt'
$pkgs = @()
if (Test-Path $log) { $pkgs = Get-Content $log | Where-Object { $_.Trim() } | Sort-Object -Unique }

if ($pkgs.Count -eq 0) {
    Write-Host "Nothing recorded as disabled by this toolkit."
} else {
    Write-Host "Re-enabling $($pkgs.Count) packages (one pm call each) ..."
    $failed = @()
    foreach ($p in $pkgs) {
        $r = Enable-Pkg $p
        Write-Host ("  {0,-55} {1}" -f $p, $r)
        if ($r -notmatch 'enabled') { $failed += $p }
    }
    $stillOff = Get-Packages '-d'
    $left = $pkgs | Where-Object { $stillOff -contains $_ }
    if ($left) {
        Write-Host "These are still disabled - run 'adb shell pm enable <name>' by hand:" -ForegroundColor Yellow
        $left | ForEach-Object { Write-Host "  $_" }
    } else {
        Write-Host "All toolkit-disabled packages are enabled again." -ForegroundColor Green
        Remove-Item $log -ErrorAction SilentlyContinue
    }
}

Write-Host "Removing the dock setting ..."
Invoke-Shell settings delete secure tvl_dock | Out-Null

if ($RemoveLauncher) {
    Write-Host "Uninstalling the custom launcher ..."
    Invoke-Shell pm uninstall com.mylauncher.tv | ForEach-Object { Write-Host "  $_" }
}

Write-Host "Pressing Home and taking a screenshot ..."
Send-Key 'HOME'
Start-Sleep -Seconds 2
Save-Shot 'after-restore' | Out-Null
Write-Host "If the TV asks which Home app to use, pick the original one and choose 'Always'. A reboot also helps." -ForegroundColor Cyan
