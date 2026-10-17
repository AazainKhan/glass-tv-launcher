# Regression sweep, P137–P180 (first beta)

One row per fix. Re-run each on the build under test and record the result in the last column. **Where**: `T` = emulator twin (logic, layout), `S` = stick (looks, motion, hardware, perf), `U` = stick with the user (physical buttons, Bluetooth devices, real apps). Motion checks are judged on an on-stick `screenrecord`, frame by frame, never by eye alone.

Prerequisites on the stick: wall adapter (not the Mac's USB, P169), all five Magisk modules on, Glass default Home, `scripts/perf-gate` dock baseline recorded first.

## Control Center

| P | Check | Steps | Pass | Where | Result |
|---|---|---|---|---|---|
| P137/P173 | One glass style, legible on any scene | Open CC over a dark and over a white backdrop, Light and Dark theme | Same clear glass in both themes; white labels legible (no dark-ink variant) | S | |
| P138 | Grid geometry | Open CC; compare with `control-center.png` golden | Tiles on the 4-column grid, 8 dp gaps, focus ×1.21 | S | |
| P139 | Glass never drops during focus blends | screenrecord D-pad across every CC tile | No frame where a tile goes black/transparent | S | |
| P141 | AirPlay tile shows the routed Sonos speaker | Route to a Sonos speaker, open CC | AirPlay tile names the speaker | U | |
| P146/P150 | Device platters, no on-screen Back | Open Remotes and Game Controllers | Full-width device pills; no Back button; one Back closes the platter | S | |
| P148/P172 | Output picker | Open AirPlay/Audio Output with headphones paired | One list under Audio Output, no Headphones header; Pair row set apart | U | |
| P149 | Round shadows | Focus a circle and a capsule control over a dark scene | Shadow follows the shape (no square corners) | S | |
| P151 | One default page | Fresh CC layout | One page, no page dots; + in edit adds a page | T | |
| P151b | Platter grows from its tile | screenrecord opening/closing Controllers, Remotes, AirPlay | Grows out of its tile and back into it; never bottom-up; the closing platter never turns into another | S | |
| P152 | Edit mode | Settings › Control Center › Edit Layout; move a tile with D-pad | Settings fades out (no frame of bare Home); only the lifted tile jiggles; no dark flash on focus moves | S | |
| P152 | No crash entering edit | Enter Edit Layout 5 times | No Glass restart (`logcat -b crash` empty) | S | |
| P163 | One banner at a time, DND | Trigger two notices quickly; then DND on + a plain notice | Never stacked; plain notice hidden under DND, actionable shown | S | |
| P164 | Search and Accessibility controls | Add both from Add a Control; select each | Search opens search; Accessibility opens the TV's a11y settings | S | |
| P167 | CC shortcut over apps | App in front; hold Menu 700 ms | CC opens over the app; Menu tap still reaches the app | U | |

## Home, Top Shelf, Now Playing

| P | Check | Steps | Pass | Where | Result |
|---|---|---|---|---|---|
| P142 | Home with music opens into Now Playing | Spotify playing → Home | Now Playing, no Cover Flow flash first | S | |
| P143 | No hold dim on tray moves under Now Playing | Now Playing up; move across the tray | Backdrop does not dim per move | S | |
| P153 | TV shelf stays current | Watch something in a TV app, return Home | Its row updates within 5 min / on Home resume | U | |
| P158 | Held-key scroll | Hold Right 5 s on the full-screen shelf row and Spotify Cover Flow (`hold.sh`) | Shelf missed ≤ 10 %, Cover Flow ≤ 3 %; dock perf-gate p99 ≤ 28 ms | S | |
| P165 | Cover Flow caption | Focus Spotify | Title centred under the front cover, above the chevron | S | |
| P177 | Pill and search circle on white | White wallpaper | Both read as glass (halo + milky glass), same recipe | S | |
| move race | Held D-pad in move mode | Move an app, hold Right across the dock | Every step lands | U | |

## Launch and close

| P | Check | Steps | Pass | Where | Result |
|---|---|---|---|---|---|
| P147/P149 | Open is continuous | screenrecord Select on Spotify (cold) | Zoom within ~120 ms of the press, 17 ms frames to full screen, no green/black flash | S | |
| P147/P149 | Close is continuous | screenrecord Home from Spotify | App preview shrinks into the tile with no size jump and no dark frames | S | |
| P162 | Failed launch says so | Disable an app's launch activity (`pm disable` a test app), select its tile | "Couldn't open <App>." capsule; zoom returns into the tile | T | |
| P178 | Double Select | Gamepad X X fast on an app tile | One zoom, no flash, app starts once | U | |

## Search, info, resume

| P | Check | Steps | Pass | Where | Result |
|---|---|---|---|---|---|
| P156 | More Info + Seasons | More Info on a movie and on a series | Info line, cast/crew; Seasons view navigable; Back closes | U | |
| P160 | Resume | Resume a Continue Watching item in Netflix, Prime, YouTube, Plex | Playback starts with no extra press; fill `docs/research/resume-playback-per-app.md` | U | |
| P170 | Stremio resume | Resume a Stremio item with a video id (Stremio's API reachable) | Plays with no key press | U | |
| P161/P171 | Search | Type with the one-row keyboard; open an app, a title, a Music result | Results live; Music opens the app's search (or the web link); Back works | U | |

## System, devices, accessibility

| P | Check | Steps | Pass | Where | Result |
|---|---|---|---|---|---|
| P144 | Sonos opt-out hint | Route Spotify to Sonos | The Spotify-specific opt-out hint; TV keeps its sound | U | |
| P149 | Fast | Settings › Root › Fast | Background apps closed once; animator scales stay 1.0 | S | |
| P149 | Cached-app cap | `dumpsys activity settings` after a reboot | `CUR_MAX_CACHED_PROCESSES=12` | S | |
| P154/P157 | Compact rename + pairs | Rename a folder; open an install confirm | 340 dp panel; Done/Cancel and Install/Cancel as half-width pairs | T | |
| P155 | Fire-only rows on Fire | Settings › Root and Display & Text | Home Takeover, Own Remote Pairing, Memory Tuning, Fire TV Accessibility all present | S | |
| P155 | App buttons | Press the remote's four app buttons | Netflix, Prime, Music, Disney+ open | U | |
| stockHome | Boot never hangs | Home Takeover on, reboot | Boots to Glass; `pm list packages -d` never lists a settings package | S | |
| P166 | Screen reader | TalkBack on: Home, CC, Search, Settings, Downloads, Files, Remotes | Every stop spoken with a role; pages announce themselves | U | |
| P168 | Install progress | Update an app from the store | Tile badge with progress; capsule at start/end | U | |
| P169/P175 | No BLE scan under BT audio | Headphones on A2DP + gamepad, music playing | `logcat -s GlassRemotePair` shows "scan stops" and no new scans; music clean; no reset over 30 min | U | |
| P174/P176 | Pair and test controllers | CC › Game Controllers › Pair; Test Controller; map a button | Pairs a controller in pairing mode; live test reacts; mapped button fires over an app; 20 dp gap under every platter header | U | |
