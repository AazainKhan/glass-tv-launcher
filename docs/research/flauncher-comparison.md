# FLauncher vs Glass: engineering ideas (2026-10-09)

FLauncher is GPL-3.0. Techniques below are described in our own words; nothing was copied. Any idea that would mean reusing its structure closely needs a licence check first (none of the three picks does). FLauncher paths are under `flauncher-master/`.

| Area | FLauncher | Glass today | Adopt? | Win on stick | Effort |
|---|---|---|---|---|---|
| App refresh | `LauncherApps` callback sends per-package deltas (`android/.../MainActivity.kt:68-91`); full rescan only at start (`lib/providers/apps_service.dart:119-144`) | Same callback, but any event triggers a full re-query (`app/.../apps/AppRepository.kt:30-34, 59-70`) | no | tiny (query is off-thread, conflated) | S |
| Art freshness | Re-reads banner on PACKAGE_CHANGED (`apps_service.dart:56-58`) | `TileSpec` has no version or update time (`apps/TileArt.kt:27`); an updated banner stays stale until process death | **yes** | correctness | S |
| Icon loading | PNG-encodes every icon and banner at full size, on the platform thread (`MainActivity.kt:144-151, 210-227`), stores blobs in SQLite | Renders 340x204 tiles off-thread, HARDWARE copy, 16 MB LRU (`TileArt.kt:41-43, 79-88`); preloads all apps (`home/HomeModel.kt:58-66`) | maybe: disk cache of rendered tiles | faster cold start and after kills | M |
| Cold start | Awaits Firebase and remote config before `runApp` (`lib/main.dart:30-60`) | Defers baking to background (`GlassApp.kt:39-50`); one blocking config read (`data/ConfigStore.kt:25-29`) | no | Glass is already ahead | - |
| Persistence | Drift tables; whole join re-read, blobs included, after every edit (`database.dart`, `apps_service.dart:158-272`) | One DataStore JSON, pretty-printed, rewritten per edit (`ConfigStore.kt:31-35, 49`) | no (SQLite) | none: layout is tiny | - |
| Reorder writes | Reorders in memory per press, saves once at move end (`apps_service.dart:194-199`, `lib/widgets/app_card.dart:240-243`) | Every press does edit, DataStore write, flow, recompose (`HomeModel.kt:130-150, 210`) | maybe | smoother move mode | M |
| Focus, scroll, key repeat | Geometric search over all nodes (`lib/custom_traversal_policy.dart:38-77`); 100 ms ease per focus (`ensure_visible.dart:30-38`) | Focus groups plus `stopAtRowEnds` (`home/HomeScreen.kt:918-924`); tuned row scroll (`:404-424`) | no | none | - |
| Wallpaper | Raw bytes kept in memory, decoded by the engine (`lib/providers/wallpaper_service.dart:52-59`) | Opaque HARDWARE bakes (`glass/WallpaperLoader.kt:173-190`) | no | Glass is better | - |

## P39 (glide jank): finding

P39 is closed as noise (board: same commit varies up to 2.8 points run to run). Glide still has a real, separate cost:
- `LocalGlide` is always provided (`HomeScreen.kt:965-967`), and `glide()` only skips when the tracker is null (`home/Glide.kt:40`), so it never skips.
- Every composed cell runs `onGloballyPositioned`, `positionInRoot` and a HashMap write with boxed values (`Glide.kt:44-48`), and keeps an `Animatable` and coroutine scope.
- Each app cell also reads `boundsInWindow` on every placement (`HomeScreen.kt:1137, 1163`), though only click and menu use it.
- Not measured (no device use allowed); counting callbacks per scroll frame would confirm.

## Top 3

1. **Gate glide, make tile bounds lazy (S).** Provide the tracker only while `moving != null` (and `rearranging` in `Menus.kt:414-509`). Compute bounds on click or menu only. Files: `HomeScreen.kt`, `Glide.kt`, `Menus.kt`. Measure: median of at least 3 `scripts/perf-gate` runs (its route `down right left` scrolls the grid, `scripts/perf-gate:22`) comparing janky % and p90. Single runs cannot see a sub-point change. Acceptance: move-mode strips and continuity checks unchanged. `perf-gate` never enters move mode, so use a custom `scripts/perf-run` key route for that.
2. **Version-aware tile key (S).** Add the app's last-update time to `TileSpec` (or invalidate on package change). Touches `TileArt.kt`, `AppRepository.kt`, `HomeModel.kt`. Measure: JVM test that a changed key re-renders. Needed before any disk cache.
3. **Disk cache of rendered tiles (M), preload in on-screen order.** Skips decoding xxxhdpi banners and pixel scans after every process kill (`TileArt.kt:96-109, 188-251`). Also dedupe in-flight loads (the preloader and visible tiles can render the same spec, `TileArt.kt:79-88`). Verify the launchable count: the preload thrashes the 16 MB cache only above about 60 apps. Measure: `am start -W` and fully-drawn logcat after a force-stop, plus PSS via `scripts/perf-run`. (`scripts/bench` hangs on this stick.)
   Runner-up: in-memory move-mode reorder with one save at the end.

## Not worth it
- Drift/SQLite: schema v5 migrations and full-join rereads cost more than they give.
- PNG blobs in a database; whole-app-list payloads over a channel.
- Remote config or analytics before first frame.
- Animated elevation and the infinite pulsing border (`app_card.dart:109-187`): break the GPU rules.
- Custom traversal policy and `EnsureVisible`: Glass's focus and `FocusCrawlTest` already cover more.
- Keeping raw wallpaper bytes in memory.
