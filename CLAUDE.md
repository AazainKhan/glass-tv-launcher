# Glass Launcher

Open-source (Apache-2.0) Apple TV / tvOS-style launcher for Android TV and Fire TV. Kotlin + Jetpack Compose for TV, single `:app` module, package `dev.glasslauncher`. The roadmap and goals are in `README.md`.

## Build and run

- **Needs JDK 17 or 21.** The system default is JDK 25, which AGP rejects. `scripts/build` picks 21 automatically. For raw Gradle: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
- `scripts/build [release|debug]`: builds, installs on the connected TV, and launches. Default is release (R8 on, signed with the debug key for now).
- Toolchain: Gradle 9.8 wrapper, AGP 9.4.1 with **built-in Kotlin** (don't apply `org.jetbrains.kotlin.android` in `app/`), Kotlin 2.4.20, compileSdk 37, minSdk 28, targetSdk 36. Versions live in `gradle/libs.versions.toml`.
- If a Gradle command hangs with no output, a daemon is stuck: `./gradlew --stop`, then rerun.
- macOS has no `timeout`; use `gtimeout` (Homebrew coreutils, installed). Never wrap a command in a missing binary: it silently does nothing.
- `installRelease` sometimes doesn't replace the APK. If behaviour looks stale, check `adb shell dumpsys package dev.glasslauncher | grep lastUpdateTime`, then `adb install -r app/build/outputs/apk/release/app-release.apk`.

## Test device

Fire TV Stick 4K 2nd Gen (AFTKRT, Fire OS 8 = API 30, armeabi-v7a, Imagination GE9215 GPU, 2 GB). Rooted with Magisk. Connected over adb.

- **Glass Launcher is the default home** (set with `cmd package set-home-activity`; the Magisk boot script `/data/adb/service.d/start-launcher.sh` starts it). AT4K (`com.overdevs.at4k`) stays installed as the fallback. To revert: `adb shell cmd package set-home-activity com.overdevs.at4k/.MainActivity` and edit the boot script back.
- The user has given broad permission for device changes. Still restore anything you change only for a test (e.g. screensaver settings).
- Fire OS ignores third-party DreamServices, even with Ambient Experience disabled; `Somnambulator` won't start one. Use the in-app "Start Aerials on Home after" (set to 3 min on this stick).
- `WRITE_SECURE_SETTINGS` and the Now Playing notification listener are granted to the app on this device.
- The stick sleeps after about 5 minutes; a black screenshot usually means it's asleep. Wake it with `adb shell input keyevent KEYCODE_WAKEUP`.
- To simulate a Home press to the launcher: `adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n dev.glasslauncher/.MainActivity`.

## Dev loop: see it, check it, measure it

Work cheapest-first. Most UI work never needs the device.

**0. Start of a session:** the SessionStart hook lists open **reports** (the user's "Report" button in the live view, and crashes/ANRs from `scripts/watch-crashes`): `scripts/reports`, `scripts/reports show <id>`, and when fixed `scripts/reports done <id> "what changed"`. Treat them as the user's bug queue. Before sending input to the stick, `scripts/stick-lock take "<you>"` and `export TV_OWNER="<you>"`; other sessions may be using it. Release it when done. Work in a git worktree when another session is active in this checkout.

**Guard rails (automatic):** a Stop hook runs `scripts/shots all` when UI code changed and blocks once if it fails. The git pre-commit hook (`core.hooksPath = scripts/git-hooks`) does the same for commits touching UI or baselines. If the change is intended, run `scripts/shots record` (and `previews record`) and commit the new baselines with the code.

**Build loop:** after a one-line change, a debug build is about 3 s plus 4 s to install; release (R8) is about 80 s. Use `scripts/build debug` for anything visual on the device and release only for performance numbers.

**1. On the Mac, no device (Robolectric + Roborazzi, `app/src/test/.../shots/`):**

- `scripts/shots verify` renders every Home screen (dock, featured row, grid, Control Center, app menu, folder, settings, light) at the stick's 960x540 dp and diffs against `app/src/test/screenshots/`. About 25 s warm. On failure, look at `app/build/outputs/roborazzi/*_compare.png`.
- `scripts/shots record` after an intended UI change, then review the new PNGs in the diff like code. `scripts/shots verify grid` runs one screen.
- `scripts/shots focus` crawls every focusable on Home with all four D-pad directions and fails on focus loss, unreachable apps, or Down from a featured card staying in the row. Read the whole graph in `app/build/focus-graph/home.md`; add named expectations to `FocusCrawlTest` when you fix a focus bug.
- `scripts/shots previews [record]`: component `@Preview`s in `app/src/debug/.../preview/ComponentPreviews.kt` (tiles, tray, menu, light/dark) on the real wallpaper, as screenshot tests (a couple of seconds each). Iterate on a single component here; add a preview for whatever you're polishing.
- `scripts/compare-ref [screen] [keyword]`: our render next to the matching tvOS 27 frame from `tvos27-inspo/screenshots` (default mapping per screen), written to `build/compare/`. Use it to check spacing, glass and type against the source of truth. `--list <keyword>` finds frames.
- `scripts/shots strips [name]` writes frame-exact strips of transitions (12 frames, 32 ms apart, clock paused) to `app/build/strips/`. One image shows a whole focus move: use it to judge motion, not just the end state. Add a case in `MotionStrips.kt`.
- The harness (`TvHarness`) installs 14 fake TV apps with generated banners, seeds the featured cache with fixture art, blocks the network, marks tips seen, and takes a `config` lambda for other states (folders, theme…). The clock and status pill are masked out of baselines. CI runs these tests but doesn't compare pixels, because fonts differ across OSes.
- Robolectric gotchas: Coil must use BitmapFactory (`imageDecoderEnabled(false)`, already done in the harness), and `createEmptyComposeRule` + `ActivityScenario` run the real `MainActivity`.

**2. On the device:**

- **Gallery** (`/gallery` in tv-live): everything being built, refreshing itself. Tabs: screen renders and failing diffs, component previews, motion strips and device clips, the focus graph, reference comparisons, and reports. Point the user here instead of pasting images.
- **Live view in the Claude browser pane:** preview `tv-live` (`scripts/tv-live`, http://localhost:8765). It shows the screen at native 1080p (20 Mbit/s), with a "Lossless still" PNG and an unscaled "1:1" mode for judging edges and blending, and arrows/Enter/Backspace/H/M on the page drive the remote. It records with the stick's encoder, so stop it before benchmarking. `scrcpy` gives a native window. Its **Report** box saves a lossless still, the last 6 s as video, the focused element and the note to `reports/inbox/`.
- `scripts/key down down right select`: real remote events via `sendevent` (adb is root), about 0.1 s per press vs 0.9 s for `input keyevent`. Also `hold:800:down` (auto-repeat), `wait:300`, and `settle`. `settle` waits until the app stops drawing and prints when the last frame landed (an animation-length measurement), or `busy` after 3 s of continuous drawing (video backdrop).
- `scripts/shot [out.png] [width]`: screenshot in about 1 s, downscaled to 960 wide. It fails with a clear message when a DRM app (Netflix…) is on screen.
- `scripts/clip <name> <keys…>`: records a transition and writes `build/clips/<name>.png`, 18 frames 33 ms apart starting at the first visible change, plus the mp4.
- `scripts/tv-type [--select] [--submit] "text"`: types any Unicode through ADBKeyBoard, which is installed on first use and draws no UI, so the D-pad keeps working. Restores Fire TV's keyboard afterwards. Use `--select` on a text row so editing starts after the IME switch.
- `scripts/dev-mode on|off`: keep the stick awake (stay-on + long timeouts), stop AT4K, wait for dexopt to finish; `off` restores the saved values.
- **Idle chrome fade** doesn't swallow keys (`Idle.touch()` only records the time); tested 2026-10-07: Down after a 1-minute fade in full screen moved focus. If a key ever seems lost after idle, it's Fire OS (screen dim/wake), not Glass.
- **Debug-build hooks:** `app/src/debug/.../DebugEventActivity.kt` handles `glassdev://event?name=…&payload=…`. Events: `config` (merge JSON into `LauncherConfig`) and `home`. Use `scripts/tv trigger-app-event <name> '<json>'`. Release builds don't contain it.

- **Emulator for parallel work:** `scripts/emulator start` boots an Android TV (API 31) emulator on its own adb server, invisible to everything using the stick; `eval "$(scripts/emulator env)"` points `scripts/key`, `shot`, `tv` etc. at it, and `scripts/emulator install debug` puts the current build on it. Use it for functional and focus checks while someone else has the stick. It doesn't match the stick's GPU, Fire OS or speed, so judge looks and performance on the stick only.

**agent-device** (`scripts/tv`, also registered as an MCP server in `.mcp.json`; defaults live in `agent-device.json`):

- Batch steps: `scripts/tv batch --steps '[{"command":"tv-remote","input":{"action":"press","button":"down"}},{"command":"snapshot","input":{"interactiveOnly":true,"diff":true}}]'`. Add `--level digest` for cheaper output.
- Assert with **id selectors**: `scripts/tv is focused 'id="app:com.netflix.ninja"'`. Tags include `app:<pkg>`, `folder:<id>`, `featured:<id>`, `featured-row`, `status-pill`, `control-center`, `settings-tile`, `folder-title`, `text-value`, `text-input`, `dock`, `home`. `label=` selectors fail on text containing `…`.
- Snapshots take about 2.5 s and `wait stable` about 5 s, so prefer `scripts/key … settle` plus `scripts/shot` for quick loops. `find <text>` **taps** the match. `perf frames` needs `scripts/tv open dev.glasslauncher` first.
- Every agent-device version shares one daemon in `~/.agent-device`; two sessions on different versions keep replacing each other's daemon. Keep everyone on the pinned version, or set `AGENT_DEVICE_STATE_DIR` per session.

**3. Numbers:** `scripts/perf-run [--rounds N] [keys…]` (see below). `scripts/perf-gate` builds release, runs it, and fails against `perf-budget.json` (p90, p99, janky %, PSS, idle CPU): run it before merging rendering changes. `scripts/bench` runs the Macrobenchmarks (cold startup and D-pad browsing, with and without the Baseline Profile) and `scripts/bench profile` regenerates the profile; both need Magisk root for the adb Shell user.

## Remote buttons (Fire TV)

- Fire OS consumes the app buttons (`KeyMapManager` in system_server launches Netflix/Prime/…), Recent Apps, and Settings (a global key sent to `com.amazon.tv.settings.v2/.GlobalKeyHandler`) before any app or accessibility service sees them. Home long-press goes to `com.amazon.tv.quicksettings`. Leave Home and the quick menu alone (user's call).
- `scripts/remote-keys install` (Magisk module `tools/magisk/glass-remote-keys`, needs a restart) remaps the app buttons and Recent Apps to BUTTON_9..13, which reach `RemoteKeysService`; actions live in `system/RemoteButtons.kt` and Settings › Remote Buttons. `uninstall` restores Fire's behaviour. Settings can't be freed by a key layout: `FireTVKeyPolicyManager` (in `interceptKeyBeforeQueueing`) catches it by scan code 249 whatever keycode it maps to, and it also swallows BUTTON_16. So the module's `service.sh` rewrites the remote's kernel keymap (`tools/keymap/glass-keymap`, built with `zig cc -target arm-linux-musleabi -static`) from 249 to 185, a code the layout doesn't name: it arrives as KEYCODE_UNKNOWN with scan code 185, and its key-up never reaches accessibility services, so `RemoteKeysService` acts on key-down. The remote gets a fresh keymap on every reconnect; `service.sh` re-applies via Magisk busybox `inotifyd` (toybox's rejects the `:n` mask).
- Fire TV's Settings app has no main screen (the stock launcher draws it; `ACTION_SETTINGS` hits `CTSDummySettingsHandler`). Glass's `Overlay.TvSettings` lists the sections and opens `com.amazon.tv.settings.v2/.tv.*Activity` directly.
- androidx.benchmark 1.5.0 checks root with `su root id`, which Magisk's su answers with nothing (and hangs under the test runner), so `scripts/bench` / `bench profile` hang at startup on the stick. Use `scripts/profile-capture` instead: it resets the profile, drives the Home journey with real key presses, flushes with SIGUSR1 and dumps with `profman --dump-classes-and-methods` as root into `app/src/release/generated/baselineProfiles/baseline-prof.txt`. Re-run it after big UI changes.
- `RemoteKeysService` must set `FLAG_REQUEST_FILTER_KEY_EVENTS` at runtime in `onServiceConnected`; the XML flag alone didn't deliver keys on Fire OS 8. Keys injected with `input keyevent` never reach accessibility filters; use `scripts/key` (sendevent), which now also knows `app1-app4 recents settings alexa tv mute`.

## Performance rules (learned on the GE9215, don't regress)

The GPU affords only about **two full-screen blended passes per frame**. Target: under 1% janky frames, about 0% idle CPU, under 120 MB PSS, release APK under 8 MB.

- Wallpaper bitmaps are opaque (`setHasAlpha(false)`), HARDWARE config, with the scrim baked in (`WallpaperLoader.load`). Never add full-screen translucent overlays.
- Glass (`glass/Glass.kt`) is a single fill per surface: a screen-mapped `BitmapShader` composed with the tint and highlight. No `clipPath`, no stacked translucent passes. The dock tray is *clear* glass (`GlassStyle.clear`): it samples the lightly blurred `clearSoftware` over the hero (the frosted `blurredSoftware` once the grid is up) and adds one masked stroke, the refracted edge band, which fades into the body so there's no seam. Panels and folders stay frosted. Each extra shader-filled stroke along the tray cost ~7 ms/frame (measured: 4 rings took p50 from 12 to 28 ms), so keep it to one.
- No animated `shadowElevation`; tiles use the pre-blurred `TileShadow` bitmap.
- Heavy content that scrolls in and out (the top shelf) lives **outside** the `LazyColumn` and moves with the scroll in `graphicsLayer`. Fades use `CompositingStrategy.ModulateAlpha`.
- Animate only in layout/draw phases (`graphicsLayer {}`, `drawWithContent {}`); no per-frame recomposition.
- Idle: no infinite animations. The clock and idle timer wake at most once a minute.

- Wallpaper blur transitions step through `Backdrop.ladder` (opaque, progressively blurred copies); never cross-fade two full-screen images.
- Hero backdrops are baked at 1920x1080 (shown 1:1); every blurred copy comes from one 960x540 intermediate. 1080p vs 720p made no measurable frame difference. Blurs use RenderScript's ScriptIntrinsicBlur (deprecated, still on Fire OS 8; ~50x the Kotlin box blur, which stays as the JVM/test fallback): a bake is ~245 ms CPU, was ~640. Don't put a colour filter on bitmap draws that don't need one (forces Skia's slow path).
- Glass is the GPU hot spot. Over a blurred backdrop (`wallpaperBlur > 0`, i.e. scrolling to or resting on the grid) it draws a flat tint and rim, no texture or gradient shaders: dock-to-grid went from 25% janky frames to ~1%. Measure with the recorder off: `scripts/perf-run --rounds 5 down up` while killing `screenrecord` (tv-live respawns it).
- The slideshow pauses while the grid is focused and waits for 3 quiet seconds, so a slide's bake and cross-fade never land on a scroll.
- Rendered tiles are HARDWARE bitmaps and Coil's memory cache is capped at 12%; software bitmaps cost both native heap and GPU memory.
- Blurred text shadows (`TextStyle.shadow`) are expensive when the text moves. Use them only on small static text (the clock).
- Background load skews numbers: `installd` runs after `compile -m speed`, and AT4K burns CPU if it's running. Wait for `installd` to go idle and keep AT4K stopped before benchmarking. Current baseline in a key-every-1.4 s stress run: median 7 ms, p90 about 11 ms, 4–9% janky (transitions only), about 0% idle CPU, about 84 MB PSS.

How to measure: `scripts/perf-run` compiles with `-m speed`, turns on dev-mode, resets gfxinfo, plays D-pad rounds at real remote pace (1.4 s apart), and prints one JSON line: frames, janky %, p50/p90/p95/p99, PSS and idle CPU (from `/proc/<pid>/stat`, since toybox `top` is unreliable). Run `scripts/dev-mode off` afterwards. Check `adb shell ps -A | grep screenrecord` first: a live view open in any chat records with the stick's encoder and roughly doubles p90. If you can't stop it, A/B against the previous build under the same load. For deeper dives: `adb shell perfetto … gfx view sched freq --app dev.glasslauncher`, analysed with the `perfetto` Python package in `tools/.venv` (`python3 -m venv tools/.venv && tools/.venv/bin/pip install perfetto`).

To bisect, temporarily add a `DebugFlags` object that reads `getprop debug.glass.flags` and gate suspects on its bits. Benchmark each bit with the script pattern in git history, then delete it.

## Code gotchas

- **No labelled returns out of inline composable lambdas** (`return@Column`, `return@key`, `return@LaunchedEffect` inside `runCatching{}.getOrElse{}`). They compile but R8 fails with `$$$$$NON_LOCAL_RETURN$$$$$`. Use if/else.
- Compose on TV defaults to a 30% "pivot" bring-into-view. Lists that shouldn't pivot use `MinimalScroll` (panels) or `NoAutoScroll` (home list, scrolled manually in `onRowFocused`).
- Overlays: only the top overlay traps focus (`Modifier.trapFocus(active)`); stacked ones refuse focus. Select and Menu handling only fires on a press that **started** on that element, so the key-up of the press that opened a menu is ignored.
- Fire TV's keyboard is full-screen and modal and opens whenever a `BasicTextField` gains focus. Text panels show the value in a row and only make the field focusable while editing (`TextInputBody`). The keyboard's action button submits; the phone form (`PhoneSetupServer`) is the preferred path.
- Apple's Aerial host (`sylvan.apple.com`) chains to Apple Root CA, which Android lacks. It's trusted for that host only in `res/xml/network_security_config.xml`.
- Config is one JSON blob in DataStore (`data/ConfigStore.kt`, `LauncherConfig`), encoded with defaults. **Changing a default doesn't affect existing installs.**

## Design source

Follow the `tvos27-guidelines` skill (and `~/Documents/firestick-unlocked/tvos27-inspo/`). Geometry lives in `ui/Metrics.kt`, measured from tvOS 27 frames: 5:3 tiles, 45dp inset, 24dp gutters, tray at 384dp, 36dp tray-to-grid gap. Home's backdrop is a scene: featured art (`WallpaperLoader.fromUrl`), the wallpaper, or Aerial video (`MotionBackground`). It's swapped through `BackdropState.swap`, which cross-fades for only 550 ms. No focus shimmer: the user rejected it as tacky. Motion curves live in `ui/Motion.kt`, fitted to the measured tvOS 27 timings (skill `references/motion-spec.md`); the stick's animation scales are 1.0x so they play at real speed.

## Layout

`apps/` (app list, tile art, icon packs) · `glass/` (blur, wallpaper, glass node) · `home/` (screen, model, overlays, menus) · `featured/` (top shelf + Stremio/TMDB/YouTube/Plex sources) · `dream/` (Aerial screensaver) · `widgets/` (clock, weather, now playing, idle/burn-in) · `system/` (home guard, updater, backup) · `settings/` · `ui/` (FocusTile, controls, theme).

## Licensing

Apache-2.0. Don't copy code from GPL projects (FLauncher, AerialViews) or from atvLauncher (no licence). Google's `android/tv-samples` is Apache-2.0 and safe to borrow from. Featured sources use the user's own API keys; keep the TMDB and JustWatch attribution text.
