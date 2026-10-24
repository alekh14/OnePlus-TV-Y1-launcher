# OnePlus TV Y1S: debloat + Apple-TV-style launcher

Everything runs from a Windows 10 PC over Wi-Fi ADB. Nothing is uninstalled; apps are only
*disabled* and every change can be undone with `scripts\restore.ps1`.

> Status: the PowerShell scripts and the launcher source were written without access to your TV or
> an Android SDK, so neither has been run or compiled yet. Expect a round of fixes after your first
> build. Send me the Gradle error text or the screenshots and I will correct the code.

## What you need

1. **ADB**: download "SDK Platform-Tools for Windows", unzip it into this folder so you have
   `platform-tools\adb.exe` (or add adb to PATH).
2. **To build the launcher**: Android Studio (installs JDK 17 and the Android SDK) and Gradle.
   Easiest: open the `launcher` folder in Android Studio once and let it sync; it creates
   `gradlew.bat` and downloads SDK platform 34. Or run `winget install Gradle.Gradle`.
3. **On the TV**: Settings > Device Preferences > About > click *Build* 7 times, then Developer
   options > USB debugging ON (and Network debugging if present).

Run scripts like this from the toolkit folder in PowerShell:

```
powershell -ExecutionPolicy Bypass -File scripts\1-connect.ps1
```

## Build the APK without Android Studio (GitHub Actions)

1. Create a free GitHub account and a new **private** repository.
2. Upload everything in this folder to it (drag the contents into the repo page; make sure
   `.github/workflows/build-apk.yml` is included - if the hidden `.github` folder does not upload, use
   *Add file > Create new file*, type the name `.github/workflows/build-apk.yml` and paste the file contents).
3. Open the **Actions** tab > "Build launcher APK" > **Run workflow**. It takes a few minutes.
4. Download the `tv-launcher-apk` artifact, unzip it to get `app-debug.apk`.
5. Install it: `adb install -r app-debug.apk`, then `adb shell pm grant com.mylauncher.tv android.permission.ACCESS_COARSE_LOCATION`.
   To keep using the toolkit's checks, copy the APK to `launcher\app\build\outputs\apk\debug\app-debug.apk`
   and run `build-and-install.ps1 -SkipBuild`.
6. If the build fails, open the failed step, copy the red error lines and send them to me.

## Steps

| # | Command | What it does |
|---|---------|--------------|
| 1 | `scripts\1-connect.ps1` | Connects to `<TV-IP>:5555`. Accept **"Allow USB debugging?"** on the TV when it appears. Prints model/Android version and takes a screenshot. |
| 2 | `scripts\2-backup.ps1` | Saves package lists, disabled list, `getprop` and all settings to `backups\<timestamp>\`. |
| 3 | `scripts\3-debloat.ps1` | Prints a categorised plan and changes nothing. Review it, edit `config\keep.txt` / `config\disable-extra.txt`, then run it with `-Apply`. |
| 4 | `build-and-install.ps1` | Builds and installs the launcher, sets the dock, tests navigation with key presses and screenshots, sets it as default Home, disables the stock launcher (each step asks first). |
| - | `scripts\restore.ps1` | Re-enables everything the toolkit disabled. Add `-RemoveLauncher` to uninstall the launcher too. |

All screenshots go to `screenshots\` (each is pulled from the TV and opened). Look at them after
each step.

## Your choices (already applied)

* Dock: Netflix, YouTube, Prime Video, JioHotstar (in that order).
* Kept: Chromecast, Miracast, voice assistant.
* Not ticked, so planned for disabling: the system-update service. That means no OTA firmware/security
  updates until you re-enable it (`restore.ps1`).
* Streaming and preloaded apps are only listed by the debloat plan, never touched, unless you add them to
  `config\disable-extra.txt`.

## What your TV's package list showed

* Lean firmware (about 106 packages, built by Skyworth/Coocaa with a MediaTek chip). There is little
  real bloat, so `config\disable-extra.txt` is pre-filled with the demo, factory-test, e-manual and
  recommendation packages found in your list. The generic patterns in `3-debloat.ps1` match almost
  nothing here, because Google and MediaTek packages are protected.
* No separate system-update app exists in the list, so the "update service" category is empty. The
  only `ota` package is `com.airoha.ble.ota`, which updates the remote and is left alone.
* Netflix, YouTube and Prime Video are installed. **JioHotstar is not**: install it from the Play Store,
  then run `scripts\4-set-dock.ps1` (until then the dock shows 3 apps).
* Stock launcher: `com.google.android.tvlauncher` (Android TV). `build-and-install.ps1` offers to disable it.
* Live TV/inputs go through `com.mediatek.wwtv.tvcenter` (there is no Google Live Channels app), so the
  Inputs dropdown may need adjusting after you test it.

## Re-enable a single app

```
adb shell pm enable com.package.name
```

Or run `scripts\restore.ps1` to re-enable everything the toolkit disabled. Find a package name with
`adb shell pm list packages | findstr /i keyword`. Disabled packages are listed by
`adb shell pm list packages -d`.

## Change the dock order or apps (no rebuild)

Edit `config\dock.txt` (one slot per line, max 4; several comma-separated package names on a line means
"first installed wins"), then:

```
powershell -ExecutionPolicy Bypass -File scripts\4-set-dock.ps1
```

This stores the list in a TV setting (`tvl_dock`) that the launcher reads.

## Wallpaper

The default is pitch black. To use a picture, copy a JPEG to
`launcher\app\src\main\assets\wallpaper.jpg` (about 1920x1080 is plenty) and run
`build-and-install.ps1` again. It is darkened automatically so text stays readable. Delete the file
and rebuild to go back to black.

## Navigation (D-pad)

* Up/Down move exactly one row. Up from the dock goes to the top bar; Down from the top bar returns.
* The ends of the top bar do not fall through to tiles.
* Down from the dock goes to the same column in the first grid row; the grid scrolls up.
* Back from the grid returns to the dock. Home always returns to the dock.
* The last focused dock app and grid tile are remembered across restarts.

## Troubleshooting

* **Screen is blank after disabling the stock launcher**: connect with ADB and run
  `scripts\restore.ps1`, or `adb shell pm enable com.google.android.tvlauncher`.
* **Wi-Fi pill says "Connected" instead of the name**: Android hides the SSID unless location
  permission is granted and location is on. The installer grants the permission; also turn Location on in TV settings.
* **An Inputs item does nothing**: the TV's Live TV app must handle passthrough inputs. If one input
  fails, tell me which; there may be an OnePlus-specific intent to use.
* **Unauthorized / offline in adb**: run `adb disconnect`, then `scripts\1-connect.ps1`, and accept the prompt on the TV.
* **Build fails**: copy the last 30 lines of the Gradle output and send them to me.

## Layout of this folder

```
scripts\        connect, backup, debloat, set-dock, restore (PowerShell)
config\         dock.txt, keep.txt, disable-extra.txt
launcher\       Android Studio / Gradle project (Java, RecyclerView, no blur, no network)
state\          written at run time (device IP, list of disabled packages)
backups\        written at run time
screenshots\    written at run time
```

Font: Outfit (SIL Open Font License), downloaded by `build-and-install.ps1`; falls back to the system sans-serif if the download fails.
