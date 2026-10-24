# Connect to the TV over ADB (port 5555) and take a first screenshot.
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\1-connect.ps1 [-Ip 192.168.1.50]
param([string]$Ip)

. "$PSScriptRoot\common.ps1"

if (-not $Ip) {
    Write-Host "Find the TV's IP on the TV: Settings > Network & Internet > (your Wi-Fi) > IP address."
    Write-Host "Or look in your router's device list. ARP entries currently on this PC:"
    try { arp -a | Select-String 'dynamic' | ForEach-Object { '  ' + $_.Line.Trim() } } catch { }
    $Ip = Read-Host "TV IP address"
}
$Ip = $Ip.Trim()
$serial = "$($Ip):5555"

Write-Host ""
Write-Host "Before continuing, on the TV: Settings > Device Preferences > About > tap 'Build' 7 times," -ForegroundColor Cyan
Write-Host "then Settings > Device Preferences > Developer options > turn ON 'USB debugging' (and 'Network debugging' if shown)." -ForegroundColor Cyan
Write-Host ""
Write-Host ">>> In a moment the TV will show 'Allow USB debugging?'. Tick 'Always allow from this computer' and press OK. <<<" -ForegroundColor Yellow
Write-Host ""

& $script:Adb start-server | Out-Null
& $script:Adb connect $serial

$ok = $false
for ($i = 0; $i -lt 30; $i++) {
    $state = (& $script:Adb -s $serial get-state 2>&1 | Out-String).Trim()
    if ($state -eq 'device') { $ok = $true; break }
    if ($i -eq 0 -or $i % 5 -eq 0) { Write-Host "  waiting for the TV to authorise this PC (state: $state) ..." }
    Start-Sleep -Seconds 2
    & $script:Adb connect $serial | Out-Null
}
if (-not $ok) { throw "Could not connect to $serial. Check the IP, that the TV and PC are on the same network, and that USB debugging is on." }

Set-Serial $serial
Write-Host "Connected to $serial" -ForegroundColor Green

$model   = (Invoke-Shell getprop ro.product.model) -join ''
$brand   = (Invoke-Shell getprop ro.product.brand) -join ''
$release = (Invoke-Shell getprop ro.build.version.release) -join ''
$sdk     = (Invoke-Shell getprop ro.build.version.sdk) -join ''
$ram     = (Invoke-Shell "grep MemTotal /proc/meminfo") -join ''
Set-Content -Path (Join-Path $script:StateDir 'sdk.txt') -Value $sdk

Write-Host ""
Write-Host "Device : $brand $model"
Write-Host "Android: $release (API $sdk)"
Write-Host "RAM    : $ram"
Write-Host ""
Write-Host "Taking a screenshot to confirm the TV responds..."
Save-Shot 'connect' | Out-Null
Write-Host "Next: scripts\2-backup.ps1"
