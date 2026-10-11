# Control Center quick wins and Add-ons UX (D1, D2)

Yin, 2026-10-11. Design only, no code. Answers demands #14 (VPN, sleep timer, reboot) and #7 (one-tap cache clear) from `docs/research/fire-tv-demands.md`. Follows tvOS 26/27 Control Center (tvos27-guidelines: SYS-01, SYS-03, MOTION-05/13) and our liquid-glass rules (one material, glass tinted by what's behind, no text shadows).

## D1. Four Control Center tiles

### Where they go

The current grid, top to bottom:
- the Settings 2×2 tile, with the Wi-Fi and Bluetooth pills stacked beside it;
- the Launcher Settings full-width pill;
- two rows of round buttons.

tvOS 27 puts its Sleep Timer in the pill row, so the new layout adds one row of two pills under Settings, Wi-Fi and Bluetooth:

```
[ Settings 2×2 ] [ Wi-Fi     ]
[              ] [ Bluetooth ]
[ Sleep Timer  ] [ VPN       ]      <- new pill row (2×1 each)
[ Launcher Settings (full)   ]
( Game )( Theme )( Saver )( Switcher )
( AirPlay )( Perf )( Clean Up )( Restart )   <- Restart new; Free Memory becomes Clean Up
```

- Everything stays on the existing cell (u = 48 dp, gap 8 dp). All four tiles join `CONTROL_CENTER_TILES`, so the user can hide each one in Settings › Control Center.
- The VPN pill hides itself when no VPN app is installed. A hidden pill frees its row slot, and Sleep Timer then sits alone, left-aligned.
- The Control Center column already scrolls the focused tile fully on screen, so the extra row needs no layout change.
- State follows our pill convention: the icon sits in a white disc while the thing is on, a two-line label shows the state, and a focused tile is white with dark content.

### 1. Sleep Timer (pill, 2×1)

- **Look:** the line 1 label is "Sleep Timer". Line 2 reads "Off", or the time left ("28 min"), updated once a minute and never per frame. The icon is a moon with "z", in the white disc while the timer runs.
- **Select:** opens a small glass menu anchored to the pill. It uses the context-menu style (440 px wide, 70 px rows, white capsule on the focused row) with Off, 15, 30, 60 and 90 min. The current choice has a ✓ and gets focus when the menu opens. The menu grows from the pill and shrinks back into it (MOTION-05), with no bounce (MOTION-13).
- **One minute before:** a glass banner at the top centre reads "Going to sleep in 1 minute". Any key cancels the timer, and the banner says "Sleep timer cancelled" for 2 s.
- **At zero:**
  1. Pause the active media session (Glass already holds session access for Now Playing).
  2. Wait 1 s.
  3. Put the stick to sleep. HDMI-CEC then turns the TV off if Fire's CEC setting allows it.
- **Timing:** the timer runs on AlarmManager (exact, allowed while idle), so it fires with Glass in the background or the screensaver up.
- **Precedence:** a running timer overrides Always-On Screen Saver (P80b), because an explicit timer wins over "never sleep".
- **Tier and paths, by setup:**

| Setup | Pause | Sleep |
|---|---|---|
| No root | Media session (Glass's notification listener) | AccessibilityService `GLOBAL_ACTION_LOCK_SCREEN` via RemoteKeysService. **Verify on the stick that it really sleeps Fire OS 8.** If it doesn't, the no-root tier only pauses, and line 2 says "Pauses only". |
| System app | Same | Same: DEVICE_POWER is signature-only, so being a priv-app doesn't help |
| Root | Same | `input keyevent KEYCODE_SLEEP` (proven on this stick in P75 testing) |

- **Known caveat:** the user's ASUS monitor won't wake from the stick's sleep until its own power button is pressed (no CEC, and the MTK driver blanks TMDS without RxSense). Note it on the menu's footer only when no CEC sink is detected: "Your display may need its own power button to wake."

### 2. Restart (round, 1×1)

- **Look:** a circular-arrow glyph, labelled "Restart" under it when focused, like the other round buttons.
- **Select:** opens a glass alert (SYS-03). The title is "Restart Fire TV?" and the body is "Apps that are open will close." It has two buttons, Restart and Cancel. **Focus opens on Cancel** (P72 rule for destructive confirms), and Back cancels.
- **Restart, by setup:**
  - System app: `PowerManager.reboot(null)`. REBOOT is signature|privileged, so it needs an allowlist entry; scripts/privapp-check adds it, and one reboot refreshes the system copy (P75 B path).
  - Root: `svc power reboot`.
  - No root: the alert's Restart button is labelled "Open Fire Settings" and the body is "Restart from Settings › My Fire TV". It opens that page. No dead-end tile.
- **Tier:** system app or root. Without either it degrades to that shortcut.

### 3. Clean Up (round, 1×1; replaces Free Memory)

- **Why one tile:** one tap answers demand #7. Two broom buttons ("Free Memory" and "Clear Caches") would confuse; users think "clean up", not RAM vs storage.
- **Select:** runs both jobs at once, with no confirm. Caches are disposable, and this matches the current Free Memory, which runs immediately.
  1. Free memory: today's background-process trim.
  2. Clear app caches, all apps in bulk.
- **Feedback:** the label reads "Clean Up · cleaning…", then "Clean Up · 1.2 GB" for 4 s. The number is storage freed. If only memory was freed, it says "Clean Up · 340 MB memory".
- **Bulk cache clear, by setup:**
  - System app: `PackageManager.freeStorageAndNotify(null, Long.MAX_VALUE, …)` through reflection. CLEAR_APP_CACHE is signature|privileged, so it needs an allowlist entry.
  - Root: `pm trim-caches 999G`.
  - No root: no bulk clear is possible. The tile still frees memory. A second row in its menu, "Biggest caches…", lists apps by cache size (StorageStatsManager, PACKAGE_USAGE_STATS is already granted), and each row opens that app's App Info so the user taps "Clear cache" there. The label says "Clean Up · memory only" the first time, so the limit is visible.
- **Never clears:** Glass's own image cache (it would cost a re-bake), Spotify and other apps' offline downloads (those are app data, not cache), and app data in general.
- **Tier:** system app or root for the bulk clear; memory works on every tier.

### 4. VPN (pill, 2×1)

- **Look:** line 1 is "VPN". Line 2 reads "Connected", "Off", or the app's name when the app is known but not connected ("NordVPN · Off"). The icon is a shield, in the white disc while connected.
- **State:** `ConnectivityManager.getNetworkCapabilities(activeNetwork).hasTransport(TRANSPORT_VPN)`, which needs only ACCESS_NETWORK_STATE (already held). It refreshes through a NetworkCallback, not polling. Which app owns the tunnel isn't readable without privileged APIs, so the pill says "Connected" and nothing more.
- **Select:** opens the chosen VPN app; most expose no deep link to "connect". There is no toggle, because Glass can't turn another app's tunnel on or off.
- **Choosing the app:**
  - Default: the only installed app that declares `android.net.VpnService`.
  - With several: Settings › Control Center › VPN App lists them.
  - With none: the pill is hidden.
- **Tier:** no root, on every setup.

### Root and permission summary

| Tile | No root | System app | Root |
|---|---|---|---|
| Sleep Timer | Pause; sleep if the accessibility lock works (verify) | Same | Pause + sleep |
| Restart | Opens Fire's Restart page | Reboots (REBOOT) | Reboots |
| Clean Up | Memory + "Biggest caches…" list | Memory + bulk caches (CLEAR_APP_CACHE) | Memory + bulk caches |
| VPN | Full | Full | Full |

New privileged permissions (REBOOT, CLEAR_APP_CACHE) go through scripts/privapp-check and the P75(B) module refresh. Don't add them by hand.

### Checks before calling them done

- Stick: the accessibility lock really sleeps Fire OS 8 (it decides Sleep Timer's no-root tier). Also measure Clean Up's MB freed on the user's stick, and check Control Center open frame counts with the extra row (ccbench2 baseline >33 ms 3.0/cycle).
- Light and dark, at the large text size, and Reduce Motion.

## D2. Add-ons: how they appear in Glass

Yang owns the architecture; this is only what the user sees.

### Settings › Add-ons (catalog)

It uses the standard Settings layout (SYS-02): the title centred, a big puzzle-piece icon in the left third, and a list on the right.

```
Add-ons
   INSTALLED
   Sleep Sounds          1.2   ›
   Stremio Shelf         0.4   ›    (dot = update available)
   AVAILABLE
   Weather Glance        Get
   SMB Shares            Get   ROOT
   Add from URL…               ›    (typed on the phone, like "Type on Your Phone")
```

- Each row shows the name and version, or a "Get" capsule for an add-on that isn't installed. A small grey **ROOT** or **SYSTEM** tag marks add-ons that need those tiers.
- A dot marks an available update, matching the home screen's new-app dot (HOME-04).
- **Add-on page:** a one-paragraph summary, then a "Adds" line listing where it shows up (Control Center tile, Top Shelf for app X, Settings page), a "Can" list of permissions in plain language, an On/Off toggle, Automatic Updates, and **Remove** last. Remove gets a destructive confirm with focus on Cancel.

### Install and permission prompts

- Prompts use a glass alert (SYS-03). The title is "Install Sleep Sounds?" The body is "It adds a Control Center tile and can: play audio; read what's playing." The buttons are Install and Cancel.
- **Default focus:** Install for add-ons that only add UI; **Cancel** for any that ask for network access to the local network, notifications or media control, system or root. For root add-ons, a second line in the body says what root is used for, in one sentence.
- Permissions are asked once at install. Later changes to permissions show the same alert when the update arrives, and the update waits until it's accepted.
- A misbehaving add-on (crash, or timing out three times) is paused automatically. Its row reads "Paused · stopped responding", and its page has a Resume button. There are no pop-ups.

### An add-on's Control Center tile

- Add-on tiles use the same CcTile sizes, round 1×1 or pill 2×1. They **never** get the 2×2 tile, which is reserved for Fire TV Settings.
- The glyph is a monochrome template icon, drawn by Glass in the tile's foreground colour so it matches the glass tiles. Add-ons can't supply full-colour art here.
- The add-on sets the label and the second line (state, up to about 18 characters). Glass draws the white disc for "on", so state looks the same everywhere.
- Tiles appear in an Add-ons group after the system tiles. The user reorders and hides them in Settings › Control Center, like built-in tiles.
- Selecting a tile runs the add-on's action. Anything bigger opens the add-on's page in Glass Settings (SYS-02 layout), not its own full-screen UI.

### An add-on's Top Shelf

- An add-on can only **supply a feed** (titles, art, deep links) for an installed app's Top Shelf, for example Stremio titles. Glass renders it with its own card row or Cover Flow, and it goes through the P81 image gate. Add-ons never draw full-screen UI on Home.
- The row title gets a small grey attribution: "Popular · via Stremio Shelf".
- If both the app and an add-on provide a feed, the app's own feed wins. Settings › Top Shelf Content gains a per-app source choice.

### Out of scope here

Signing, sandboxing, the API surface, update transport and the catalog source are Yang's architecture spec.
