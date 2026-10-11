# Glass gaming pillar: research and design

2026-10-11. Read-only research. Local inputs: `fire-tv-demands.md` (#17), `CC-CUSTOMIZATION-RESEARCH.md`, the add-ons spec (branch `docs/addons-spec`), the README, `scripts/remote-keepalive`. Web search returned thin results for Reddit (none surfaced), Amazon's developer pages (one 404) and Apple's HIG, so each claim below is tagged **documented** (a source says it), **anecdote** (one forum post) or **inferred** (reasoned, must be tested on the stick). Nothing here was run on the device.

Hardware note: the stick in this workspace (AFTKRT "karat", MT8696T, 2 GB, Fire OS 8 / API 30) is, by Amazon's spec listing as relayed by search results, the **Fire TV Stick 4K Max (2nd gen, 2023)**: 4 cores at 2 GHz, GE9215 GPU, Wi-Fi 6E ([Expert Reviews](https://www.expertreviews.co.uk/media-streamers/amazon-fire-tv-stick-4k-max-2nd-gen-review), [Amazon specs](https://developer.amazon.com/docs/fire-tv/device-specifications-fire-tv-stick.html)). `CLAUDE.md` calls it "4K 2nd Gen"; vendor support lists name both, so both appear below.

## 1. What gamers want on a TV (synthesis)

Gaming forums and reviews converge on a short list. None of it needs Glass to run games; it needs Glass to remove friction.

1. **Pick up and play in two button presses.** Controller on, game running, with no hunting through five apps.
2. **The controller just works.** Stays paired, shows battery, reconnects after reboot. Pairing drops are the top complaint class ([fire-tv-demands #17](file:///Users/aazain/Documents/glass-launcher/docs/research/fire-tv-demands.md); RetroPie/Lakka threads show the same "bond lost after reboot, power-cycle the pad" pattern on other Linux/Android boxes, anecdote: [RetroPie](https://retropie.org.uk/forum/post/199677)).
3. **Low, steady latency**, and honesty about when the network is the problem. A dying controller or weak Bluetooth is routinely mistaken for lag ([Android Central](https://www.androidcentral.com/best-gaming-controllers-amazon-fire-tv), [Steam Community](https://steamcommunity.com/app/353380/discussions/4/4208120159994173527)).
4. **One library** instead of siloed stores: Game Pass, GeForce NOW, Steam, my PC, retro.
5. **Social glue**: who is online, can I join, did I just unlock something.

Apple's pattern to borrow: a game-specific "Continue Playing" row and Game Center overlay, with a prompt to resume when a controller connects ([Apple Arcade guide](https://support.apple.com/en-nz/guide/tv/atvbe7892252/15.0), [Game Center HIG](https://developer-rno.apple.com/design/human-interface-guidelines/technologies/game-center/dashboard)). I found no source showing Continue Playing inside Top Shelf; treat that as a design choice, not a copy.

## 2. Cloud and streaming services

Fire TV caps: GeForce NOW tops out at 1080p60 SDR, H.264, stereo on every Fire TV, regardless of tier ([AFTVnews](https://www.aftvnews.com/tag/nvidia-geforce-now), [Nvidia blog](https://blogs.nvidia.com/blog/geforce-now-thursday-amazon-fire-tv-app/)).

| Service | Runs on this stick today? | How | Glass integration | Confidence |
|---|---|---|---|---|
| Xbox Cloud Gaming | Yes, official Xbox app on 4K / 4K Max since July 2024; needs Game Pass Ultimate and a BT pad ([Pure Xbox](https://www.purexbox.com/news/2024/07/xbox-gaming-now-officially-available-on-amazon-fire-tv-stick), [SlashGear](https://www.slashgear.com/1403283/how-to-play-xbox-game-pass-games-on-fire-tv)). Silk + xbox.com/play was the older beta route; unconfirmed now. | Launch the app package | Library row entry; launch by package. Deep-linking to a title is **unverified** (check the app manifest on the stick with `dumpsys package`). | App: documented |
| GeForce NOW | Yes: 4K Max 2nd gen (Fire OS 8.1.6.0+), 4K Max 1st gen, 4K Plus; an AFTVnews listing also names the 2nd-gen 4K. Released Feb 2026. | Amazon Appstore app | Library entry + latency HUD (section 7) | Documented |
| Amazon Luna | Yes via Fire TV app; Luna controller talks to Luna over Wi-Fi, bypassing the stick, so lowest latency ([AFTVnews via Android Central](https://www.androidcentral.com/best-gaming-controllers-amazon-fire-tv)). Official model list not found. | Luna app | Library entry | App: documented, models: unverified |
| Boosteroid | Yes, Appstore app, 4K and 4K Max named ([Boosteroid](https://boosteroid.com/blog/2025/11/25/boosteroid-cloud-gaming-now-available-on-fire-tv-os/)) | App | Library entry | Documented |
| Steam Link | Yes, official Appstore app, list names 4K Max, 4K Max 2nd gen and 4K 2nd gen ([Steam Community](https://steamcommunity.com/app/353380/discussions/4/4629231855272467063)). Quality is mixed: stutter, input lag and BT lag reports on 4K Max (anecdote). | App | Library entry; host PC wake (below) | App: documented; quality: anecdote |
| Moonlight + Sunshine/Apollo | Yes, Android client installs on Fire OS; supports Sunshine hosts ([README](https://github.com/moonlight-stream/moonlight-android)). One user measured about 10 ms decode on a 4K Max vs about 2 ms on Shield / Apple TV (anecdote, [TechEnclave](https://techenclave.com/t/sunshine-moonlight-a-great-free-option-for-streaming-games-to-tv-ipad-phone/264292)). Hardware HEVC decode is the setting that matters. | Sideload or Appstore build | Per-game shortcuts; see below | Client: documented; latency: anecdote |
| PlayStation Remote Play / Chiaki-ng | **Unproven.** chiaki-ng lists an Android build but Fire TV and Android TV are not mentioned ([repo](https://github.com/seanwhsu/chiaki-ng)). I found no Sony app for Fire TV. | Sideload test needed | Only after a test on the stick | Unverified |

**Moonlight integration.** The v8.0 changelog says the Android client improved game shortcuts and launches directly from the Android TV home screen ([F-Droid page](https://f-droid.org/ru/packages/com.limelight/), via search). I could not find the exact intent action or extras in a source. Next step is reading `AndroidManifest.xml` and shortcut code in the repo, or `adb shell dumpsys package com.limelight` on the stick. If shortcuts are exposed, Glass can list each Sunshine app (Desktop, Steam Big Picture, named games) as a tile. Moonlight's own Wake-on-LAN is not documented in the README I fetched; so Glass should send the magic packet itself: a UDP broadcast of 6x`FF` plus 16 repeats of the MAC to port 9 is a few lines of Kotlin and needs only the PC's MAC (store it in the add-on's settings, or read it from Moonlight's host list). Sequence: Select on "PC games" -> send WoL -> poll the Sunshine port (47989) until it answers -> launch Moonlight. Waking from full shutdown needs BIOS WoL; sleep/hibernate is the reliable case (inferred).

**Steam Link and Apollo.** Steam Link has no documented deep link for a game; launch the app and keep Steam as one tile. Apollo (a Sunshine fork) is just another Moonlight host; I found no Fire-specific data.

## 3. A unified Games row

**Sources and data (feasible without the platforms' blessing):**

| Source | What you get | How | Auth |
|---|---|---|---|
| Installed Android games | Packages in category `GAME` plus the cloud apps above | `PackageManager`, `ApplicationInfo.category` | none |
| Recently played / continue | Own launch log, plus UsageStats (Glass already asks for `GET_USAGE_STATS`) | Glass records launches; UsageStats fills in | none |
| Steam | Owned and recent games (last ~2 weeks), by public profile or your own key ([Valve wiki](https://developer.valvesoftware.com/wiki/Steam_Web_API)) | Steam Web API key + SteamID, entered by phone QR (Glass already does this for TMDB) | user key, no OAuth |
| Moonlight hosts | Sunshine app list | Sunshine REST app list needs pairing; or Moonlight shortcuts | pairing PIN |
| Xbox | Library via Game Pass catalog is possible; personal library is gated | see section 5 | OAuth/proxy |

**Cover art.** SteamGridDB: free API key, v2, gives grids (box art), heroes (wide), logos, with author attribution; terms were not found in my search, so read them before shipping ([npm client](https://npmjs.org/package/steamgriddb)). IGDB needs a Twitch developer account (2FA); about 4 requests/s on the free tier; commercial status is contested, check [IGDB docs](https://api-docs.igdb.com) ([forum](https://discuss.dev.twitch.com/t/commercial-use-of-igdb-api/23567)). Recommendation: Steam CDN art for Steam games (keyed by appid, no extra account), SteamGridDB for the rest, cached on disk through the existing P81 image gate. Glass should fetch art itself, as the add-ons spec already requires.

**Top Shelf for games.** The existing Top Shelf contract (FeaturedFeed/FeaturedItem, 30 items, 1280 px) fits. A "Games" shelf shows: resume (last game, with last-played time), friends playing, "PC is awake" status, new Game Pass additions. When a controller connects and a game was paused within the last hour, offer "Resume <game>" (Apple's behaviour, documented above).

## 4. Controllers

**Support matrix.**

| Pad | Fire TV | Notes |
|---|---|---|
| Xbox Wireless | Works over Bluetooth; named by Xbox app | Pairing from Settings > Controllers |
| DualSense / DualShock 4 | Named supported by the Xbox app; "quick to pair" ([Android Central](https://www.androidcentral.com/best-gaming-controllers-amazon-fire-tv)) | Haptics/adaptive triggers: no (see below) |
| 8BitDo | Works in Xbox/BT mode; reconnect-after-reboot bugs are well known elsewhere (firmware, mode, bond) (anecdote) | Update firmware |
| Luna controller | Wi-Fi to Luna | Not a normal gamepad for local apps |

**Staying paired.** Hypothesis, **inferred**: Fire OS's `AmazonAutoPairService` (the thing Glass already neutralises for the remote, see `scripts/remote-keepalive`) may also disturb gamepad bonds or flip the stick into pairing mode after boot. This is unverified; the demands file marks it "Partial". The cheap experiment: after a reboot, `adb shell dumpsys bluetooth_manager` and `logcat -b all | grep -i -E "bond|autopair"` with a paired pad, with the keepalive module on and off. Known causes on other platforms are a bond not completed (pad's passcode/trust step), controller still connected at reboot, and old firmware (anecdote). Design: a Magisk `glass-pad-keepalive` module, same pattern as the remote one (watch the log, act when the service arms a timer), only **if** the experiment shows the same cause. Otherwise the fix is a Glass-side "reconnect" action (Bluetooth `connect` on bonded HID devices at boot via su).

**Haptics.** `InputDevice.getVibrator()` is deprecated in API 31 for `getVibratorManager()` ([Android reference](https://developer.android.com/reference/android/view/InputDevice.html)); on API 30 the old call exists. Whether Fire OS routes rumble to Xbox/DualSense over HID is the open question behind demand #17, and DualSense haptics and adaptive triggers use Sony's proprietary reports that Android does not expose (inferred). Glass should not promise rumble; it can offer a "Test vibration" button and report the result.

**Battery tiles.** `InputDevice.getBatteryState()` exists in the current docs but I could not confirm it on API 30 (I believe it arrived in Android 12). On API 30 the route is the hidden `BluetoothDevice.getBatteryLevel` or `dumpsys bluetooth_manager`/`dumpsys battery`-style reads through Glass's su grant (inferred). Reads can hang for seconds on some pads (an AOSP commit cites the 8BitDo SN30 Pro+, [source](https://android.googlesource.com/platform/frameworks/native/+/22c330ce3b)), so poll off the main thread and at most every few minutes. Low battery at 15% triggers a toast.

**Toast and navigation.** A "Controller connected" toast uses `InputManager.InputDeviceListener` (no permission). Navigating Glass with a pad already works as DPAD/A/B key events for most pads; Glass should map A=Select, B=Back, Y=Menu/Options, X=Search, Guide (Xbox button, `KEYCODE_BUTTON_MODE`) = Control Center. The Guide button may be consumed by the Xbox app or by the system before Glass sees it; Glass's `RemoteKeysService` accessibility service can see key events and is the place to try (inferred; test).

## 5. Friends and presence

| Network | Feasibility | Auth / gotcha |
|---|---|---|
| Steam | Good. `GetPlayerSummaries` returns persona state and current game for up to 100 IDs; friends list needs a public profile, or your own key for your own data ([Valve wiki](https://developer.valvesoftware.com/wiki/Steam_Web_API)). No join-game over the web API; "join" would be a Steam Link launch. | User API key; no OAuth |
| Xbox | Hard. Official Xbox Live services are for certified titles ([Microsoft](https://learn.microsoft.com/gaming/xbox-live)); third-party wrappers (xbl.io, community Python libs) exist and are a grey area; the free tier figure of 150 requests/hour is secondhand. | Microsoft sign-in, proxy terms unverified |
| Discord | Poor for reading others' presence. Docs cover setting your own rich presence and the Social SDK for games ([Discord](https://docs.discord.com/developers/rich-presence/overview)); I found no sanctioned way for a launcher to list friends' activity. | Skip |
| Achievements | Steam: `GetPlayerAchievements`-style calls can be polled per game (not verified here). Xbox: via the same grey proxies. A toast on unlock needs polling every minute while a game runs. | Cost/benefit weak; later |

Recommendation: **Steam-only presence in v1** ("Alex is playing Hades", tap to open Steam Link). Xbox and Discord stay out until a legal, stable path exists.

## 6. Performance and a Game Mode

What Glass controls, on the 120 MB PSS / 2 GB budget:

- **Pause Glass background work** when a game (or any full-screen game package) is foreground: stop featured refreshes, Aerial, weather, image decode, add-on polls. Glass already idles near 0% CPU; this makes it a rule, and frees memory for lmkd (the existing `glass-tuning` module sets 12 cached processes; Game Mode could temporarily lower it).
- **ALLM**: the 4K Max advertises Auto Low Latency Mode, but it needs an HDMI 2.1-capable TV, a good cable, and Fire display settings not forced to manual ([AFTVnews](https://www.aftvnews.com/?p=37383) via search; secondary Japanese blog). Whether apps can request ALLM on Fire OS is unverified; a Glass tile can only show "TV game mode: on/off" if the platform exposes it. Test with `dumpsys display` / `dumpsys hdmi_control` on the stick.
- **Network quality tile**: ping the gateway and 1.1.1.1, show Wi-Fi band, link speed, RSSI, jitter; this is plain Android (`WifiManager`, ICMP via `InetAddress.isReachable` or a TCP connect). Highlight 2.4 GHz and weak signal, the usual cause of "lag".
- **Latency HUD** during streaming: Glass cannot read another app's stream stats. It can overlay (with the SYSTEM_ALERT_WINDOW grant PhairPlay already uses) a small network HUD: ping, jitter, Wi-Fi rate, controller battery. Moonlight and Steam Link have their own stats overlays; Glass should say "turn on Moonlight's stats" in a tip, not fake it.
- **Wired Ethernet** advice: strongest single fix reported for Steam Link (anecdote). Show a tile hint when on Wi-Fi.

## 7. Retro and emulation (neutral)

RetroArch is on the Amazon Appstore for Fire TV ([AFTVnews](https://www.aftvnews.com/retroarch-game-emulator-frontend-is-now-officially-available-on-fire-tv-devices/), [Liliputing](https://liliputing.com/2021/07/retroarch-is-now-available-from-the-amazon-appstore-retro-gaming-on-fire-tv-and-tablet-devices.html)). Emulators are legal software; legality of ROMs depends on how you obtained them and where you live, and guides recommend using games you own ([HowToRetro](https://www.howtoretro.com/emulation/retroarch-fire-tv-stick-setup/)). Glass's stance: it **ships no cores, no ROMs, no ROM-site links**, and its UI says "your own games". Practical scope: 8/16/32-bit and PS1 are reasonable on this class of stick; PSP/GameCube/Wii/PS2 are not (anecdotes, 2021 era). Integration: list RetroArch (and ES-DE-style frontends) as a launcher; "recent" comes from UsageStats only. Content-level shortcuts are skipped. Optional later: Libretro thumbnails for art, not required.

## 8. Ranked ideas (would make a gamer switch x feasibility)

Score 1-5 each; rank by product.

| # | Idea | Switch | Feasible | Notes |
|---|---|---|---|---|
| 1 | **Games row + Continue Playing** (installed, cloud, Steam Link, Moonlight) | 5 | 5 | Local data; pure Glass |
| 2 | **Controller toast + battery tile + low-battery warning** | 4 | 4 | InputDeviceListener; battery on API 30 needs su/hidden API |
| 3 | **Guide button -> Control Center / Glass; pad navigates Glass** | 5 | 3 | Depends on seeing `BUTTON_MODE`; test |
| 4 | **Wake PC + launch Moonlight** (one tile) | 5 | 4 | WoL is easy; Moonlight launch route needs the intent check |
| 5 | **Controller keepalive (re-pair across reboot)** | 5 | 3 | Needs the experiment; reuses remote-keepalive design |
| 6 | **Network quality tile + Wi-Fi/Ethernet hint** | 3 | 5 | Pure Android |
| 7 | **Game Mode** (pause Glass work, show ALLM status) | 3 | 4 | Pause is easy; ALLM unknown |
| 8 | **Cover art via Steam CDN + SteamGridDB** | 4 | 4 | Gate through image pipeline; check SGDB terms |
| 9 | **Steam friends online + "playing now" tile** | 3 | 4 | Steam only, public profiles |
| 10 | **Resume prompt when a pad connects** | 3 | 4 | Apple-style; follows from #1 and #2 |

Honorable mentions: Game Pass "new this week" Top Shelf (catalog is public), an add-on "Playtime stats" tile, achievements toasts (weak), Xbox/Discord presence (blocked).

Add-on mapping: #1, #8, #10 as a **Games Home row / Top Shelf provider** add-on; #2, #6, #7, #9 as **Control Center tiles**; #3 and #5 as Glass-core / Magisk modules (they need accessibility or root, which the add-ons spec says an add-on must not proxy). The Games row can ship in core since it is the pillar's centre.

## 9. Honest limits of a $50 stick

| Limit | Why |
|---|---|
| No native AAA, no local PC-class gaming | 2 GB RAM, GE9215 GPU; cloud or LAN streaming only |
| 1080p60, H.264, stereo on GeForce NOW | Platform cap on all Fire TV ([AFTVnews](https://www.aftvnews.com/tag/nvidia-geforce-now)) |
| Streaming quality is Wi-Fi and BT dependent | One radio, no Ethernet in the box (adapter needed); Bluetooth range reports (anecdote) |
| Decode latency is higher than a Shield | About 10 ms vs 2 ms measured by one user |
| Haptics, DualSense triggers, gyro | OS does not route them; do not promise |
| ALLM needs an HDMI 2.1 TV | Not a stick limit |
| PS2/GameCube/Wii emulation | Too slow (anecdote) |
| Xbox and Discord friends | No sanctioned API for launchers |
| Glass's own cost during games | Must go to near-zero; every cached process competes with the game under lmkd |
| PlayStation Remote Play | No known Fire TV client |

## 10. Open questions to resolve on the stick (all cheap)

1. Does `BUTTON_MODE` (Guide) reach `RemoteKeysService` with Xbox and DualSense pads connected?
2. After a reboot, does the pad bond survive with the remote keepalive on, and what does the log say?
3. Does the Moonlight package export a launch intent or shortcuts, and what extras?
4. Does the Xbox app have a deep link scheme for titles?
5. Does `dumpsys` show ALLM state when a game runs?
6. Is rumble delivered (`InputDevice.getVibrator().hasVibrator()` for each pad)?
7. Read SteamGridDB and IGDB terms before shipping art.

## Sources

[Xbox on Fire TV (Pure Xbox)](https://www.purexbox.com/news/2024/07/xbox-gaming-now-officially-available-on-amazon-fire-tv-stick) | [SlashGear Xbox guide](https://www.slashgear.com/1403283/how-to-play-xbox-game-pass-games-on-fire-tv) | [GeForce NOW on Fire TV (AFTVnews)](https://www.aftvnews.com/tag/nvidia-geforce-now) | [Nvidia blog](https://blogs.nvidia.com/blog/geforce-now-thursday-amazon-fire-tv-app/) | [Boosteroid](https://boosteroid.com/blog/2025/11/25/boosteroid-cloud-gaming-now-available-on-fire-tv-os/) | [Steam Link threads](https://steamcommunity.com/app/353380/discussions/4/4629231855272467063) | [Moonlight Android](https://github.com/moonlight-stream/moonlight-android) | [TechEnclave Moonlight latency](https://techenclave.com/t/sunshine-moonlight-a-great-free-option-for-streaming-games-to-tv-ipad-phone/264292) | [chiaki-ng](https://github.com/seanwhsu/chiaki-ng) | [Android Central controllers](https://www.androidcentral.com/best-gaming-controllers-amazon-fire-tv) | [InputDevice reference](https://developer.android.com/reference/android/view/InputDevice.html) | [Valve Steam Web API](https://developer.valvesoftware.com/wiki/Steam_Web_API) | [IGDB commercial-use thread](https://discuss.dev.twitch.com/t/commercial-use-of-igdb-api/23567) | [steamgriddb npm](https://npmjs.org/package/steamgriddb) | [Discord Rich Presence](https://docs.discord.com/developers/rich-presence/overview) | [Xbox Live docs](https://learn.microsoft.com/gaming/xbox-live) | [RetroArch on Fire TV](https://www.aftvnews.com/retroarch-game-emulator-frontend-is-now-officially-available-on-fire-tv-devices/) | [Apple Arcade on Apple TV](https://support.apple.com/en-nz/guide/tv/atvbe7892252/15.0) | [Game Center dashboard HIG](https://developer-rno.apple.com/design/human-interface-guidelines/technologies/game-center/dashboard) | [4K Max 2nd gen review](https://www.expertreviews.co.uk/media-streamers/amazon-fire-tv-stick-4k-max-2nd-gen-review)
