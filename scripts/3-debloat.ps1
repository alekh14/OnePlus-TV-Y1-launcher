# Sort installed packages into categories and (optionally) disable the unwanted ones.
# Nothing is uninstalled: only "pm disable-user --user 0", which restore.ps1 reverses.
#
# Step 1 (safe):   powershell -ExecutionPolicy Bypass -File scripts\3-debloat.ps1
#                  -> prints the plan and writes state\debloat-plan.txt. Changes nothing.
# Step 2 (apply):  powershell -ExecutionPolicy Bypass -File scripts\3-debloat.ps1 -Apply
#
# Your choices so far: KEEP Chromecast, Miracast, voice assistant. System-update service is NOT
# ticked, so it is in the plan. Streaming apps and preloaded apps are only listed, never touched,
# unless you put names in config\disable-extra.txt.
param([switch]$Apply)

. "$PSScriptRoot\common.ps1"

# ---------- patterns (case-insensitive regex on the package name) ----------
# Never touched, whatever else matches. Google's stack, AOSP, this launcher, SoC vendor core.
$protected = '^(android|com\.android\..*|com\.google\..*|com\.mylauncher\.tv|com\.mediatek\..*|com\.amlogic\..*|com\.realtek\..*|org\.chromium\..*|com\.svox\..*)$'
# Packages needed for the features you said you use (cast, mirroring, assistant, remote/voice, input, setup).
$keepFeatures = '(mediashell|castreceiver|chromecast|\.cast\b|miracast|wfd|wifidisplay|wifi-display|assistant|katniss|voice|speech|bluetooth|remote|inputmethod|tvinput|tuner|livetv|hdmi|cec|settings|setupwizard|permission|installer|keychain|webview|certinstaller|backup)'

$cats = [ordered]@{
  'Manufacturer telemetry / analytics' = '(statistic|analytic|telemetry|datacollect|bugreport|logkit|mtklogger|crashbox|feedback|dcs\b|olog|trackingservice|usagestats)'
  'Manufacturer content and ad services' = '(recommend|promot|advert|adservice|\.ads?\b|\.ads?\.|appmarket|appstore|hotapps|mixedcontent|contentprovider|contentservice|shopping|\.shop\b|\.store\b|marketplace|pushservice|patchwall|heytap\.(mcs|usercenter|browser|market|themestore|appmarket|cloud))'
  'Background services you probably do not need' = '(cleaner|booster|speedup|memoryclean|housekeeper|optimi[sz]er|dlna|airplay|screenshare|nearby|oneplusconnect|connect\.service)'
  'System update service (you did not tick it)' = '(\.ota\b|\.ota\.|systemupdate|softwareupdate|swupdate|upgrade|updater|fota|\.rus\b)'
}
$preloaded = '(facebook|twitter|instagram|linkedin|spotify|amazon\.(mp3|kindle|shopping)|booking|agoda|dailyhunt|gaana|saavn|wynk|games?\b|casino|solitaire|candy|\.news\b|newshunt|opera|puffin|uc\.browser|ucmobile)'
$streaming = '(netflix|hotstar|startv|jiocinema|jio\.media|primevideo|amazonvideo|youtube|zee5|sonyliv|voot|mxplayer|hungama|altbalaji|disney|hbo|apple\.tv|erosnow|sunnxt)'

# ---------- user config ----------
$keepFile = Join-Path $script:Root 'config\keep.txt'
$extraFile = Join-Path $script:Root 'config\disable-extra.txt'
$userKeep = @(); if (Test-Path $keepFile)  { $userKeep  = Get-Content $keepFile  | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith('#') } }
$userExtra = @(); if (Test-Path $extraFile) { $userExtra = Get-Content $extraFile | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith('#') } }

# ---------- read device ----------
$null = Get-Serial
if (-not (Test-Path (Join-Path $script:StateDir 'last-backup.txt'))) {
    throw "No backup found. Run scripts\2-backup.ps1 first."
}
Write-Host "Reading installed packages ..."
$all       = Get-Packages ''
$disabled  = Get-Packages '-d'
$active    = $all | Where-Object { $disabled -notcontains $_ }

$plan = [ordered]@{}
foreach ($k in $cats.Keys) { $plan[$k] = New-Object System.Collections.ArrayList }
$listPreloaded = New-Object System.Collections.ArrayList
$listStreaming = New-Object System.Collections.ArrayList
$listExtra     = New-Object System.Collections.ArrayList

foreach ($p in $active) {
    if ($userKeep -contains $p) { continue }
    if ($userExtra -contains $p) { [void]$listExtra.Add($p); continue }

    $isProtected = ($p -match $protected)
    $hit = $null
    foreach ($k in $cats.Keys) { if ($p -match $cats[$k]) { $hit = $k; break } }

    if ($hit) {
        # protected packages are only allowed through for pure log/telemetry tools
        if ($isProtected -and $p -notmatch '(mtklogger|logkit)') { continue }
        if ($p -match $keepFeatures -and $hit -ne 'Manufacturer telemetry / analytics') { continue }
        [void]$plan[$hit].Add($p)
        continue
    }
    if ($isProtected) { continue }
    if ($p -match $streaming) { [void]$listStreaming.Add($p); continue }
    if ($p -match $preloaded) { [void]$listPreloaded.Add($p); continue }
}

# ---------- show the plan ----------
$out = New-Object System.Collections.ArrayList
function Show([string]$s, $color) { Write-Host $s -ForegroundColor $color; [void]$out.Add($s) }

Show "=== DEBLOAT PLAN (device has $($active.Count) enabled packages) ===" 'Cyan'
$total = 0
foreach ($k in $plan.Keys) {
    Show "" 'White'
    Show "[$k]  ->  will be disabled ($($plan[$k].Count))" 'Yellow'
    foreach ($p in $plan[$k]) { Show "    $p" 'White' }
    $total += $plan[$k].Count
}
if ($listExtra.Count) {
    Show "" 'White'; Show "[From config\disable-extra.txt] -> will be disabled ($($listExtra.Count))" 'Yellow'
    foreach ($p in $listExtra) { Show "    $p" 'White' }
    $total += $listExtra.Count
}
Show "" 'White'
Show "[Preloaded apps]  -> NOT touched (add names to config\disable-extra.txt to remove)" 'Green'
foreach ($p in $listPreloaded) { Show "    $p" 'White' }
Show "" 'White'
Show "[Streaming apps]  -> NOT touched (you gave no preference)" 'Green'
foreach ($p in $listStreaming) { Show "    $p" 'White' }
Show "" 'White'
Show "Total to disable: $total" 'Cyan'
Set-Content -Path (Join-Path $script:StateDir 'debloat-plan.txt') -Value $out

if (-not $Apply) {
    Write-Host ""
    Write-Host "Nothing changed. Read the lists above (also saved in state\debloat-plan.txt)." -ForegroundColor Green
    Write-Host "Anything you want to protect: put its package name on its own line in config\keep.txt."
    Write-Host "When happy:  scripts\3-debloat.ps1 -Apply"
    return
}

# ---------- apply, one pm call per package, category by category ----------
Write-Host ""
Write-Host "Taking a 'before' screenshot ..."
Save-Shot 'before-debloat' | Out-Null

$todo = New-Object System.Collections.ArrayList
foreach ($k in $plan.Keys) {
    if ($plan[$k].Count -eq 0) { continue }
    if (Confirm-Step "Disable category '$k' ($($plan[$k].Count) packages)?") { [void]$todo.AddRange($plan[$k]) }
}
if ($listExtra.Count -and (Confirm-Step "Disable the $($listExtra.Count) packages from disable-extra.txt?")) { [void]$todo.AddRange($listExtra) }

$done = 0
foreach ($p in $todo) {
    $r = Disable-Pkg $p
    if ($r -match 'disabled-user|new state') { Add-ToolkitLog $p; $done++; Write-Host "  disabled  $p" }
    else { Write-Host "  SKIPPED   $p  ($r)" -ForegroundColor Yellow }
}

Write-Host ""
Write-Host "Verifying against the TV's disabled list ..."
$nowDisabled = Get-Packages '-d'
$missing = $todo | Where-Object { $nowDisabled -notcontains $_ }
Write-Host "$done of $($todo.Count) disabled; $(@($missing).Count) did not take effect (system-protected) - harmless."

Write-Host "Pressing Home and taking an 'after' screenshot (check the TV still works) ..."
Send-Key 'HOME'
Start-Sleep -Seconds 2
Save-Shot 'after-debloat' | Out-Null
Write-Host "Look at the screenshot. If anything is broken run scripts\restore.ps1" -ForegroundColor Cyan
