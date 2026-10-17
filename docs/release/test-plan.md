# Release test plan (first beta)

Most bugs so far appeared only under particular conditions: after a reboot, in one screen state, with several Bluetooth devices at once. This plan looks for those on purpose.

## Hunts

| Hunt | Catches | Where | Owner |
|---|---|---|---|
| Soak: 8–12 h cycling screens, music, sleep/wake | Leaks, slow degradation, overnight crashes | Stick (on its wall adapter) | Yin |
| Monkey: 50,000 random keys (`adb shell monkey -p dev.glasslauncher`) | Crashes in odd state combinations | Emulator | Yang |
| Reboot matrix: 10 reboots across states (modules on/off, Wi-Fi off, no remote, Glass not default home) | Boot hangs, lost settings, Home guard | Stick | Yin |
| Failure states: offline, slow network, Spotify/Stremio uninstalled, full storage, stale cache | Blank screens, silent failures | Emulator | Yang |
| Hardware combinations: BT headphones + controller + music + video, connect/disconnect mid-play | Radio load, audio routing | Stick, with the user | Yin |
| Clean install: wipe, plain APK, follow `docs/release/v0.3.0-beta.md` only | Onboarding gaps, permission prompts | Plain AVD | Yang |
| Upgrade: install the current build, then the release build over it | Lost layouts, migrations | Emulator | Yang |
| Regression sweep: re-run every bug fixed in the last week (P137–P180) | Regressions | Both | Both |

## Stable means

- 12 h soak on the stick: no crash, freeze or reboot.
- Monkey: 50,000 events with no crash or ANR.
- 10 of 10 clean reboots.
- A clean install works by following the guide alone; an upgrade keeps settings.
- perf-gate within budget on every surface (P180).
- Nothing broken on a non-Fire device; missing features are hidden cleanly.

## Devices

| Priority | Device | Why |
|---|---|---|
| Must | Fire TV Stick 4K 2nd gen | Main target |
| Must | Plain Android TV AVD | Non-Fire build (passed P155) |
| Should | One Google TV device (Chromecast with Google TV / Onn 4K) | Largest non-Fire audience; different stock launcher |
| Should | One older or cheaper Fire stick (Lite / 3rd gen) | Weakest GPU, older Fire OS |
| Nice | Fire TV 4K Max, Nvidia Shield, a Fire TV Smart TV | Faster hardware, TV-specific inputs |

Emulators at API 28, 30, 31 and 34 cover OS-version crashes; smoothness needs real hardware.

## Results

Record each hunt's result below with the date, build and numbers.

### 2026-10-17: Yang, emulators (twin GlassFire_API30, plain GlassTV_API31 Android TV), master ca13f1b2 debug/release builds

| Hunt | Result |
|---|---|
| Monkey, 50,000 events, 50 ms throttle, key-heavy (nav 45%, majornav 40%, appswitch 5%, touch 3%, no system or "any" keys) | Twin: seed 1017, 50,000 events, no crash or ANR. Plain AVD: seed 3017, 50,000 events, no crash or ANR. A first plain-AVD run (seed 2017) stopped at event 20,012 with an ANR "no focused window"; the monkey's random "any" events had pressed POWER, which put the screen to sleep (`powerPress` in the log), so it was not a Glass fault. The seed-2017 run used flags in `/tmp/monkey-flags` (anyevent 7%); later runs used anyevent 0. |
| Failure states | Offline (wifi and data off): cold start and 3,000-event monkey, no crash, Home shows no featured shelf and no blank screen. Slow network (gsm speed, gprs delay): no crash. Spotify and Stremio not installed (twin has neither): no crash. Stale and corrupt caches (tile PNGs, featured JSON and the image cache overwritten with 37 random bytes and dated 2020), online and offline: no crash. **Full storage (/data at 0 bytes free): CRASH** at event 51, `IOException: write failed: ENOSPC` out of the DataStore config write on Main. Fixed in a3a7d0ca (`ConfigStore.update` logs and returns false); full-disk rerun clean. |
| Clean install on the plain AVD, following `v0.3.0-beta.md` | Works after two fixes, and the guide has gaps; see below. |
| Upgrade | Built an older 0.2.0 (0aa8c810, 2026-10-09), hid an app on the plain AVD, installed master's release build over it (same versionCode 2, so `install -r`). Layout and the hidden app survived, no welcome card again, no crash. Not covered: a real version bump, a config written by a build older than 10-09. |
| API-level crash checks | API 30 (twin) and API 31 (Android TV) done. API 28 and 34 have no AVD here (images exist for 34 and 35 only); waiting for the Manager's go-ahead to create them. |

Bugs found and fixed this round: full-disk crash (a3a7d0ca); first-run card showed Fire's adb command off Fire and its button read "Open A…" (7c9872dc); "Make Glass TV Launcher the Default" did nothing on Android TV because the Home role request was not started for a result (378554ab).

Gaps in `v0.3.0-beta.md` found by the clean install: it does not say that the first launch shows a Welcome card, then a "Turn on remote buttons?" card and a "Make Glass your Home" card (both can be cancelled); that Glass's own Settings are reached through the clock pill, Control Center, then Launcher Settings (the big Settings tile opens the system settings); and that after "Make the default" Android shows a system dialog to confirm. The beta also asks for no "app list" step that exists on every device. The emulator used `-gpu swiftshader_indirect` at first and was too slow to take key presses; use `-gpu host`.
