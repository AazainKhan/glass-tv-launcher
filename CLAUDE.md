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

- **AT4K (`com.overdevs.at4k`) is the user's default home.** Open Glass Launcher with `adb shell am start -n dev.glasslauncher/.MainActivity`. Don't change the default home, or disable or enable system packages, without asking.
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

How to measure: `scripts/tv perf frames --json`, or `adb shell dumpsys gfxinfo dev.glasslauncher reset`, a key sequence, then `dumpsys gfxinfo`. Run `adb shell cmd package compile -m speed -f dev.glasslauncher` first so JIT warm-up doesn't skew results. For deeper dives: `adb shell perfetto … gfx view sched freq --app dev.glasslauncher`, analysed with the `perfetto` Python package.

`DebugFlags.kt` (`adb shell setprop debug.glass.flags <bits>`) is a **temporary** perf-bisect switch. Remove it before release.

## Code gotchas

- **No labelled returns out of inline composable lambdas** (`return@Column`, `return@key`, `return@LaunchedEffect` inside `runCatching{}.getOrElse{}`). They compile but R8 fails with `$$$$$NON_LOCAL_RETURN$$$$$`. Use if/else.
- Compose on TV defaults to a 30% "pivot" bring-into-view. Lists that shouldn't pivot use `MinimalScroll` (panels) or `NoAutoScroll` (home list, scrolled manually in `onRowFocused`).
- Overlays: only the top overlay traps focus (`Modifier.trapFocus(active)`); stacked ones refuse focus. Select and Menu handling only fires on a press that **started** on that element, so the key-up of the press that opened a menu is ignored.
- Config is one JSON blob in DataStore (`data/ConfigStore.kt`, `LauncherConfig`), encoded with defaults. **Changing a default doesn't affect existing installs.**

## Layout

`apps/` (app list, tile art, icon packs) · `glass/` (blur, wallpaper, glass node) · `home/` (screen, model, overlays, menus) · `featured/` (top shelf + Stremio/TMDB/YouTube/Plex sources) · `dream/` (Aerial screensaver) · `widgets/` (clock, weather, now playing, idle/burn-in) · `system/` (home guard, updater, backup) · `settings/` · `ui/` (FocusTile, controls, theme).

## Licensing

Apache-2.0. Don't copy code from GPL projects (FLauncher, AerialViews) or from atvLauncher (no licence). Google's `android/tv-samples` is Apache-2.0 and safe to borrow from. Featured sources use the user's own API keys; keep the TMDB and JustWatch attribution text.
