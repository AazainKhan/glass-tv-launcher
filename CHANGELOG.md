# Changelog

All notable changes to Glass are recorded here. No version has been released yet.

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
