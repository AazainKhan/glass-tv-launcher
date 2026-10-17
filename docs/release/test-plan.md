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
