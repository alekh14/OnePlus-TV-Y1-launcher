# Back up the TV's package lists, properties and settings BEFORE changing anything.
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\2-backup.ps1
. "$PSScriptRoot\common.ps1"

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$dir = Join-Path $script:BackupDir $stamp
New-Item -ItemType Directory -Path $dir | Out-Null

Write-Host "Backing up to $dir"

function Save-Lines([string]$file, $lines) {
    Set-Content -Path (Join-Path $dir $file) -Value $lines -Encoding UTF8
}

Save-Lines 'packages-all.txt'        (Invoke-Shell pm list packages -f)
Save-Lines 'packages-disabled.txt'   (Invoke-Shell pm list packages -d)
Save-Lines 'packages-system.txt'     (Invoke-Shell pm list packages -s)
Save-Lines 'packages-3rdparty.txt'   (Invoke-Shell pm list packages -3)
Save-Lines 'getprop.txt'             (Invoke-Shell getprop)
Save-Lines 'settings-system.txt'     (Invoke-Shell settings list system)
Save-Lines 'settings-secure.txt'     (Invoke-Shell settings list secure)
Save-Lines 'settings-global.txt'     (Invoke-Shell settings list global)
Save-Lines 'home-activities.txt'     (Invoke-Shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME)

$count = (Get-Content (Join-Path $dir 'packages-all.txt')).Count
$dis   = (Get-Content (Join-Path $dir 'packages-disabled.txt')).Count
Write-Host "Saved $count packages ($dis already disabled), getprop and all settings."

Set-Content -Path (Join-Path $script:StateDir 'last-backup.txt') -Value $dir

# The restore script re-enables only what this toolkit disables (tracked in state\disabled-by-toolkit.txt).
Write-Host ""
Write-Host "Backup done. To undo everything later run: scripts\restore.ps1" -ForegroundColor Green
Write-Host "Next: scripts\3-debloat.ps1  (shows a plan first, changes nothing until you add -Apply)"
