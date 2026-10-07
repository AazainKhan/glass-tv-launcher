# Glass Launcher

Open-source (Apache-2.0) Apple TV / tvOS-style launcher for Android TV and Fire TV. Kotlin + Jetpack Compose for TV, single `:app` module, package `dev.glasslauncher`. The roadmap and goals are in `README.md`.

## Build and run

- **Needs JDK 17 or 21.** The system default is JDK 25, which AGP rejects. `scripts/build` picks 21 automatically. For raw Gradle: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
- `scripts/build [release|debug]`: builds, installs on the connected TV, and launches. Default is release (R8 on, signed with the debug key for now).
- Toolchain: Gradle 9.8 wrapper, AGP 9.4.1 with **built-in Kotlin** (don't apply `org.jetbrains.kotlin.android` in `app/`), Kotlin 2.4.20, compileSdk 37, minSdk 28, targetSdk 36. Versions live in `gradle/libs.versions.toml`.
- If a Gradle command hangs with no output, a daemon is stuck: `./gradlew --stop`, then rerun.
- macOS has no `timeout`/`gtimeout`. Wrapping a command in it silently does nothing (the build never runs).
- `installRelease` sometimes doesn't replace the APK. If behaviour looks stale, check `adb shell dumpsys package dev.glasslauncher | grep lastUpdateTime`, then `adb install -r app/build/outputs/apk/release/app-release.apk`.

## Test device

Fire TV Stick 4K 2nd Gen (AFTKRT, Fire OS 8 = API 30, armeabi-v7a, Imagination GE9215 GPU, 2 GB). Rooted with Magisk. Connected over adb.

- **Glass Launcher is the default home** (set with `cmd package set-home-activity`; the Magisk boot script `/data/adb/service.d/start-launcher.sh` starts it). AT4K (`com.overdevs.at4k`) stays installed as the fallback. To revert: `adb shell cmd package set-home-activity com.overdevs.at4k/.MainActivity` and edit the boot script back.
- The user has given broad permission for device changes. Still restore anything you change only for a test (e.g. screensaver settings).
- Fire OS ignores third-party DreamServices, even with Ambient Experience disabled; `Somnambulator` won't start one. Use the in-app "Start Aerials on Home after" (set to 3 min on this stick).
- `WRITE_SECURE_SETTINGS` and the Now Playing notification listener are granted to the app on this device.
- The stick sleeps after about 5 minutes; a black screenshot usually means it's asleep. Wake it with `adb shell input keyevent KEYCODE_WAKEUP`.
- To simulate a Home press to the launcher: `adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n dev.glasslauncher/.MainActivity`.

## Driving the TV (agent-device)

`scripts/tv <cmd>` wraps the pinned agent-device (`package.json`) with `--platform android --target tv --serial … --session tv`.

- `scripts/tv snapshot -i`, `scripts/tv tv-remote press up|down|left|right|select|back|home`, `scripts/tv tv-remote longpress select`, `scripts/tv screenshot out.png --overlay-refs`.
- Assert with **id selectors**: `scripts/tv is focused 'id="app:com.netflix.ninja"'`. Stable tags: `app:<pkg>`, `folder:<id>`, `featured:<id>`, `settings-button`, `folder-title`, `text-input`, `top-shelf`, `dock`, `home`. `label=` selectors fail on text containing `…`.
- `tv-remote` doesn't accept `--settle`. Run `snapshot -i` after the press.
- `find <text>` **taps** the match. Don't use it just to look something up.
- `perf frames`/`perf memory sample` need `scripts/tv open dev.glasslauncher` first so the session knows the app.
- Fire TV keyboard: the full-screen IME captures the D-pad. Use `adb shell input text "a%sb"` (`%s` is a space) and `KEYCODE_DEL`. The text field uses `showKeyboardOnFocus = false` so Back can close the keyboard.

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

How to measure: `scripts/tv perf frames --json`, or `adb shell dumpsys gfxinfo dev.glasslauncher reset`, a key sequence, then `dumpsys gfxinfo`. Run `adb shell cmd package compile -m speed -f dev.glasslauncher` first so JIT warm-up doesn't skew results. For deeper dives: `adb shell perfetto … gfx view sched freq --app dev.glasslauncher`, analysed with the `perfetto` Python package.

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
