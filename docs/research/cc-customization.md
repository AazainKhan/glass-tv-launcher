# Customizable Control Center for Glass: research

2026-10-11. Sources: local `tvos27-inspo` notes and frames, Glass code (`home/ControlCenter.kt`, `ui/FocusTile.kt`, `settings/SettingsPanel.kt`), Yin's spec `2026-10-11-cc-quickwins-and-addons-ux.md`, and the web. Fetch tools returned thin pages for Apple's own support and HIG pages, so every number below is marked **documented** (a source says it) or **inferred** (reasoned, or seen in a frame). Verify on a real iPhone before quoting.

## Headline finding

tvOS 26/27 Control Center is **not user-customizable** as far as I could find. Searches (9to5Mac, Apple's tvOS guide) show no edit mode and no third-party tiles; the layout is fixed and the tiles take their tint from the screen behind. Third-party customization exists only on iOS 18+. So Glass's edit mode borrows iOS 18's model and re-skins it for a remote, as Apple would on a TV.

What Glass has today: Settings › Control Center toggles tiles on or off (`ccHidden`), a wiggle exists in `FocusTile` (190 ms reverse, ±0.8°) for home-grid move mode, and the grid is Apple's 4x4 (u = 48 dp, gap 8 dp, panel 240 dp; circle 1x1, pill 2x1, large 2x2). No reorder, no resize, no pages, no add-on tiles yet.

## 1. iOS 18 to 26: how editing works (documented unless marked)

| Question | Answer |
|---|---|
| Enter edit mode | Tap **+** (top left) or touch-and-hold empty space ([MacStories](https://www.macstories.net/stories/ios-and-ipados-18-the-macstories-review/4/), [iGeeks](https://www.igeeksblog.com/how-to-customize-ios-18-control-center/)). |
| Tiles in edit mode | Controls wobble ("glowy/jiggle" mode); a **minus badge** sits at each tile's top left; a **handle** sits at the bottom-right corner of resizable tiles. |
| Add | "Add a Control" at the bottom opens a sheet/gallery with search. Lists Apple's controls and every installed app's controls. Third-party items group **under their app** (inferred from Apple's gallery). Tapping adds at the end of the page. |
| Remove | Tap the minus badge. Removing all controls on a custom page removes the page. |
| Resize | Drag the corner handle; snaps to allowed sizes. Not every control allows every size (Calculator cannot go 4 wide) ([WhySoGeek](https://whysogeek.com/ios-26-control-center-customize-pages-guide/)). |
| Pages | Vertical stack of pages, with page icons on the right edge. Defaults: Favorites, Music, Home, Connectivity. Drag a tile past the bottom, or tap the dashed circle, to make a page. Scrub a finger over the icons to switch ([MacStories](https://www.macstories.net/stories/ios-and-ipados-18-the-macstories-review/4/)). |
| Reorder | Drag; neighbours reflow. MacStories calls it "whack-a-mole": tiles jump to unexpected places, so reflow is the main pain point to avoid. |
| Persist | Layout is per device (and iCloud backup). **Settings › Control Center › Reset Control Center** restores defaults (iGeeks). Exit edit mode by tapping empty space. |
| Config | A control with parameters is configured like a widget (press in edit mode) (MacStories). |

## 2. Sizes and layout rules

| Size (cells) | Shape | Typical content | Shows |
|---|---|---|---|
| 1x1 | circle/rounded square | Flashlight, Timer | glyph only |
| 2x1 | pill | Wi-Fi, Focus | glyph + title + value line |
| 2x2 | square | Now Playing | big glyph/art + title + value |
| 4x1, 4x2 | wide | Now Playing, Home | rich content |

Rules: tiles snap to a 4-column grid; a size may be disallowed; a control may be **drawn at 3 sizes**, so title and value text "may not always be visible" (WWDC24 10157 notes via [wwdcnotes](https://wwdcnotes.com/documentation/wwdc24-10157-extend-your-apps-controls-across-the-system/)). Design for glyph-only first. Overflow spills to the next page.

## 3. Third-party controls

- API: `ControlWidget` in WidgetKit; `StaticControlConfiguration` or `AppIntentControlConfiguration`; kinds `ControlWidgetButton` (momentary) and `ControlWidgetToggle` (on/off).
- Parts: **SF Symbol, title, tint, optional value text** (wwdcnotes). Gallery shows `displayName` + `description`. Default name is the app name, which is how attribution works.
- Looks: monochrome symbol, system-drawn container, app-chosen tint on the on-state. Third-party controls show symbol + title, plus a text line when larger (MacStories).
- States: off, on, **value** (`controlWidgetStatus` flashes a short status after an action), **loading** (the system shows `previewValue` until the async provider answers), disabled/unavailable (exists in API; I could not verify visuals). Toggles reload after the action returns, on app request, or on push.
- Same control also works on Lock Screen and Action button. Value text is never visible on Lock Screen.

## 4. tvOS: what you can and cannot do

- Open by holding the TV button (tvOS 27 is "almost instant"). Local frame `03-41`: a big white Power Off 2x2 tile when focused, 2-line pills (Wi-Fi, Do Not Disturb, Audio, Sleep Timer), a row of 4 round buttons, tint from the red page behind.
- **Pages on tvOS are menu-bar icons**, not dots: frame `05-21` shows a top bar (VPN badge, audio waveform, Home glyph in a white disc, Control Center glyph, profile avatar). Selecting Home swaps the column to a HomeKit camera card (about 2x2, with page pips) and scene pills (green tint when active).
- No touch exists. Focus is the only pointer; focus makes a tile white with dark content, scale about x1.21, soft shadow (Sketch notes).

### Touch to remote translation

| iOS touch | Glass remote equivalent |
|---|---|
| Tap + / long-press empty | **Hold Select** on any tile, or a "Edit" pill at the bottom of the panel (visible discoverability). |
| Tap minus badge | Focus a tile in edit mode: **Play/Pause removes** (badge is a hint, not a target); on Fire, Menu/Options works too. |
| Drag to move | **Select lifts** the tile (it scales up, lifts its shadow, keeps wobbling); **D-pad moves** it one cell with neighbours sliding; Select drops. Back cancels the lift and snaps it home. |
| Corner resize handle | While lifted, **Up/Down at an edge cycles size** only if allowed; or a **Size** chip appears under the focused tile (Left/Right = 1x1, 2x1). Never a free drag. |
| Add a Control sheet | Select on the dashed **+** slot at the end of the grid. Sheet is a list (left: apps; right: controls). |
| Page icons | Right-edge dots become focusable (Right from the grid), or LB/RB equivalents (Fast-forward/rewind on Fire remote). |
| Done | **Back** exits edit mode (one press; if a tile is lifted, Back drops it first). |
| Reset | Settings › Control Center › Reset, with a Cancel-first alert. |

## 5. Motion

Apple publishes no numbers for Control Center edit mode (inferred from frames/behaviour). Use these, consistent with Glass's existing curves:

| Moment | Spec |
|---|---|
| Enter edit | 220 ms: tiles start wobbling (190 ms period, +/-0.8°, never in sync: offset by tile index), minus badges scale 0 to 1 over 160 ms, the + slot fades in. |
| Exit edit | 180 ms: wobble settles with 120 ms tween (already in FocusTile), badges scale out, + slot fades. |
| Lift | Reuse focus-in spring (scale x1.21 to x1.28 lifted) and the pre-blurred focus shadow bitmap. |
| Follow focus (move) | Moved tile travels cell to cell in 160 ms, exp-decelerate; neighbours slide 160 ms. Continuity rule: nothing teleports. |
| Resize morph | 220 ms; the tile's bounds animate, glyph stays centred, label crossfades; neighbours reflow in the same 220 ms. |
| Reduce motion | No wobble; badges dissolve; moves become 120 ms dissolves. |

## 6. Ten design rules for Glass

GPU limits: about two blended full-screen passes per frame, no per-frame blur, no animated elevation, under 120 MB PSS, animate only in draw/layout phases.

1. **One baked sheet.** The panel stays one captured, blurred, dimmed bitmap. Edit mode only changes draw state on top; never re-bake while editing.
2. **Hold Select enters edit; a visible "Edit" pill exists as backup.** Nobody on a remote discovers long-press unaided.
3. **Wobble is a transform, not a re-render:** `graphicsLayer` rotation per tile, offset by index, off when Reduce Motion is on.
4. **Remove = Play/Pause, with a minus badge as a legend only.** Confirm nothing: a removed tile goes to the Add sheet and can return, so undo costs nothing.
5. **Lift, move, drop.** Select lifts; D-pad moves one cell; neighbours slide with a short tween; Back drops. No free-form drag.
6. **Fixed size set per tile, declared by the tile:** system tiles 1x1/2x1 (Settings 2x2 is fixed); add-on tiles 1x1 or 2x1 only (matches Yin's spec); a size chip cycles them.
7. **Reflow is deterministic:** packing order top to bottom, left to right, first-fit; a move never reorders tiles it did not touch. This is MacStories's "whack-a-mole" fix.
8. **Pages are few.** Page 1 holds 4x4 cells; extra pages only on overflow; page dots appear only when more than one page exists; dots are focusable on Right.
9. **Add sheet groups by app, search is a phone-typed field** ("Type on your phone"), each row shows glyph, name and a one-line description. Add-on rows carry an "via <Add-on>" grey line.
10. **Persist one small JSON:** ordered list of `{id, size, page}` plus hidden ids; keep `ccHidden` as the migration source. Reset restores defaults with a Cancel-first alert.

Three don'ts:
- **Don't** draw per-tile live blur or translucent stacks in edit mode; use the baked sheet plus flat fills.
- **Don't** let add-ons ship colour art, 2x2 tiles or their own layout; they send glyph key, label, value line (18 chars), state, action.
- **Don't** make any drag or hold-to-repeat the only way to do something; every action needs one-press paths.

## 7. Add-on tile states (Glass draws, add-on supplies data)

Off: glyph, label, "Off". On: white disc behind the glyph (Glass convention). Value: second line text. Loading: dimmed glyph, "..." line, a 1-frame-per-second static shimmer is banned; use a static dot. Error: glyph with a small dot, line "Can't reach <name>" and Select retries. Paused (add-on timed out three times): grey, "Paused". Attribution: a small grey add-on name under the label in the Add sheet and in focus tooltips.

## 8. Integrations people love

How Apple surfaces apps: **iOS Control Center** hosts third-party ControlWidget buttons/toggles (also Lock Screen, Action button). **Widgets** show glanceable data on Home/Lock Screen. **Live Activities** show one ongoing event in the Dynamic Island and Lock Screen (iOS, iPadOS, watchOS, macOS; to my knowledge not tvOS). **tvOS** surfaces third parties mostly through Top Shelf, the Now Playing card in Control Center, and HomeKit pages. On a TV Glass can do the same with remote-only actions. Feasibility reflects my knowledge; items marked (verify) need a quick API check.

| Integration | States and 1-2 actions (Apple-side) | Glass mapping | Fire TV feasibility |
|---|---|---|---|
| Spotify, Apple Music, YouTube Music now-playing | Idle: "Not playing". Playing: art, title, artist, progress. Actions: play/pause, next. iOS CC shows a 2x2/4x2 media card. | 2x2 Now Playing tile (art, title); Select = play/pause, Right/Left = next/prev. Glass already has the notification listener. | **Yes, no API**: Android MediaSession via notification listener works for any app on the stick. Spotify Web API adds playlists but needs Premium (verify). |
| Sonos | Room name, now playing, volume; actions: play/pause, volume. | 2x1 pill: room + track; Select toggles, Up/Down volume. | **Yes, local network** (UPnP/SOAP on port 1400; S2 also has a documented cloud Control API). Needs discovery (mDNS/SSDP). |
| Philips Hue | Scene/room on/off, brightness; actions: toggle, pick scene. | Pills per room/scene; Select toggles; Left/Right brightness. | **Yes, local REST (Hue API v2, HTTPS)** after a one-time bridge link button press and key. |
| Home Assistant | Any entity: on/off, value (temp), scenes, scripts. | Add-on tile per entity; same pill rules; Select calls a service. | **Yes, REST and WebSocket** with a long-lived token typed on the phone. Best generic backend: one add-on covers Hue, Sonos, locks. |
| Ring, Nest doorbells | Camera card: live view, "Someone at the door" notification; actions: view, talk. | 2x2 camera tile; alert slides in a pill; Select opens full-screen. | **Ring: not possible** officially (no public API; unofficial clients break). **Nest: partial**, Google SDM API (paid developer registration, WebRTC streams), a lot of work. Home Assistant can proxy camera snapshots. |
| Plex, Jellyfin | On deck / continue watching; now-playing session; actions: resume, play next. | **Top Shelf add-on** feed (cards with deep links) and a 2x1 "On server" pill. Glass already has a Plex source. | **Yes**: Plex local API (token), Jellyfin REST API (user token), both public. |
| Twitch, YouTube Live | Followed channels live now, viewer count; action: open stream. | Top Shelf row "Live now"; focus card shows viewers. | **Twitch: yes** (Helix API, OAuth; needs a client id). **YouTube Live:** Data API is quota-limited (verify); open via deep link to the YouTube app. |
| Xbox, Steam streaming | Console online, friends playing; actions: wake, stream. | Pill "Xbox: Off/On"; Select opens Xbox/Steam Link app. | **Partial**: Xbox and Steam Link apps exist on Fire TV; presence via Steam Web API (public), Xbox via unofficial/Microsoft sign-in (verify). Wake-on-LAN works locally. |
| Discord | Voice channel members, who's online. | 2x1 pill "3 online" for one server. | **Partial**: public guild widget JSON for opted-in servers; no Discord TV app; no real control. Low value. |
| Weather | Temp, condition glyph; action: forecast. | Pill "18° Clear" (Glass has a weather widget); Select opens forecast panel. | **Yes**: Open-Meteo / NWS public APIs, no key. |
| Sports scores (Live Activity) | Live score, clock, period; tap opens game. | Top-of-panel pill showing the followed team's live score; Select opens game page or app. | **Partial**: ESPN public scoreboard JSON is unofficial (ToS risk); licensed APIs paid. Add-on supplies data, Glass draws score pill. |
| Tailscale, VPN | Connected/Off, exit node; toggle. | 2x1 pill (Yin's VPN spec exists); Select opens the app. | **Yes**: status via ConnectivityManager TRANSPORT_VPN, no root. Tailscale's own toggle needs its app or API key (verify); Tailscale supports Android TV and Fire TV. |
| Uber Eats, delivery Live Activities | Order status, ETA, driver position (iOS Live Activity). | Would be a pill "Arriving in 12 min". | **Not possible** directly: no public API and the app isn't on Fire TV; phone-push bridging is out of scope. |

## 8b. Niche integrations (power users)

### Lead: Claude Code on your TV

Verified against the official docs ([hooks](https://code.claude.com/docs/en/hooks), [Remote Control](https://code.claude.com/docs/en/remote-control), [Agent SDK](https://code.claude.com/docs/en/agent-sdk/overview)).

**What the docs say**
- **Hooks can post to a URL.** Handler type `http` POSTs the event JSON (session_id, cwd, tool_name, tool_input, transcript_path) to a URL, with headers and env-var interpolation. Events include SessionStart, UserPromptSubmit, PreToolUse, PermissionRequest, Notification (matchers `permission_prompt`, `idle_prompt`), PostToolUse, Stop, SubagentStart/Stop, TaskCompleted, SessionEnd.
- **A hook can answer for the user.** `PermissionRequest` accepts `hookSpecificOutput.decision.behavior` = `allow` or `deny` (allow may also rewrite the input); `PreToolUse` takes `allow`/`deny`/`ask`. The default timeout is **600 s** for http hooks, so a bridge can hold the request open while a person reaches the remote. On timeout or an unreachable server a PreToolUse call "falls through to the normal permission flow" (the terminal prompt still works), unless `onFailure: block` is set. That is the safe default: the TV can never strand an agent.
- **Notification has no decision control**; it is for status only (good for "waiting for you" cards).
- **Remote Control** (phone app and claude.ai/code) is outbound-only HTTPS to Anthropic, with no inbound ports and **no documented third-party API**. Pro/Max/Team/Enterprise only, API keys not supported. It is for Anthropic's own clients; a TV cannot join it. Do not build on it.
- **Agent SDK** (Python/TypeScript, runs the Claude Code binary) can start sessions, resume them, and has a permissions model and hooks. Its terms: third parties may not offer claude.ai login or its rate limits, so it needs an API key, and products must not be branded "Claude Code". Use it only if the bridge itself should *start* agents (the "message your agent" path); for a user's own existing sessions, hooks suffice. `claude -p` headless mode is the lighter alternative.

**Architecture (honest and small)**
1. A ~100-line bridge on the Mac (Node or Python) listens on the LAN, bearer token required, bound to the home subnet.
2. User-level hooks (`~/.claude/settings.json`) of type `http` send SessionStart, PreToolUse/PermissionRequest, Notification, Stop, SessionEnd to the bridge. The bridge keeps a session table.
3. The Glass add-on polls or holds a WebSocket/SSE to the bridge (Fire TV has plain HTTP, so this needs nothing special) and supplies tile data.
4. A permission prompt: bridge holds the hook's HTTP response open, pushes "Bash: npm test?" to the TV, the remote answers Allow/Deny, the bridge returns the JSON decision.
5. Messaging: voice or phone text goes to the bridge, which runs `claude -p --resume <id> "<text>"` (queued message, new turn) or the SDK. Mid-turn injection into a live interactive session is not supported by a hook; it lands as the next prompt.

**Tiles**

| Tile | States | Remote |
|---|---|---|
| Agents pill (2x1) | "3 agents" with working (spinner dot), "1 waiting" (amber, white disc), idle, "Mac offline" | Select opens a panel with one card per session: project, task line from the last user prompt, current tool, elapsed |
| Permission pop-in | Slides over the panel when `PermissionRequest` arrives: tool, command, cwd | Select = Allow once, Back = Deny, Play/Pause = "Always allow" (writes `updatedPermissions`) is risky, so omit in v1 |
| Reply chip | Last assistant line from the transcript | Mic button = voice (Fire remote Alexa key can be captured), or "Type on your phone" |

**Feasibility verdict: feasible, with caveats.** Status cards and approve/deny: **yes**, public documented hooks, small bridge, safe fail-through. Messaging: **partly**: it queues a new turn via headless/SDK, no live typing into an open terminal session. Remote Control: **not possible** for third parties. Risks: approving shell commands from a couch needs the command text visible and legible at 10 ft (truncate, show cwd), requires an unguessable token, and must never auto-approve on timeout. Security default: Deny wins, Allow needs a deliberate press. (The Agent Manager's `.superpowers/pair/board.md` could feed the same tile for our own agents.)

### More niche integrations

| Integration | Tile states | Actions on a remote | Feasibility |
|---|---|---|---|
| GitHub PR and CI | "2 PRs need review", CI green/red/running; PR card: title, checks | Select opens PR card; Select = Approve, Play/Pause = Merge (confirm alert, Cancel first) | **Yes**: GitHub REST/GraphQL with a fine-grained token typed on the phone; polling or webhooks via the bridge. |
| Homelab (Proxmox, Unraid, HA energy) | CPU/RAM bars, array healthy/degraded, power now in W | Select: start/stop a VM or container (confirm); energy card read-only | **Yes**: Proxmox REST (API token), Unraid GraphQL/Connect API, HA REST for energy. LAN only. |
| OctoPrint / Bambu printer | Idle, printing 63% with ETA, error; camera snapshot | Select pauses/resumes; Back closes camera | **OctoPrint: yes** (REST + MJPEG). **Bambu: partial**: local MQTT works in LAN mode with an access code; the camera is a proprietary stream (verify). |
| Tesla / EV charge | Plugged, charging 72% to 80%, ETA, locked | Select starts/stops charging, lock | **Partial**: Tesla Fleet API needs developer registration and OAuth; third-party aggregators exist. Charge status read-only is the realistic scope. |
| Plex / Jellyfin server stats | "2 watching", who and what; bandwidth | Select shows sessions; Play/Pause stops a stream (confirm) | **Yes**: Plex `/status/sessions`, Jellyfin `/Sessions`, both public. |
| Discord voice presence | "3 in #lounge" with avatars | Select opens a list; no join action | **Partial**: guild widget JSON only for opted-in servers; real presence needs a bot you run. |
| Uptime Kuma alerts | "All 14 up" green, "2 down" red with names | Select lists failed monitors; Play/Pause pauses a monitor | **Yes**: status page JSON/API, Prometheus metrics endpoint, or push hook to the bridge. |
| Live agent-ops dashboard (ours) | Board counts: open, in review, blocked; stick lock held by whom | Select opens the board; Allow/Deny from the bridge | **Yes**: reads `board.md` via the same bridge. Unique to this project. |

## 8c. Rankings

### Top 8 overall (appeal x feasibility, each 1-5)

| Rank | Integration | Appeal | Feasibility | Score |
|---|---|---|---|---|
| 1 | Now Playing (Spotify, Apple Music, YT Music) via MediaSession | 5 | 5 | 25 |
| 2 | Home Assistant | 5 | 4 | 20 |
| 3 | **Claude Code on your TV** (status + approve/deny) | 5 | 4 | 20 |
| 4 | Plex and Jellyfin (Top Shelf, who's watching) | 4 | 4 | 16 |
| 4 | Philips Hue | 4 | 4 | 16 |
| 4 | GitHub PR and CI | 4 | 4 | 16 |
| 7 | VPN and Tailscale status | 3 | 5 | 15 |
| 7 | Weather | 3 | 5 | 15 |

Close behind: Sonos 12, sports 12, Twitch 12, Uptime Kuma 12 (3x4), homelab 12, OctoPrint 12 (3x4), agent-ops 12, Nest 10, Tesla 9, Steam/Xbox 9, Discord 6, Ring 5, Uber Eats 0.

### Top 5 niche (the "nobody else has this" list)

| Rank | Integration | Appeal | Feasibility | Score |
|---|---|---|---|---|
| 1 | Claude Code on your TV | 5 | 4 | 20 |
| 2 | GitHub PR and CI approve/merge | 4 | 4 | 16 |
| 3 | Homelab dashboards (Proxmox, Unraid, HA energy) | 4 | 3 | 12 |
| 3 | OctoPrint printer progress and camera | 3 | 4 | 12 |
| 3 | Uptime Kuma alerts | 3 | 4 | 12 |

A shared "bridge" add-on (token, LAN URL, typed once on the phone) can serve the first, second, fifth and agent-ops tiles with one code path.

## 9. Reference images

Local frames (under `tvos27-inspo/screenshots/`):
- `halfmanhalftech-tvos27-rc/03-41_control-center-over-podcasts.jpg`: tvOS 27 CC: white Power Off tile, 2-line pills, round buttons, tint from the page.
- `stephenrobles-top10/05-21_control-center-homekit-cameras.jpg`: HomeKit page with camera card and scene pills; menu bar icons act as pages.
- `stephenrobles-top10/05-25_control-center-camera-feed.jpg`: full camera feed from the CC.

Web (URLs from MacStories' iOS 18 review; not downloaded):
- https://cdn.macstories.net/tuesday-10-sep-2024-18-51-52-1725987127476.png: new iOS 18 Control Center with power button.
- https://cdn.macstories.net/tuesday-10-sep-2024-18-53-44-1725987228997.png: configuring a widget vs a control: same flow.
- https://cdn.macstories.net/tuesday-10-sep-2024-19-05-26-1725987940079.png: large Now Playing on the music page vs compact on main.
- https://cdn.macstories.net/tuesday-10-sep-2024-19-09-55-1725988201592.png: a custom HomeKit page.
- https://cdn.macstories.net/tuesday-10-sep-2024-19-12-22-1725988364943.png: the Controls gallery, built-in and third-party.
- https://cdn.macstories.net/tuesday-10-sep-2024-19-13-23-1725988414150.png: configuring a third-party control.
- https://cdn.macstories.net/001/2016-09-08-205931.jpeg: iOS 10 multi-page Control Center (the old model).
- https://www.igeeksblog.com/wp-content/uploads/2024/06/Tap-on-minus-icon-to-remove-control-from-control-center.jpg: minus badge in edit mode.
- https://www.igeeksblog.com/wp-content/uploads/2024/06/Change-control-size-with-iOS-18-Control-Center.jpg: corner resize handle.
- https://www.igeeksblog.com/wp-content/uploads/2024/06/Add-new-Control-Center-screen-on-iPhone.jpg: adding a page.

Other sources: [WWDC24 10157](https://developer.apple.com/videos/play/wwdc2024/10157/), [WhySoGeek iOS 26 guide](https://whysogeek.com/ios-26-control-center-customize-pages-guide/), [9to5Mac tvOS 26 customization](https://9to5mac.com/2026/05/06/tvos-26-has-added-five-new-ways-to-customize-your-apple-tv-4k/) (no CC editing listed).
