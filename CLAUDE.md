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

**1. On the Mac, no device (Robolectric + Roborazzi, `app/src/test/.../shots/`):**

- `scripts/shots verify` renders every Home screen (dock, featured row, grid, Control Center, app menu, folder, settings, light) at the stick's 960x540 dp and diffs against `app/src/test/screenshots/`. About 25 s warm. On failure, look at `app/build/outputs/roborazzi/*_compare.png`.
- `scripts/shots record` after an intended UI change, then review the new PNGs in the diff like code. `scripts/shots verify grid` runs one screen.
- `scripts/shots focus` crawls every focusable on Home with all four D-pad directions and fails on focus loss, unreachable apps, or Down from a featured card staying in the row. Read the whole graph in `app/build/focus-graph/home.md`; add named expectations to `FocusCrawlTest` when you fix a focus bug.
- `scripts/shots strips [name]` writes frame-exact strips of transitions (12 frames, 32 ms apart, clock paused) to `app/build/strips/`. One image shows a whole focus move: use it to judge motion, not just the end state. Add a case in `MotionStrips.kt`.
- The harness (`TvHarness`) installs 14 fake TV apps with generated banners, seeds the featured cache with fixture art, blocks the network, marks tips seen, and takes a `config` lambda for other states (folders, theme…). The clock and status pill are masked out of baselines. CI runs these tests but doesn't compare pixels, because fonts differ across OSes.
- Robolectric gotchas: Coil must use BitmapFactory (`imageDecoderEnabled(false)`, already done in the harness), and `createEmptyComposeRule` + `ActivityScenario` run the real `MainActivity`.

**2. On the device:**

- **Live view in the Claude browser pane:** preview `tv-live` (`scripts/tv-live`, http://localhost:8765). It shows the screen at about 30 fps, and arrows/Enter/Backspace/H/M on the page drive the remote. It records with the stick's encoder, so stop it before benchmarking. `scrcpy` gives a native window.
- `scripts/key down down right select`: real remote events via `sendevent` (adb is root), about 0.1 s per press vs 0.9 s for `input keyevent`. Also `hold:800:down` (auto-repeat), `wait:300`, and `settle`. `settle` waits until the app stops drawing and prints when the last frame landed (an animation-length measurement), or `busy` after 3 s of continuous drawing (video backdrop).
- `scripts/shot [out.png] [width]`: screenshot in about 1 s, downscaled to 960 wide. It fails with a clear message when a DRM app (Netflix…) is on screen.
- `scripts/clip <name> <keys…>`: records a transition and writes `build/clips/<name>.png`, 18 frames 33 ms apart starting at the first visible change, plus the mp4.
- `scripts/tv-type [--select] [--submit] "text"`: types any Unicode through ADBKeyBoard, which is installed on first use and draws no UI, so the D-pad keeps working. Restores Fire TV's keyboard afterwards. Use `--select` on a text row so editing starts after the IME switch.
- `scripts/dev-mode on|off`: keep the stick awake (stay-on + long timeouts), stop AT4K, wait for dexopt to finish; `off` restores the saved values.
- **The first key after a few idle minutes only wakes the UI** (chrome fade); it doesn't move focus. Send one throwaway press, or `scripts/tv trigger-app-event config '{"idleFadeMinutes":0}'` on a debug build (restore it to 3 after).
- **Debug-build hooks:** `app/src/debug/.../DebugEventActivity.kt` handles `glassdev://event?name=…&payload=…`. Events: `config` (merge JSON into `LauncherConfig`) and `home`. Use `scripts/tv trigger-app-event <name> '<json>'`. Release builds don't contain it.

**agent-device** (`scripts/tv`, also registered as an MCP server in `.mcp.json`; defaults live in `agent-device.json`):

- Batch steps: `scripts/tv batch --steps '[{"command":"tv-remote","input":{"action":"press","button":"down"}},{"command":"snapshot","input":{"interactiveOnly":true,"diff":true}}]'`. Add `--level digest` for cheaper output.
- Assert with **id selectors**: `scripts/tv is focused 'id="app:com.netflix.ninja"'`. Tags include `app:<pkg>`, `folder:<id>`, `featured:<id>`, `featured-row`, `status-pill`, `control-center`, `settings-tile`, `folder-title`, `text-value`, `text-input`, `dock`, `home`. `label=` selectors fail on text containing `…`.
- Snapshots take about 2.5 s and `wait stable` about 5 s, so prefer `scripts/key … settle` plus `scripts/shot` for quick loops. `find <text>` **taps** the match. `perf frames` needs `scripts/tv open dev.glasslauncher` first.
- Every agent-device version shares one daemon in `~/.agent-device`; two sessions on different versions keep replacing each other's daemon. Keep everyone on the pinned version, or set `AGENT_DEVICE_STATE_DIR` per session.

**3. Numbers:** `scripts/perf-run [--rounds N] [keys…]` (see below).

## Performance rules (learned on the GE9215, don't regress)

The GPU affords only about **two full-screen blended passes per frame**. Target: under 1% janky frames, about 0% idle CPU, under 120 MB PSS, release APK under 8 MB.

- Wallpaper bitmaps are opaque (`setHasAlpha(false)`), HARDWARE config, with the scrim baked in (`WallpaperLoader.load`). Never add full-screen translucent overlays.
- Glass (`glass/Glass.kt`) is a single fill per surface: a screen-mapped `BitmapShader` composed with the tint and highlight. No `clipPath`, no stacked translucent passes.
- No animated `shadowElevation`; tiles use the pre-blurred `TileShadow` bitmap.
- Heavy content that scrolls in and out (the top shelf) lives **outside** the `LazyColumn` and moves with the scroll in `graphicsLayer`. Fades use `CompositingStrategy.ModulateAlpha`.
- Animate only in layout/draw phases (`graphicsLayer {}`, `drawWithContent {}`); no per-frame recomposition.
- Idle: no infinite animations. The clock and idle timer wake at most once a minute.

- Wallpaper blur transitions step through `Backdrop.ladder` (opaque, progressively blurred copies); never cross-fade two full-screen images.
- Rendered tiles are HARDWARE bitmaps and Coil's memory cache is capped at 12%; software bitmaps cost both native heap and GPU memory.
- Blurred text shadows (`TextStyle.shadow`) are expensive when the text moves. Use them only on small static text (the clock).
- Background load skews numbers: `installd` runs after `compile -m speed`, and AT4K burns CPU if it's running. Wait for `installd` to go idle and keep AT4K stopped before benchmarking. Current baseline in a key-every-1.4 s stress run: median 7 ms, p90 about 11 ms, 4–9% janky (transitions only), about 0% idle CPU, about 84 MB PSS.

How to measure: `scripts/perf-run` compiles with `-m speed`, turns on dev-mode, resets gfxinfo, plays D-pad rounds at real remote pace (1.4 s apart), and prints one JSON line: frames, janky %, p50/p90/p95/p99, PSS and idle CPU (from `/proc/<pid>/stat`, since toybox `top` is unreliable). Run `scripts/dev-mode off` afterwards. For deeper dives: `adb shell perfetto … gfx view sched freq --app dev.glasslauncher`, analysed with the `perfetto` Python package in `tools/.venv` (`python3 -m venv tools/.venv && tools/.venv/bin/pip install perfetto`).

To bisect, temporarily add a `DebugFlags` object that reads `getprop debug.glass.flags` and gate suspects on its bits. Benchmark each bit with the script pattern in git history, then delete it.

## Code gotchas

- **No labelled returns out of inline composable lambdas** (`return@Column`, `return@key`, `return@LaunchedEffect` inside `runCatching{}.getOrElse{}`). They compile but R8 fails with `$$$$$NON_LOCAL_RETURN$$$$$`. Use if/else.
- Compose on TV defaults to a 30% "pivot" bring-into-view. Lists that shouldn't pivot use `MinimalScroll` (panels) or `NoAutoScroll` (home list, scrolled manually in `onRowFocused`).
- Overlays: only the top overlay traps focus (`Modifier.trapFocus(active)`); stacked ones refuse focus. Select and Menu handling only fires on a press that **started** on that element, so the key-up of the press that opened a menu is ignored.
- Fire TV's keyboard is full-screen and modal and opens whenever a `BasicTextField` gains focus. Text panels show the value in a row and only make the field focusable while editing (`TextInputBody`). The keyboard's action button submits; the phone form (`PhoneSetupServer`) is the preferred path.
- Apple's Aerial host (`sylvan.apple.com`) chains to Apple Root CA, which Android lacks. It's trusted for that host only in `res/xml/network_security_config.xml`.
- Config is one JSON blob in DataStore (`data/ConfigStore.kt`, `LauncherConfig`), encoded with defaults. **Changing a default doesn't affect existing installs.**

## Design source

Follow the `tvos27-guidelines` skill (and `~/Documents/firestick-unlocked/tvos27-inspo/`). Geometry lives in `ui/Metrics.kt`, measured from tvOS 27 frames: 5:3 tiles, 45dp inset, 24dp gutters, tray at 368dp, 36dp tray-to-grid gap. Home's backdrop is a scene: featured art (`WallpaperLoader.fromUrl`), the wallpaper, or Aerial video (`MotionBackground`). It's swapped through `BackdropState.swap`, which cross-fades for only 550 ms. No focus shimmer: the user rejected it as tacky.

## Layout

`apps/` (app list, tile art, icon packs) · `glass/` (blur, wallpaper, glass node) · `home/` (screen, model, overlays, menus) · `featured/` (top shelf + Stremio/TMDB/YouTube/Plex sources) · `dream/` (Aerial screensaver) · `widgets/` (clock, weather, now playing, idle/burn-in) · `system/` (home guard, updater, backup) · `settings/` · `ui/` (FocusTile, controls, theme).

## Licensing

Apache-2.0. Don't copy code from GPL projects (FLauncher, AerialViews) or from atvLauncher (no licence). Google's `android/tv-samples` is Apache-2.0 and safe to borrow from. Featured sources use the user's own API keys; keep the TMDB and JustWatch attribution text.
