# Changelog

All notable changes to Glass are recorded here. No version has been released yet.

## 0.3.0-beta.1 (2026-10-20, pre-release)

First plain-APK beta. Release notes: `docs/release/v0.3.0-beta.md`.

### Added

- Search: a search screen with a full keyboard, a symbols page, forgiving matching, a Music section and the source under every result.
- Control Center: Remotes and Game Controllers pages with Pair, Settings and the connected devices; a controller page with a live
  button and stick tester and Button Mapping; one Control Center style; the same gap under the header on every sub-panel.
- Control Center shortcut on a remote key without the key-layout module, and remappable controller buttons.
- The output picker lists every output under Audio Output, with network speakers selectable together.
- Featured: a shared info line and panel, a Seasons view, Resume that starts playback (Stremio resumes through its own link with no
  key press), and a shelf that stays current.
- Install and update progress on app tiles; a "View in Store" shortcut.
- One-at-a-time notices that hold under Do Not Disturb; screen-reader labels, roles and page titles throughout.

### Changed

- Fire TV- and device-specific behaviour is chosen at run time, so Glass runs on any Android TV or Google TV device; Fire-only
  rows and app defaults no longer appear elsewhere.

### Fixed

- A launch that fails now says "Couldn't open <App>."; the open and close animations no longer skip; held-key scrolling settles
  without jank on slow sticks; Home takeover can no longer disable a fallback Home.

## Unreleased

### Added

- Glass Browser: an optional, separate app (`scripts/browser`) for opening web pages and downloading files from the
  Downloads page ("Open Browser" appears only when it is installed). It is not part of Glass, so a web page never runs
  with Glass's privileges; downloads and the on-screen keyboard go through Glass's own rules.

### Security

- Another app on the device could ask Glass's Home screen to open the Amazon Appstore with crafted extras, and on a
  rooted install (Glass Pro) those extras reached a root shell command. Only the Appstore itself may now make that
  request, and its extras are limited to plain identifiers. Builds from source between 2026-10-07 and 2026-10-13 are
  affected; no release contained it.
- Root commands quote or validate every value they include, and Glass's Bluetooth and screen-state receivers are no
  longer reachable by other apps.
