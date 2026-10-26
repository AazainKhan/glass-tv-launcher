# beta 2 — the checks only you can do at the TV

Everything here needs a human hand on the remote or a device on the TV. Everything else in
[`regression-sweep.md`](regression-sweep.md) runs on the stick unaided. **16 rows, about 30 minutes** if you skip the
two long ones; the long ones need a charger and an hour.

Run them on the beta 2 build once it is installed (release build — a debug APK is not a valid smoothness test).
Say what failed and I will write it up; you do not need to fill in the table yourself.

## First, five minutes (no extra gear)

| # | Row | Do | You should see |
|---|---|---|---|
| 1 | P155 | Press the remote's four app buttons | Netflix, Prime Video, Amazon Music and Disney+ each open |
| 2 | P167 | With an app in front, **hold Menu ~0.7 s** | Control Center opens over the app; a normal Menu tap still reaches the app |
| 3 | P178 | On an app tile, tap a gamepad's **X twice quickly** | One zoom, no flash, the app starts once |
| 4 | move race | Menu → Move on a dock app, then **hold Right** across the dock | Every step lands; nothing jumps or sticks |
| 5 | P156 | On a movie: More Info. On a series: More Info → Seasons | Info line with cast; Seasons is navigable; Back closes it |
| 6 | P161/P171 | Search: type on the one-row keyboard; open an app, a title, then a Music result | Results update as you type; Music opens the app's own search (or its web link); Back works |
| 7 | P153 | Watch something in a TV app, then press Home | That app's row on the shelf is current (within 5 min or on resume) |
| 8 | P144 | Route Spotify to a Sonos speaker | The Spotify-specific opt-out hint appears, and the TV keeps its own sound |

## Needs headphones or a controller

| # | Row | Do | You should see |
|---|---|---|---|
| 9 | P148/P172 | Pair headphones, then open Control Center → Audio Output | One list under Audio Output, no "Headphones" header; the Pair row is set apart |
| 10 | P141 | Route to a Sonos speaker, open Control Center | The AirPlay tile names the speaker it is playing on |
| 11 | P174/P176 | Control Center → Game Controllers → Pair, then Test Controller, then map one button | Pairs in pairing mode; the tester reacts live; the mapped button fires while an app is in front |
| 12 | P169/P175 | Headphones on Bluetooth audio **and** a gamepad connected, music playing, 30 min | No audio resets. On a computer: `adb logcat -s GlassRemotePair` shows "scan stops" and no new scans |

## Needs an install and a screen reader

| # | Row | Do | You should see |
|---|---|---|---|
| 13 | P168 | Update an app from the store | The tile shows a progress badge; a capsule appears at the start and the end |
| 14 | P166 | Turn TalkBack on; walk Home, Control Center, Search, Settings, Downloads, Files, Remotes | Every stop is spoken with its role; each page announces itself |

## Needs other apps' accounts

| # | Row | Do | You should see |
|---|---|---|---|
| 15 | P160 | Resume one Continue Watching item in each of Netflix, Prime Video, YouTube and Plex | Playback starts with no extra press. Note which of the four needed a second press — that file is the record |
| 16 | P170 | Resume a Stremio item that has a video id | It plays with no key press |

## When you are done

Tell me which numbers passed and which failed. A failure is fine: nothing here is tagged or published, so a failing
row either gets fixed before beta 2 or moves to "Known limits" in `v0.3.0-beta.2.md`.
