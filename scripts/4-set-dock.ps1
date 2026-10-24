# Resolve config\dock.txt to installed package names and store them on the TV so the launcher
# shows them in the dock. No rebuild needed - run this any time you change dock.txt.
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\4-set-dock.ps1
. "$PSScriptRoot\common.ps1"

$file = Join-Path $script:Root 'config\dock.txt'
$installed = Get-Packages ''
$chosen = @()
$slot = 0
foreach ($line in (Get-Content $file)) {
    $t = $line.Trim()
    if (-not $t -or $t.StartsWith('#')) { continue }
    $slot++
    $found = $null
    foreach ($cand in ($t -split ',')) {
        $c = $cand.Trim()
        if ($installed -contains $c) { $found = $c; break }
    }
    if ($found) { Write-Host "  dock slot $slot -> $found"; $chosen += $found }
    else { Write-Host "  dock slot $slot -> NOT INSTALLED (tried: $t). Install it from the Play Store, then run this script again." -ForegroundColor Yellow }
}
if ($chosen.Count -gt 4) { $chosen = $chosen[0..3] }
if ($chosen.Count -eq 0) { throw "No dock apps are installed." }

$value = ($chosen -join ',')
Invoke-Shell settings put secure tvl_dock $value | Out-Null
$check = (Invoke-Shell settings get secure tvl_dock) -join ''
Write-Host "Stored on TV: $check"
if ($check -ne $value) { throw "The setting did not stick." }

# Make the launcher redraw
Invoke-Shell am start -n com.mylauncher.tv/.MainActivity | Out-Null
Start-Sleep -Seconds 2
Save-Shot 'dock' | Out-Null
