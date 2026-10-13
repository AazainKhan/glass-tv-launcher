# Fire twin (GlassFire_API30)

A local emulator that approximates the Fire TV Stick 4K 2nd Gen: Android 11 / API 30, AOSP (no Google apps, like Fire),
arm64, 1920x1080 @ 320 dpi, 60 Hz, heap growth limit 192m (same as the stick). Fire OS itself is 32-bit-only and cannot boot
on Apple silicon (`rom/research/fireos-emulator-spike.md`), so this is a look-alike, never the real thing.
Differences the emulator forces: RAM is floored at 2 GB (the stick has ~1.7 GB), and `dalvik.vm.heapsize` (the largest heap a
`largeHeap` app can get) shows 512m where the stick has 384m. The per-app heap limit that normal apps hit,
`dalvik.vm.heapgrowthlimit`, is 192m on both.
There is no android-tv arm64 image for API 30, so it has no TV system apps or leanback feature; Glass is the home anyway.

```
scripts/emulator create-twin              # idempotent: downloads the image (sdkmanager) and creates the AVD
scripts/emulator start --twin             # console 5610 / adb 5611 on the private adb server (5038); coexists with GlassTV_API31
eval "$(scripts/emulator env --twin)"     # then scripts/key, shot, tv act on it
scripts/emulator deploy-stick [debug|release]          # build once (no adb, no privapp-check), install on the twin
scripts/emulator mirror                   # stick -> twin: user apps and Glass prefs (see below)
scripts/emulator deploy-stick --stick     # the real stick: needs GLASS_DEVICE=1 and the stick lock, runs scripts/build --device
scripts/emulator-test-shim                # tests mirror/deploy-stick against a fake stick (adb shim); needs the twin running
```

Prerequisite for `mirror` and `deploy-stick --stick`: `export STICK_SERIAL=<the stick's adb serial>` (not stored in the repo).
`mirror` reads the stick only (an allowlist in `stick_adb` refuses any other command): `GLASS_STICK=1`, the stick lock held by you (`scripts/stick-lock take`, `TV_OWNER`), read-only
commands (`pm list packages -3`, `pm path`, `pull`, a `tar` of Glass's `shared_prefs` and `files`). It installs APKs
(`install-multiple` for splits), skips 32-bit-only apps (no `lib/arm64-v8a`) and failures, and writes `build/mirror/summary.txt`;
finished apps are recorded in `installed.txt` (keyed by package and versionCode), so a rerun resumes. The pull directory is private (umask 077); `MIRROR_DIR` may not point inside the repo outside `build/`. Glass's data is restored with `run-as`, which works only for
a debug build of Glass on the twin (install it first); otherwise the summary says "pulled, NOT restored". Pulling it from the
stick uses `run-as` (debug build there) or `su` (Magisk).

| Area | Twin | Stick only |
|---|---|---|
| Layout, focus navigation, D-pad logic, app grid/tray, Control Center logic, config/prefs flows | yes | |
| Compose UI tests, install/launch smoke, HOME intent, API 30 behaviour (permissions, background limits) | yes | |
| Third-party app compatibility (arm64 builds only) | partly (`mirror`) | 32-bit ABI apps and native libs (`armeabi-v7a`) |
| GPU, jank, frame timing, animation feel | no: host GPU, never quote numbers | Mali GPU; `scripts/bench`, `perf-gate`, `clip` |
| PSS, memory pressure, lmkd, zram tuning | no: 2 GB, different lmkd | `scripts/tuning`, `vitals` |
| Bluetooth remote, pairing, keepalive | no | yes |
| HDMI-CEC, HDR, display modes, audio passthrough | no | yes |
| Magisk modules, privapp allowlist, Glass as system app | no | `scripts/privapp-check`, `build --device` |
| Amazon services and intents (Alexa, aria, Amazon launcher, PhairPlay) | no | yes |
| Remote-key filtering (accessibility key events from the remapped remote) | no | yes |
| Performance numbers of any kind | never | yes |

Remote keys on the twin: `scripts/key` falls back to `input keyevent` there and sends DPAD_*, BACK, HOME, MENU,
MEDIA_PLAY_PAUSE/REWIND/FAST_FORWARD, VOLUME_MUTE. The app buttons, Recents, Settings (185) and Alexa reach Glass on the stick
only as `glass-remote-keys` remaps of the physical remote's key layout (APP_1..4 -> BUTTON_9..12, Recents -> BUTTON_13, scan
codes 744-748, 249->185); an emulator has no such device, so `scripts/key app1` cannot reproduce them and Alexa cannot be
injected at all. Test Glass's handling of those on the stick.
