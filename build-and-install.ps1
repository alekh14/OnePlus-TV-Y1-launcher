# Build the launcher, install it on the TV, set it as default Home, then (after you confirm)
# disable the stock launcher. Takes screenshots and injects remote key presses to verify.
#
# Usage:  powershell -ExecutionPolicy Bypass -File build-and-install.ps1 [-SkipBuild]
# Needs:  scripts\1-connect.ps1 done, JDK 17 + Android SDK (Android Studio installs both) and Gradle
#         (or a gradlew.bat in launcher\ - Android Studio creates one when you open the project once).
param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\scripts\common.ps1"
$null = Get-Serial

$launcherDir = Join-Path $PSScriptRoot 'launcher'
$apk = Join-Path $launcherDir 'app\build\outputs\apk\debug\app-debug.apk'
$pkg = 'com.mylauncher.tv'
$act = "$pkg/.MainActivity"

# ---------- 1. TV API level -> minSdk ----------
$sdk = (Invoke-Shell getprop ro.build.version.sdk) -join ''
if (-not ($sdk -match '^\d+$')) { $sdk = '21' }
if ([int]$sdk -gt 34) { $sdk = '34' }
Write-Host "TV runs API $sdk - building with minSdk $sdk"

# ---------- 2. Outfit font (open licence, SIL OFL) ----------
$fontDir = Join-Path $launcherDir 'app\src\main\assets\fonts'
if (-not (Test-Path $fontDir)) { New-Item -ItemType Directory -Path $fontDir | Out-Null }
foreach ($w in @('Thin', 'Light', 'Regular')) {
    $f = Join-Path $fontDir "Outfit-$w.ttf"
    if (Test-Path $f) { continue }
    $ok = $false
    foreach ($url in @(
        "https://github.com/Outfitio/Outfit-Fonts/raw/main/fonts/ttf/Outfit-$w.ttf",
        "https://raw.githubusercontent.com/Outfitio/Outfit-Fonts/main/fonts/ttf/Outfit-$w.ttf")) {
        try { Invoke-WebRequest -Uri $url -OutFile $f -UseBasicParsing; if ((Get-Item $f).Length -gt 20000) { $ok = $true; break } } catch { Remove-Item $f -ErrorAction SilentlyContinue }
    }
    if ($ok) { Write-Host "  downloaded Outfit-$w.ttf" }
    else { Write-Host "  could not download Outfit-$w.ttf - the launcher will use the system sans-serif instead." -ForegroundColor Yellow }
}
if (-not (Test-Path (Join-Path $fontDir 'Outfit-Thin.ttf'))) {
    Write-Host "  To get Outfit manually: fonts.google.com/specimen/Outfit > Download family, then copy static\Outfit-Thin.ttf, Outfit-Light.ttf, Outfit-Regular.ttf into $fontDir" -ForegroundColor Yellow
}

# ---------- 3. Build ----------
if (-not $SkipBuild) {
    $localProps = Join-Path $launcherDir 'local.properties'
    if (-not (Test-Path $localProps)) {
        $sdkDir = $env:ANDROID_HOME
        if (-not $sdkDir) { $sdkDir = $env:ANDROID_SDK_ROOT }
        if (-not $sdkDir) { $sdkDir = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
        if (-not (Test-Path $sdkDir)) { throw "Android SDK not found. Install Android Studio (it installs the SDK), open it once, and run this again." }
        Set-Content -Path $localProps -Value ("sdk.dir=" + ($sdkDir -replace '\\', '\\'))
    }
    $gradlew = Join-Path $launcherDir 'gradlew.bat'
    Push-Location $launcherDir
    try {
        if (Test-Path $gradlew) { & $gradlew assembleDebug "-PtvMinSdk=$sdk" }
        elseif (Get-Command gradle -ErrorAction SilentlyContinue) { & gradle assembleDebug "-PtvMinSdk=$sdk" }
        else { throw "No Gradle found. Either open the 'launcher' folder in Android Studio once (it creates gradlew.bat), or run: winget install Gradle.Gradle   then open a new terminal." }
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (see the messages above). Send me the error text and I will fix the code." }
    } finally { Pop-Location }
}
if (-not (Test-Path $apk)) { throw "APK not found at $apk" }
Write-Host "Built $apk" -ForegroundColor Green

# ---------- 4. Install ----------
Write-Host "Installing on the TV ..."
$r = (Invoke-Adb install -r $apk 2>&1 | Out-String).Trim()
Write-Host $r
if ($r -notmatch 'Success') { throw "Install failed." }
Invoke-Shell pm grant $pkg android.permission.ACCESS_COARSE_LOCATION | Out-Null   # lets the Wi-Fi pill show the network name

# ---------- 5. Dock apps ----------
& "$PSScriptRoot\scripts\4-set-dock.ps1"

# ---------- 6. First look + remote-key checks ----------
Write-Host "Starting the launcher and checking navigation with remote key presses ..."
Invoke-Shell am start -n $act | Out-Null
Start-Sleep -Seconds 3
Save-Shot 'launcher-start' | Out-Null

Send-Key 'RIGHT';  Save-Shot 'dock-right' | Out-Null
Send-Key 'LEFT'
Send-Key 'DOWN';   Save-Shot 'grid-row1' | Out-Null
Send-Key 'DOWN';   Save-Shot 'grid-row2' | Out-Null
Send-Key 'UP' 2;   Save-Shot 'back-to-dock' | Out-Null
Send-Key 'UP';     Save-Shot 'top-bar' | Out-Null
Send-Key 'RIGHT' 4; Save-Shot 'top-bar-right-end' | Out-Null
Send-Key 'LEFT' 4
Send-Key 'OK';     Save-Shot 'inputs-menu' | Out-Null
Send-Key 'DOWN';   Save-Shot 'inputs-menu-2' | Out-Null
Send-Key 'BACK'
Send-Key 'DOWN'
Write-Host "Open the screenshots folder: $script:ShotDir" -ForegroundColor Cyan
Write-Host "Check: black background, rounded dock at the bottom, glass highlight on the focused tile," -ForegroundColor Cyan
Write-Host "       Down scrolls the grid up, Up returns, the top bar stops at its ends, Inputs lists HDMI/AV/Live TV."

if (-not (Confirm-Step "Do the screenshots look right? Make the launcher the default Home and continue")) {
    Write-Host "Stopped. Send me the screenshots and a description and I will fix what is off."
    return
}

# ---------- 7. Make it the default Home ----------
Write-Host "Setting the default Home app ..."
$out = (Invoke-Shell cmd package set-home-activity $act) -join ' '
if ($out -match 'Unknown|Error|Exception|not found') { $out = (Invoke-Shell pm set-home-activity $act) -join ' ' }
Write-Host "  $out"
Invoke-Shell input keyevent 3 | Out-Null
Start-Sleep -Seconds 2
Save-Shot 'home-after-default' | Out-Null
$resolved = (Invoke-Shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME) -join ' '
Write-Host "  Home now resolves to: $resolved"

# ---------- 8. Disable the stock launcher ----------
$candidates = @('com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx', 'com.google.android.leanbacklauncher')
$allPk = Get-Packages ''
$found = @($candidates | Where-Object { $allPk -contains $_ })
$extra = @($allPk | Where-Object { $_ -match 'launcher' -and $_ -ne $pkg -and $candidates -notcontains $_ -and $_ -notmatch '^com\.google\.android\.(gms|packageinstaller)' })
if ($extra.Count) { Write-Host "Other packages with 'launcher' in the name (not touched automatically):"; $extra | ForEach-Object { Write-Host "  $_" } }
foreach ($p in $found) {
    if (Confirm-Step "Disable the stock launcher '$p'? (restore.ps1 brings it back)") {
        $res = Disable-Pkg $p
        Write-Host "  $res"
        if ($res -match 'disabled-user|new state') { Add-ToolkitLog $p }
        Start-Sleep -Seconds 1
        Send-Key 'HOME'
        Start-Sleep -Seconds 2
        Save-Shot ("after-disable-" + ($p -replace '.*\.', '')) | Out-Null
        Write-Host "  Look at that screenshot: it must show your launcher. If not run scripts\restore.ps1" -ForegroundColor Cyan
    }
}
Write-Host "Done. Press Home on your remote to confirm. Dock order: edit config\dock.txt then run scripts\4-set-dock.ps1" -ForegroundColor Green
