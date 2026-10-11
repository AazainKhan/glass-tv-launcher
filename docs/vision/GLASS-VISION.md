# Glass vision: every idea, decision and source in one place

Maintained by the Agent Manager. Last updated 2026-10-11. This file indexes everything; the linked files hold the detail.

## 1. What Glass is

A free, open-source, Apple TV-style launcher for Fire TV. It isn't sold as "just a launcher". The pillars:
1. A home screen with no ads that stays your home.
2. Apple TV motion, measured from a real Apple TV and tuned for a $50 stick (0.5 s cold start, under 1% janky frames).
3. It fixes the stick, not just the screen: remote buttons, remote pairing, an always-on screensaver, Control Center over apps.
4. An add-on platform that anyone can extend.
5. A customizable Control Center, iOS 18 style, built for the remote.
6. Integrations people love, plus niche power-user ones (Claude Code on your TV).
7. Gaming as a major pillar (research in progress).
8. Agent-ready: llms.txt and a runbook, so an AI can install it.
9. All the way down: a de-Amazoned Fire OS build (in development).

## 2. Ways to install (one product, four exports)

The user decided not to call these "tiers"; they're ways to get Glass.

| Way | Who it's for | Status |
|---|---|---|
| Install the app | Anyone; sideload the APK, turn on the Accessibility toggle | Available |
| Install + one command | Plus one adb command so Amazon's home never flashes | Available |
| Rooted install (Glass Pro, one Magisk zip) | Rooted sticks; system app, remote keys, tuning, keepalive, debloat | Built (rom/out/glass-pro.zip); stick steps pending |
| Full system install (Glass OS) | Unlocked sticks; rebuilt Fire OS with Glass built in | In development |

Sources: rom/docs/glass-os-spec.md, rom/docs/glass-os-plan.md, rom/docs/decisions-2026-10-10.md, branch docs/tier1-no-root (tier1-no-root.md).

## 3. User demand research

The user's ranked report on 17 Fire TV demands (ads, launchers, storage, bloat, button remapping, caches, AFR, audio, Cast, private listening, profiles, quick settings, SMB, peripherals) is condensed with Glass's status per demand in docs/research/fire-tv-demands.md.

## 4. Add-ons platform (A1)

- Spec: docs/superpowers/specs/2026-10-11-addons-design.md on branch docs/addons-spec (552f9f5).
- Model: separate signed APKs that supply data only; Glass renders them. Extension points are a Top Shelf provider, a Control Center tile, a home row, a remote-button action, a settings page and a screensaver (theme packs later).
- Catalog: our own curated repo, which Droid-ify reads (user decision).
- Phases: (0) prove one Control Center tile add-on, with PhairPlay migrated; (1) all extension points, the SDK and the 10-minute guide; (2) the catalog; (3) launch add-ons. About 9 to 10 weeks.
- Open questions: 7, at the end of the spec.

## 5. Customizable Control Center (P83)

- Research: docs/research/cc-customization.md.
- Key insight: Apple TV doesn't let users customize Control Center, so Glass would be first. It copies iOS 18 and adapts it to the remote:
  - hold Select to edit;
  - Select lifts a tile;
  - the D-pad moves it;
  - Play/Pause removes it;
  - Back exits.
- Tile sizes are 1x1, 2x1 and 2x2. Packing is deterministic first-fit, so tiles never jump around. Pages work like Apple TV's menu-bar icons.
- Built-in quick wins, from Yin's spec docs/superpowers/specs/2026-10-11-cc-quickwins-and-addons-ux.md: Sleep Timer, VPN, Restart, and Clean Up (frees memory and clears caches).
- Design spec (Yin, done): docs/superpowers/specs/2026-10-11-customizable-cc-design.md. Next-fit packing (positions derived from order + size), hold Select 600 ms to edit, Menu resizes, Play/Pause removes, gallery Glass / Add-ons / By App, pages as menu-bar icons (Controls, Media, Home, Agents), Claude Code approve card (Deny-first proposed), +≤8 MB budget.

## 6. Integrations

Top 8 overall, scored as appeal × feasibility:

| # | Integration | Score |
|---|---|---|
| 1 | Now Playing (MediaSession) | 25 |
| 2 | Home Assistant | 20 |
| 3 | Claude Code on your TV | 20 |
| 4 | Plex/Jellyfin | 16 |
| 5 | Philips Hue | 16 |
| 6 | GitHub PR/CI | 16 |
| 7 | VPN/Tailscale | 15 |
| 8 | Weather | 15 |

- Top niche: Claude Code, GitHub approve/merge, homelab dashboards, OctoPrint, Uptime Kuma.
- Earlier showcase picks: Universal Up Next, Doorbell on your TV, Phone as remote. Ask Glass and True cinema mode (AFR, root) come next.
- Claude Code on your TV: http hooks post to a small bridge on the Mac. PermissionRequest hooks wait up to 600 s for the TV's Approve or Deny, and fall back to the terminal prompt. Remote Control has no third-party API.
- Not promised: Google Cast receiver, private listening.

## 7. Gaming (in progress)

Research: firestick-unlocked/tvos27-inspo/GAMING-RESEARCH.md covers cloud and streaming, a library row, controllers, friends and presence, game mode, retro, Control Center tiles and limits.

## 8. Website

- Live at https://aazainkhan.github.io/glass-os (repo AazainKhan/glass-os). Built with Astro Starlight, with llms.txt, .md pages and Stripe-style page actions.
- Critique: rom/site/CRITIQUE.md.
  - Lead with "no ads and it stays your home".
  - Ship the rooted fixes.
  - Prove the numbers.
  - Be honest that add-ons aren't built yet.
  - Drop piracy-adjacent logos.
- Pending copy: branch site/copy-v3 ("Tell your AI to install it"), not shipped. The runbook still needs a live run.

## 9. Other research

- docs/research/apple-android-interop.md: AirDrop is a no-go; the top app-layer wins are file receive, Now Playing and AirPlay hardening.
- docs/research/flauncher-comparison.md.
- ROM: rom/research/{tooling,inventory,package-classification,replacements,account-center}.md.

## 10. Open decisions for the user

1. Approve the customizable Control Center design (when Yin delivers it).
2. Approve the add-ons model and answer the spec's 7 open questions.
3. Website copy direction, including whether to swap the Nuvio hero and the Kodi, Stremio and Jellyfin logos.
4. The P75(B) hold-Home pairing test.
5. Small ones: "p.m." vs "PM", a footer email, and 7 vs 9 Spotify preview covers.
