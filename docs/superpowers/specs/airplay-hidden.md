# AirPlay as a hidden PhairPlay driven by Glass (P117, re-spec)

Yang, 2026-10-13. Plan only: no code yet. This replaces the module-import plan in `airplay-builtin.md` (branch
`docs/airplay-builtin`), which the user rejected: Glass does not vendor PhairPlay's source.

**Goal.** AirPlay feels like part of the OS. PhairPlay stays its own app, but it is invisible: no launcher icon, no entry
in Glass's app list, nothing the user opens. Glass Settings › AirPlay and the Control Center tile drive every one of its
settings. Where Glass can draw something itself (the pairing code, the Now Playing card, the settings pages) it does, in
Glass's style. Upstream is credited.

**Inputs.** Upstream `github.com/mazer666/PhairPlay` (Apache-2.0) at the pin in `scripts/phairplay`; the existing
`tools/phairplay/glass-control.patch` (305 lines: a signature-guarded `ControlReceiver`, the session bring-forward);
Glass `system/AirPlay.kt`, `home/ControlCenter.kt` (the AirPlay platter), the Settings panel.

## 1. Why this shape

- **Nothing is copied into Glass.** The earlier plan put PhairPlay's protocol code and its native `playfair` tables in
  Glass's repo and APK. `playfair` is reverse-engineered FairPlay data of unclear licence, and `:airplay` was linked into
  every build. Here the repo carries only our patch (as today) and a pinned upstream commit that `scripts/phairplay`
  fetches and builds. Whatever upstream ships stays upstream's.
- **Crash domain and memory stay out of Home.** The receiver, decoders and their buffers live in PhairPlay's process.
- **No NDK or new libraries in Glass.**

## 2. What changes in the PhairPlay patch

All of it lives in `tools/phairplay/glass-control.patch` (extended), signature-guarded as today
(`com.phairplay.permission.CONTROL`), so only apps signed with Glass's key can talk to PhairPlay.

1. **Invisible.** Drop the `LAUNCHER` / `LEANBACK_LAUNCHER` intent filters from `MainActivity`. It stays as the video
   surface host, started by the service with an explicit component (already how the bring-forward works), never from Home.
   Glass also adds PhairPlay's packages to its built-in hidden list, so a build without the patch still doesn't show it.
2. **Control protocol v2** (broadcasts, same permission both ways):
   - `SET` with `key` and a typed extra (`bool` / `int` / `string`): writes one setting through PhairPlay's
     `SettingsRepository`. Unknown keys are ignored and answered with `ERROR`.
   - `QUERY` (exists): PhairPlay answers `STATE` carrying `running`, `sender`, `protocol` (a version int), and every
     setting's current value, so Glass renders one source of truth and never caches its own copy.
   - New events PhairPlay sends: `PAIRING_CODE` (the 4-digit code, and when it is dismissed), `SESSION`
     (`started` / `ended`, sender name, kind: mirror / video / audio / photo), `NOW_PLAYING` (title, artist, artwork
     reference, position, duration, playing).
   - New command: `TRANSPORT` (`play`, `pause`, `stop`) for Glass's Now Playing card, forwarded to the sender over DACP.
3. **Session bring-forward and step-back** stay as in the existing patch (mirroring and video need PhairPlay's surface on
   screen). The patch adds only an optional `chrome=none` extra so the activity draws nothing but the video.
4. **Version handshake.** `protocol` lets Glass tell an old PhairPlay (the current patch: ENABLE / DISABLE / QUERY only)
   from a v2 one, and fall back to the plain toggle for the old one.

## 3. Settings: every PhairPlay setting, where Glass shows it

| PhairPlay (`AppSettings`) | Glass home | Notes |
|---|---|---|
| `airPlayEnabled` | **Settings › AirPlay › AirPlay**; the CC tile | One setting, two surfaces. The tile shows the sender while a session runs. |
| `displayName` | **Settings › AirPlay › Name** | Default: the TV's name; max 63 chars; Glass's on-screen keyboard. |
| `airPlayPinAuthEnabled` | **Settings › AirPlay › Require Code** | The code itself appears in Glass's own full-screen card (`PAIRING_CODE`). |
| `forceHighResolution` | **Settings › AirPlay › Video Quality** (Standard 1080p / High 1440p) | Advanced row. |
| `mirrorAudioEnabled` | **Settings › AirPlay › Mirroring Audio** | Advanced; upstream marks the clock sync experimental. |
| `showDebugOverlay` | Developer options | Not a user setting. |
| `startOnBoot` | **Removed from the UI** | Glass sends `ENABLE` at boot when AirPlay is on (it already knows at start). |
| `miracastEnabled`, `castEnabled` | **Hidden** | Out of scope; Glass never sends them. |

Rows use Glass's existing `MenuRow`s and the tvOS look from the tvos27-guidelines skill. Items upstream doesn't have
(a password, "Same Network only") are not offered until PhairPlay supports them.

## 4. What Glass draws itself

- **Settings › AirPlay** (above) and the **CC AirPlay platter** (exists; gains the Name row and the quality choice).
- **Pairing code card**: on `PAIRING_CODE`, a Glass card over whatever is showing, dismissed on the matching close event.
- **Now Playing**: audio-only and URL-video sessions feed Glass's Now Playing widget from `NOW_PLAYING`, with
  `TRANSPORT` for play / pause. Mirroring and video playback still show PhairPlay's surface (it is the video).
- **Waiting / "not installed" state**: when PhairPlay is absent the tile and Settings row say "Install PhairPlay" and
  point at `scripts/phairplay install`; nothing crashes.

Where this stops: the video surface is PhairPlay's. Making it Glass's would mean vendoring the decoders, which the user
ruled out.

## 5. Install modes

| Mode | PhairPlay is | Control |
|---|---|---|
| APK (non-root) | Installed beside Glass, same signing key (`scripts/phairplay`), icon hidden by the patch | Signature permission; works without root. Bring-forward needs `SYSTEM_ALERT_WINDOW` (one `appops` grant, as today); without it Glass shows "Open AirPlay" on the card instead of a silent miss. |
| Glass Pro (root) | Same APK; the grant is done by the installer | Same |
| Glass OS | Preinstalled as a hidden system app (privapp XML grants the overlay permission) | Same, no grants to make |

No mode needs root to function, and none relies on a private API.

## 6. Credits and licence

- Settings › AirPlay › About: "AirPlay receiving by PhairPlay (Apache-2.0)", with a licences screen showing upstream's
  `LICENSE` and `NOTICE`, and a link to the project.
- README and the site: a Credits line. The patch file keeps upstream's headers; changed files get a "Modified for Glass"
  line (Apache-2.0 section 4(b)).
- The repo ships no upstream source. If Glass OS preinstalls the APK, that image carries upstream's licences next to it.

## 7. Phases (each its own branch, none requires the stick except where marked)

1. **Protocol v2 in the patch + Glass client** (`AirPlay.kt` parsing, typed settings model, unit tests for encode / decode
   and the old-PhairPlay fallback). Patch applies to the pin: checked by `scripts/phairplay` dry-run. Stick: Yin installs.
2. **Settings › AirPlay page + CC platter additions** (JVM screenshots; goldens).
3. **Pairing card and Now Playing feed** (JVM tests with fake events; stick for the real handshake).
4. **Hide the icon** (patch + Glass's hidden list) and the Credits.

## 8. Open questions for Yin / REA

- Does PhairPlay's `SettingsRepository` allow writes from the receiver without the service running (DataStore access
  from a broadcast)? If not, `SET` goes through the service.
- Which DACP commands does upstream already forward to the sender, for `TRANSPORT`?
- Glass OS: can a system app hold `SYSTEM_ALERT_WINDOW` by privapp XML, or only by `appops`?

## 9. What is dropped from the earlier plan

The `:airplay` module, the NDK / CMake build, BouncyCastle and `ddplist` in Glass, the cross-process surface hand-off,
the settings migration, and the `playfair` licence work. `airplay-builtin.md` stays on its branch for reference only.
