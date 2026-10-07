# Glass Launcher

An open-source, Apple TV–style home screen for Android TV, Google TV and Fire TV. It has liquid-glass surfaces, tvOS-like focus motion, folders, a featured "top shelf" from the services you use, and Apple's Aerial screensaver videos. It's built to stay smooth on a Fire TV Stick.

## Features

- **Liquid glass that's fast on cheap sticks.** Frosted panels, dock and folders without Android 12's blur APIs: one pre-blurred backdrop is mapped under every glass surface in a single GPU pass. Measured on a Fire TV Stick 4K (2nd gen): under 1% janky frames, roughly 0% CPU when idle.
- **tvOS-style focus.** Spring lift and scale, a tilt in from the direction you pressed, a specular sweep, soft shadows, labels under the focused app. Navigation sounds follow the system setting.
- **Clean tiles for every app.** TV banners are used when an app has one. Phone-only apps get a generated full-bleed tile from their icon, so nothing looks like a stray square.
- **Top Row (dock) of up to 6 apps** on a glass shelf, plus folders with a blurred backdrop and rename. Rearrange anything (Menu or hold Select → Move), hide apps, and pick custom icons (image file, web image, or any ADW/Nova icon pack).
- **Featured shelf from the source you choose:**
  - Stremio (Cinemeta, no key needed)
  - Netflix, Prime Video, Apple TV+, Disney+, Max or Hulu via TMDB (your free key)
  - YouTube trending (your key)
  - Plex On Deck (sign in with plex.tv/link)

  Selecting an item opens it in the matching app.
- **Set up from your phone.** Scan a QR code and type API keys, URLs or names on your phone instead of with the remote. The form is served only on your local network, on a one-time link, while the panel is open.
- **Aerial screensaver.** Apple's tvOS Aerial videos (1080p or 4K HEVC), cached for offline replay, with location and time. Works as a system screensaver on Android TV and Google TV. Fire OS only allows Amazon's screensavers, so there's also "Start Aerials on Home after N minutes".
- **Burn-in care.** The clock, status bar and featured shelf fade when idle, and the screen drifts a few pixels each minute.
- **Wallpapers.** Built-in gradients, any image on the device, or a web image, with separate light and dark wallpapers.
- **Widgets.** Clock, weather (Open-Meteo, no key), and Now Playing from any media app.
- **Accessibility.** Reduce Motion and Reduce Transparency (following the system's animation and high-contrast settings by default), 10-foot type sizes, labelled controls.
- **Home button.** Registers as Home for Android TV, Google TV and Fire OS, with an optional fallback that brings Glass back when the stock launcher opens.
- **In-app updates** from GitHub Releases. **Backup and restore** of your layout and settings.

## Install

Download the latest APK from [Releases](https://github.com/AazainKhan/glass-launcher/releases) and sideload it (for example with the Downloader app). Requires Android 9 or newer.

### Make it your home screen

- **Google TV / Android TV:** Settings → Home Button → *Make Glass Launcher the Default*.
- **Fire TV:** Fire OS has no default-launcher picker. Either:
  - turn on *Home Button Takeover* in Settings (an accessibility service that only watches for the stock launcher's window), or
  - from a computer:

    ```bash
    adb shell pm disable-user --user 0 com.amazon.tv.launcher
    ```

    Undo with `adb shell pm enable com.amazon.tv.launcher`.

### Optional one-time permission

Lets Glass set itself as the screensaver and switch on its Home-button fallback without opening system menus:

```bash
adb shell pm grant dev.glasslauncher android.permission.WRITE_SECURE_SETTINGS
```

## Using it with a remote

| Press | Does |
|---|---|
| Select | Open |
| Menu (☰), or hold Select | App options: move, top row, folder, icon, hide, info, uninstall |
| Play/Pause | Open the focused app or featured title |
| Back | Close panels, return to the top |
| Up from the top row | Featured shelf, then Settings (⚙) |

## Building

Needs JDK 17 or 21 and the Android SDK. `scripts/build` installs on the connected TV and launches the app.

```bash
scripts/build release
```

UI checks on a real TV use [agent-device](https://github.com/callstackincubator/agent-device) (`scripts/tv snapshot -i`, `scripts/tv tv-remote press down`, …). Every tile has a stable id such as `app:com.netflix.ninja`. See `CLAUDE.md` for the performance rules this project holds itself to.

Releases are built by GitHub Actions when a `v*` tag is pushed. Set the `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` secrets to sign with your own key.

## Credits and licences

- Glass Launcher is licensed under [Apache-2.0](LICENSE).
- [Inter](https://rsms.me/inter/) by Rasmus Andersson, SIL Open Font License ([licenses/Inter-OFL.txt](licenses/Inter-OFL.txt)).
- Icons from [Material Symbols](https://fonts.google.com/icons) by Google, Apache-2.0 (`app/src/main/res/drawable/ic_*.xml`).
- Aerial videos are streamed from Apple's servers and are © Apple.
- This product uses the TMDB API but is not endorsed or certified by TMDB. Streaming availability data provided by JustWatch.
- Weather data by [Open-Meteo](https://open-meteo.com). Featured content from Stremio Cinemeta, YouTube and Plex is shown using your own accounts and keys.
