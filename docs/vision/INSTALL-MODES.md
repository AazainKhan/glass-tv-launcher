# Glass in each way to get it

Yin, 2026-10-12. The user's rule: **every Glass feature works in all three ways to get Glass**, either fully or with a clean fallback. A feature must never silently do nothing.

- **APK.** Sideload the app, no root. Anything beyond plain permissions comes from Fire's own Settings pages or a one-time adb command from a computer (not root).
- **Glass Pro.** A rooted stick with the one Magisk zip. Glass becomes a privileged system app with a su grant, the remote keymap is installed, and the stick is tuned.
- **Glass OS.** The rebuilt Fire OS with Glass baked in as a priv-app. Magisk and the su grant stay (rom/docs/glass-os-spec.md §5), so for every feature below **OS behaves like Pro**. The one difference: OS v1 keeps the remote keymap, tuning and keepalive in Glass Pro's companion mode.

The table is built from the code on master `7b256bf` and from the earlier no-root study (`docs/research/tier1-no-root.md`, master `fd05650`). **No row has been tested on an unrooted stick**: the only stick is rooted (see "Testing APK mode" at the end).

## How to read the table

Needs:
- **root:** su
- **priv:** a privileged permission, which only a system app gets
- **a11y:** Glass's accessibility service is on
- **usage:** usage access
- **notif:** notification-listener access
- **wss:** WRITE_SECURE_SETTINGS, granted with adb
- **loc:** location
- **keymap:** the glass-remote-keys keymap (Pro/OS)

Verdict for APK:
- ✅ works
- 🟡 clean fallback (the user sees what to do or what's limited)
- 🔴 silent or misleading: **a gap to fix**

## Features

### Home and launching

| Feature | Needs | APK | Pro / OS | APK verdict |
|---|---|---|---|---|
| Glass is Home (Home button, boot) | root (Pro), a11y or adb (APK) | Fire's Home goes to Amazon's launcher. The optional Home guard (a11y) bounces back to Glass after a flash. The reliable route is one adb command (`pm disable-user com.amazon.tv.launcher`). Nothing restores the guard after a force-stop. | Amazon's launcher is disabled, and `keepHome` re-asserts it every time Glass comes forward | 🟡 works only after setup, and setup isn't guided (gap 1) |
| Rows, Top Shelf feeds, Cover Flow, folders, move, hide, themes, wallpaper | none | Full | Full | ✅ |
| Fire TV app art (Amazon's tile icons) | root | Falls back to each app's own banner | Full | ✅ quiet degradation, fine |
| Continue Watching source (Watch Next + apps' continue rows) | priv (EPG permissions) | The source fails with "Turn on Settings › Root › System App…": an action APK users can't take (that row is greyed) | Full | 🔴 points at a root-only fix (gap 7) |
| App open/close zoom, instant back (P87) | a11y (window focus) | Full once a11y is on; with it off, Back is never taken (fail-safe) | Full | ✅ |
| Appstore shortcut | root (turns Amazon's launcher on briefly) | `open()` returns false. The fallback launch lands back on Glass, so nothing appears. | Full | 🔴 does nothing (gap 5) |
| Uninstall from the app menu | none | System confirm dialog | Same | ✅ |
| Updates | "Install unknown apps" | Prompt each time | Same | ✅ |
| Icon packs: list and apply | none | Full | Full | ✅ |
| Icon packs: install a downloaded pack | root | Confirm dialog promises root, then a toast says to use Downloader | Silent `pm install` | 🔴 misleading dialog (gap 8) |

### Control Center

| Feature | Needs | APK | Pro / OS | APK verdict |
|---|---|---|---|---|
| Control Center from Home (status pill) | none | Full | Full | ✅ |
| Control Center over any app (remote Settings button) | keymap + a11y | Nothing opens it: Fire keeps the Settings button | Full | 🟡 only reachable from Home; no trigger exists (gap 9) |
| Customizable layout, edit mode, gallery, pages | none | Full | Full | ✅ |
| Wi-Fi / Bluetooth pills | loc for the network name | "Connected" instead of the SSID | Full | ✅ |
| Fire TV Settings tile and its sections | priv (3 sections) | The 3 guarded sections open the nearest Android page | Full | 🟡 acceptable, though the page differs |
| Sleep Timer | notif (pause), a11y or root (sleep) | Sleeps through a11y `LOCK_SCREEN`, **not yet verified to sleep Fire OS 8**. Nothing pauses without notif. With a11y off, nothing happens at zero and nothing says so. | Pause + `KEYCODE_SLEEP` | 🔴 can silently do nothing (gap 3) |
| Restart | root or priv (REBOOT) | Alert opens Fire's Restart page ("Restart from Settings") | Reboots | ✅ clean fallback |
| Clean Up | root (`am kill-all`, `pm trim-caches`), priv (CLEAR_APP_CACHE) | Memory step frees ~0 MB (`am kill-all` needs root); cache step is denied. Label shows "memory only" with a number near 0. | Memory + caches | 🔴 misleading (gap 4) |
| VPN pill | none | Full | Full | ✅ |
| AirPlay | the PhairPlay app | Hidden unless PhairPlay is installed | Same | ✅ |
| Smart Home | none | Opens the app if present | Same | ✅ |
| Ask Alexa | root (virtual mic key) | `ACTION_ASSIST`, which opens and closes at once; result ignored | Full | 🔴 does nothing (gap 6) |
| Alexa Settings | root | Hidden (cached `Root.known`) | Full | ✅ (hidden) |
| Performance, Free Memory tiles | root | Hidden | Full | ✅ (hidden) |
| Claude Code approve card | network (LAN) | Full | Full | ✅ |
| Demo add-on tiles | none | Full | Full | ✅ |

### Remote and system

| Feature | Needs | APK | Pro / OS | APK verdict |
|---|---|---|---|---|
| Remote app buttons, Recents button | keymap (root) | Fire's own behaviour; Settings › Remote Buttons explains | Mapped by Glass | 🟡 explained |
| Hold Back 1.5 s goes Home | a11y | Full once a11y is on | Full | ✅ |
| Remote-keys service stays on after updates (P87 self-heal) | root, or wss | `keepKeysService` returns silently; `HomeGuard.ensureRemoteKeys` heals only when the keymap is active, i.e. never in APK mode | Healed via su | 🔴 the user isn't told the service is off (gap 2; Yang's P93) |
| Own Remote Pairing (P75 B) | priv + root (prop) | Toggle greyed | Full | 🟡 greyed |
| Remote keepalive | root (service.d) | Not installed; Fire's 60 s re-pair can drop a sleeping remote | Full | 🟡 Fire's stock behaviour |
| App switcher: list | usage | Only apps opened through Glass | Full list (usage granted) | 🟡 shorter list; should say why (part of gap 10) |
| App switcher: previews | a11y (screenshots) | Art instead of previews | Full | ✅ |
| App switcher: close an app | root or priv (`forceStopPackage`) | `killBackgroundProcesses` only. The card disappears but the app keeps running; the result is ignored. | Real force-stop | 🔴 silent (gap 10) |
| Now Playing on Home | notif | No card | Same (grant) | 🟡 should offer the grant (see gap 3) |
| Aerials screensaver (in Glass) | none | Full while Glass is in front | Full | ✅ |
| Always-On screensaver (P80b) | wss or root | Toggle disabled with the adb grant command in its help | Full | 🟡. One bug: with the toggle on and no permission, turning it off snaps back silently |
| System app, memory tuning, App Freezer, Root Log, Performance rows | root | Settings › Root says "Not detected"; rows greyed | Full | ✅ (greyed) |
| PSS / tuning (zram, LRU-first lmkd, 12 cached apps) | root | Fire's defaults (4 cached apps) | Tuned | ✅ invisible |

## Gaps in APK mode, ranked by how many APK users would notice

Ranked by how often an APK user meets the gap: first-run and daily-use gaps rank highest.

1. **Becoming Home isn't guided.** Every APK user meets this first, and Glass is "a launcher" only if this works. *Fallback:*
   - A first-run "Make Glass your Home" card that offers both routes:
     - Accessibility: deep-link to Fire's Accessibility page and say which toggle to turn on.
     - Computer: the one adb command, with a copy button.
   - Re-enable the Home guard after a force-stop when wss is held (today only remote keys are re-enabled).
2. **Accessibility off goes unnoticed.** Instant back, the Home guard, hold-Back, switcher previews and Sleep Timer's sleep all depend on it. After an update or force-stop, an APK user just loses them. *Fallback:* P93's design is the model:
   - A Glass setting is the source of truth.
   - Pro/OS heal it through su and respect a manual off.
   - APK shows a deep-link card ("Turn on Glass in Accessibility") on Home whenever the setting says on but the service is off.
   - Merge the two healers (`HomeGuard.ensureRemoteKeys` and `RootFeatures.keepKeysService`) into one.
3. **Sleep Timer can silently do nothing.** It's a headline Control Center tile. *Fallback:*
   - Verify on the stick that a11y `LOCK_SCREEN` sleeps Fire OS 8 (the spec's open check).
   - If it doesn't, line 2 says "Pauses only".
   - With a11y off, the timer menu says "Needs Glass in Accessibility" and links there.
   - Without notif, offer the Now Playing grant (or say "Can't pause; will sleep only").
4. **Clean Up is misleading.** It shows "memory only" with ~0 MB. *Fallback:*
   - Free memory with `ActivityManager.killBackgroundProcesses` over recent packages. KILL_BACKGROUND_PROCESSES is a normal permission Glass already holds, so the number becomes real.
   - Add the spec's "Biggest caches…" list (StorageStatsManager; each row opens App Info) instead of the denied bulk clear.
5. **Appstore shortcut does nothing.** *Fallback:* hide it in APK mode. The Fire OS 8 Appstore has no screen without Amazon's launcher, so there is nothing to open. If a store is wanted, point to the bundled store (Droid-ify) or a web link.
6. **Ask Alexa does nothing.** *Fallback:* hide the tile without root, as Alexa Settings already is. Or relabel it "Hold the Alexa button", which is the real APK path.
7. **Continue Watching points APK users at a root-only fix.** Its error says "Turn on Settings › Root › System App". *Fallback:* in APK mode, leave Continue Watching out of the Top Shelf source list, or label it "Glass Pro", so nobody can choose a source that can't work.
8. **Icon pack install promises root.** *Fallback:* in APK mode, skip the confirm and say up front "Open the file with Downloader or ES File Explorer to install it".
9. **Control Center only opens from Home.** *Fallback (to prototype):* a key the a11y service does receive on stock Fire OS, such as a long press of Menu (☰). Until then, Settings › Remote Buttons says how to reach it.
10. **Closing an app in the switcher doesn't close it.** It's silent, and the user believes it worked. *Fallback:*
    - Keep `killBackgroundProcesses`.
    - The first time in APK mode, say "Removed from the list. To fully stop it, use App Info › Force stop", with a link.
    - The shorter list without usage access gets a one-line hint with the adb command.

Pro/OS-only defects found along the way (not APK gaps, but silent):
- **Performance stores "Fast" before the root action succeeds.** If su is later revoked, the tile says Fast while nothing was applied.
- **Alexa Settings is gated on the cached `Root.known`.** A tap after su is revoked does nothing.
- **Always-On off with no permission snaps back** silently (see the table).

## Merge rule (from 2026-10-12)

Yin rejects any merge that needs root, a privileged permission, a special grant or the keymap without **defined APK behaviour**: either full support or one of the clean fallbacks below. Each PR states its row for this table, and this file is updated in the same merge.

Clean fallbacks, in order of preference:
1. **Same result through a non-root API**, e.g. `killBackgroundProcesses`, a11y global actions, or Fire Settings deep links.
2. **Hidden**, when nothing equivalent exists and the absence isn't confusing (Alexa Settings, Performance).
3. **Shown with a one-line explanation and a deep link or copyable adb command** (Always-On screensaver, Restart, Remote Buttons).
4. Never shown-and-silent, never a promise of root in the APK UI, and never a stored state that a failed action didn't apply.

Self-healing state follows P93's pattern:
- A Glass setting is the source of truth.
- Pro/OS repair it through su after updates and respect a manual off.
- APK shows a card linking to the right Fire Settings page.

## Testing APK mode

APK mode is tested on the **emulator** (`scripts/emulator`) as a plain APK install: no su, no keymap, no system app, no grants. The rooted stick is not changed for these tests. Afterwards, run `scripts/emulator release-usb` so the stick stays on adb.

Simulate APK mode on the stick only when a check really needs Fire-specific behaviour, and ask the Agent Manager first. Examples: whether a11y `LOCK_SCREEN` sleeps Fire OS 8, or Fire's own key policy.
