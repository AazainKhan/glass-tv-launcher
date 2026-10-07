# Glass Launcher

An open-source, Apple TV–style home screen for Android TV and Fire TV. It's designed to stay smooth on low-end streaming sticks.

> Status: early development (milestone 1 of 7: app grid). See the roadmap below.

## Goals

- Liquid-glass surfaces and fluid tvOS-style focus motion, including on Android 11 devices without live blur
- Clean 16:9 tiles for every app; apps without a TV banner get a generated tile
- Folders, a 6-app dock, custom icons and wallpapers, hidden apps
- A featured row where you pick the source: Netflix, Prime Video or Apple TV (via TMDB), Plex, YouTube, Stremio. You supply your own API keys.
- Aerial video screensaver as a real system screensaver, plus burn-in protection
- Near-zero CPU while idle, small APK

## Requirements

Android 9+ (API 28). Tested on Fire TV Stick 4K 2nd Gen (Fire OS 8).

## Build

Needs JDK 17 or 21 and the Android SDK.

```bash
scripts/build release   # build, install on the connected TV, launch
```

## Development tooling

UI testing and debugging on the TV use [agent-device](https://github.com/callstackincubator/agent-device), pinned in `package.json`:

```bash
scripts/tv snapshot -i                          # focusable elements with refs
scripts/tv tv-remote press right                # D-pad
scripts/tv is focused 'id="app:com.netflix.ninja"'
scripts/tv screenshot shot.png --overlay-refs
scripts/tv perf frames --json                   # frame health (after: scripts/tv open dev.glasslauncher)
```

Every app tile has the resource id `app:<package>` and its label as the content description.

## Roadmap

1. App grid, generated tiles, Home/Fire OS home intents ✅
2. Glass surfaces, focus motion, wallpapers, baseline profile
3. Dock, folders, reorder, hide apps, custom icons
4. Featured row with selectable sources
5. Aerial screensaver (DreamService), idle burn-in protection
6. Widgets, Home-button setup, in-app updater, backup/restore
7. CI and releases

## License

Apache-2.0
