# Customizable Control Center (P83)

Yin, 2026-10-11. Design only. Inputs:
- `firestick-unlocked/tvos27-inspo/CC-CUSTOMIZATION-RESEARCH.md` (the research);
- Yang's `docs/superpowers/specs/2026-10-11-addons-design.md` (552f9f5);
- my `2026-10-11-cc-quickwins-and-addons-ux.md`;
- the tvos27-guidelines skill.

tvOS has no Control Center editing, so this takes iOS 18's model and rebuilds it for a five-button remote.

## 0. Constraints that shape everything

- **The remote:** D-pad, Select, Back, Home, Menu (≡) and Play/Pause. There's no touch, no drag and no corner handles. Every action needs a one-press path.
- **GPU, from the research:** about two blended full-screen passes per frame. That means no per-tile blur, no animated elevation, and no re-bake while editing. **Memory:** Glass ≤ 120 MB PSS with Control Center open.
- **Visual rules:** one material through every animation; glass tinted by what's behind (P76 panelSheet); a focused tile is white with dark content; no text shadows.

## 1. The model

- **Grid:** 4 columns per page; cell u = 48 dp, gap 8 dp (unchanged). A page holds up to **6 rows**. The column already scrolls the focused tile into view, and a 7th row goes to the next page.
- **Sizes:** each control declares which of these it allows.

| Size | Shape | Content |
|---|---|---|
| 1×1 | circle | glyph only (a value may replace the glyph, e.g. "18°") |
| 2×1 | capsule pill | glyph in a disc + label + value line |
| 2×2 | rounded square (r 26 dp) | big glyph or art + label + value |
| 4×1 | full-width pill | built-ins only (Launcher Settings) |

  Add-ons may use **1×1 and 2×1 only**. Only Glass built-ins may use 2×2 and 4×1.
- **Pages:** an ordered list. Each page has an icon in the menu bar (section 4). There's always at least one page, "Controls". A page left empty when edit mode ends is deleted.
- **What's stored:** per page, the **ordered list of `{id, size}`**. Positions are never stored; they're recomputed by the packing rule below. That makes the layout deterministic and keeps the stored file tiny.

### Packing rule: cursor-based, next-fit, never backfill

```
cursor = (row 0, col 0); occupied = {}
for tile in page.tiles (in order):
    pos = first cell at or after cursor, in reading order, where tile fits
          (inside 4 columns, no overlap with occupied)
    place tile at pos; mark its cells occupied
    cursor = (pos.row, pos.col + tile.width)        // the cursor never moves backwards
```

- **The guarantee:** reordering one tile only shifts the tiles between its old and new place in the list. A tile can never jump ahead to fill a hole earlier in the grid. That jump, called "backfill", is iOS's "whack-a-mole" problem.
- **Gaps:** a 2-wide tile that doesn't fit at col 3 starts the next row. The hole stays, shown in edit mode as a dashed empty cell, and the user fills it by moving a 1×1 there.
- **A 2×2 still works:** at (0,0) it occupies two rows, and later pills wrap beside it because overlap is checked. That reproduces today's layout exactly:

```
default "Controls" page, packed:
row0  [ Settings 2×2 ][ Wi-Fi     ]
row1  [             ][ Bluetooth  ]
row2  [ Sleep Timer ][ VPN        ]
row3  [ Launcher Settings  4×1    ]
row4  ( Game )( Theme )( Saver )( Switcher )
row5  ( AirPlay )( Perf )( Clean Up )( Restart )
```

## 2. Edit mode (remote mapping)

| Action | Press | Notes |
|---|---|---|
| Enter | **Hold Select ≥ 600 ms** on any tile, or Select the **Edit** pill at the end of the last page | The pill is there for discoverability. Hold-Select is reserved for this everywhere in Control Center (see 7.3) |
| Lift | **Select** on a focused tile | It rises (×1.21 to ×1.28) and keeps wobbling. Hints change to "Move · Drop" |
| Move | **D-pad** while lifted | Left/Right = one place earlier or later in the list. Up/Down = the place whose packed slot is nearest in the row above or below. Neighbours slide |
| Drop | **Select** | Commits. **Back** while lifted cancels and sends the tile back to where it started |
| Move to another page | While lifted, press **Up** from the top row into the menu bar, then Left/Right over the page icons | The tile is added at the end of that page; the page shows as you pass its icon |
| Resize | **Menu (≡)** on a focused tile, not lifted | Cycles the control's allowed sizes in order; neighbours repack. Tiles with one size show no Menu hint |
| Remove | **Play/Pause** on a focused tile | No confirm, because the gallery brings it back. The minus badge is only a legend. Fire TV Settings (2×2) can't be removed: its badge is absent and a soft "can't remove" shake plays |
| Add | **Select** the dashed **＋ Add a Control** slot (always last on the page) | Opens the gallery (section 3) |
| New page | **＋** icon at the right end of the menu bar | Creates an empty page and opens the gallery for it |
| Done | **Back** (when nothing is lifted) | Saves. Home also saves and closes |

The hint bar at the bottom is the existing MoveHints style. It shows "Select Move · ≡ Size · ⏯ Remove · Back Done", or while lifted "◀▶▲▼ Move · Select Drop · Back Cancel".

### Mockup: edit mode, a 2×1 lifted

```
                               ┌─ menu bar ─────────────────────────────┐
                               │ 9:41 PM        (▣)( ♫ )( ⌂ )( ✳ )( ＋ ) │  ← page icons; current page in a white disc
                               └────────────────────────────────────────┘
     ⊖┌──────────────┐ ⊖╭─────────────╮
      │      ⚙       │  │ ◉ Wi-Fi      │      ← every tile wobbles ±0.8° (out of phase)
      │   Settings   │  │   Home-5G    │      ⊖ = remove legend (Play/Pause)
      │   Fire TV    │ ⊖╭─────────────╮
      └──────────────┘  │ ◉ Bluetooth  │
                        │   On         │
     ⊖╭─────────────╮   ╰─────────────╯
      │ ☾ Sleep     │ ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄       ← the slot the lifted tile will drop into
      │   Off       │ ┆               ┆
      ╰─────────────╯ ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄
                    ╭═══════════════╗
                    ║ ⛨ VPN         ║  ← LIFTED: ×1.28, focus shadow, white fill, still wobbling
                    ║   Connected   ║
                    ╚═══════════════╝
     ⊖╭──────────────────────────────╮
      │ ☰ Launcher Settings          │
      ╰──────────────────────────────╯
     ⊖( 🎮 ) ⊖( ◐ ) ⊖( ⛰ ) ⊖( ▦ )
     ⊖( ⎚ ) ⊖( ◔ ) ⊖( 🧹 ) ⊖( ↻ )
      ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┐
      ┆  ＋  Add a Control             ┆
      └┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┘
        ◀▶▲▼ Move   Select Drop   Back Cancel
```

## 3. The "Add a Control" gallery

The gallery uses the standard Settings layout (SYS-02), so it reads as part of Glass, not a new surface. The left column holds the groups; the right column lists controls with live previews.

```
┌──────────────────────────────── Add a Control ───────────────────────────────┐
│                                                                                │
│   ╭──────────────╮      GLASS                                                  │
│   │  Glass     ✓ │      ╭───────────────╮                                     │
│   ╰──────────────╯      │ ☾ Sleep Timer │  Pause and sleep after 15–90 min    │
│     Add-ons             │   Off         │  1×1 · 2×1                    ✓ Added│
│     By App              ╰───────────────╯                                     │
│                         ( ↻ )  Restart · restart Fire TV · System or Root      │
│   🔎 Type on your       ( 🧹 ) Clean Up · free memory and caches               │
│      phone…             ╭───────────────╮                                     │
│                         │ ♫ Now Playing │  Play, pause, skip any app          │
│                         │   Not playing │  1×1 · 2×1 · 2×2                     │
│                         ╰───────────────╯                                     │
│                         ADD-ONS                                               │
│                         ╭───────────────╮                                     │
│                         │ ✳ Agents      │  Claude Code status and approvals   │
│                         │   3 working   │  via Claude Code Bridge · 2×1       │
│                         ╰───────────────╯                                     │
│                         ╭───────────────╮                                     │
│                         │ ⌂ Movie Night │  Run a Home Assistant scene         │
│                         │   Off         │  via Home Assistant · 1×1 · 2×1     │
│                         ╰───────────────╯                                     │
└────────────────────────────────────────────────────────────────────────────────┘
```

- **Groups:**
  - **Glass:** the built-ins below.
  - **Add-ons:** every control from installed add-ons, each with a grey "via <add-on>" line.
  - **By App:** the same controls grouped under the app they act on (Spotify, Plex, Home Assistant).
- **Search:** "Type on your phone" (the existing QR flow), filtering by name.
- **Rows:** each shows a preview at the control's default size, using live data if it's cheap or the add-on's preview value otherwise (no network call just to browse), plus a one-line description and its allowed sizes. A **System** or **Root** tag appears where the tier needs it (quick-wins spec table).
- **Select:** adds the control at the end of the current page, or into the focused empty slot if the gallery was opened from one. The gallery then closes and **focus lands on the new tile**, which plays the "add" motion.
- **Already added:** the row shows ✓ Added, and Select jumps to that tile instead of adding a copy. Only parameterized controls, such as a Home Assistant scene with a chosen entity, may appear more than once.
- **Configuration:** a parameterized control opens its config page right after adding (Settings layout; any text is typed on the phone).

## 4. Pages as menu-bar icons (tvOS frame 05-21)

- The Control Center header (time, weather) keeps its place. **Page icons sit at the right of the header**: 29 dp circles at a 30 dp pitch, flush with the grid's right edge, as tvOS draws Home and Control Center.
- **Current page:** its icon sits in a white disc; the others are glyphs on thin glass.
- **Focus path:** Up from the top row of tiles focuses the current page icon. Left/Right **switches page as focus lands**, as tvOS tab bars do, with no Select needed. Down returns into that page's tiles, top-left first.
- **Page icon:** the user picks it in edit mode (Menu on the icon). The default is the first tile's glyph.
- **Defaults on first run:**
  - Controls (▣);
  - Media (♫), with Now Playing at 2×2 plus Plex;
  - Home (⌂), created only if the Home Assistant add-on is installed;
  - Agents (✳), created only if the Claude Code Bridge add-on is installed.
- **Changing pages:** in edit mode the bar gets a ＋. Play/Pause on a page icon deletes that page, after a Cancel-first alert if it isn't empty.

## 5. Built-in controls (Glass group)

| Control | Sizes (default first) | Tier | Notes |
|---|---|---|---|
| Fire TV Settings | 2×2 only | none | Can't be removed. It's the only way to reach Fire's settings |
| Wi-Fi, Bluetooth | 2×1, 1×1 | none | Opens the system pages; never toggled from Glass |
| Launcher Settings | 4×1, 2×1 | none | |
| **Sleep Timer** | 2×1, 1×1 | sleep: root (or the accessibility lock, to verify) | Quick-wins spec §1. At 1×1 the remaining minutes replace the glyph ("28") |
| **VPN** | 2×1, 1×1 | none | Hidden from the gallery when no VPN app is installed |
| **Restart** | 1×1 | System app or root | Cancel-first confirm |
| **Clean Up** | 1×1, 2×1 | bulk caches: System app or root | Replaces Free Memory; at 2×1 the value line shows the last result |
| Now Playing | 2×2, 2×1, 1×1 | none (notification listener) | Built-in because Glass already holds the media sessions; see 6.1 |
| Game Controllers, Theme, Screen Saver, App Switcher, AirPlay, Performance | 1×1 | as today | |
| Alexa | 2×1 page shortcut | none | Opens today's Alexa page. **Select is still guarded there** (glass-guard). Never put Smart Home actions on a tile |

## 6. Showcase controls

Glass draws everything; add-ons send only `{glyphKey, label, value≤18 chars, state, actions}` (Yang's contract). The state visuals are the same for every control:

| State | Look |
|---|---|
| off | glyph on glass, value "Off" |
| on | glyph in a **white disc** (Glass convention); the add-on may tint the disc only for status colours: amber "waiting", green "active" |
| value | second line shows the text; at 1×1 a short value (≤3 chars) may replace the glyph |
| loading | glyph at 60%, value "…" as a **static** dot (no shimmer) |
| error | glyph plus a small dot, value "Can't reach X"; Select retries |
| paused | grey, value "Paused" (the add-on timed out 3×, per Yang §7) |

### 6.1 Now Playing (built-in, MediaSession)

```
2×2 playing                 2×1 playing            1×1        idle (any size)
┌──────────────┐            ╭─────────────────╮    ( ❚❚ )     ╭─────────────────╮
│ [art 104dp]  │            │ [art] Shiver     │               │ ♫ Now Playing   │
│              │            │       Coldplay   │               │   Not playing   │
│ Shiver       │            ╰─────────────────╯               ╰─────────────────╯
│ Coldplay ▬▬▬─ │   ← progress = a 2 dp line, updated 1×/s
└──────────────┘
```

- **Controls:** Select = play/pause. Left/Right while focused and **not** in edit mode = previous/next.
- **What can block those moves:** they would otherwise move focus, so they only act at 2×2, where the tile is wide enough that focus moving into it from a neighbour already happened. At 2×1 and 1×1, Left/Right move focus as usual.
- **Art:** decoded at shown size, 208 px at 2×2, via the P81 pipeline. The only raster in Control Center is that one cover, about 170 KB.

### 6.2 Home Assistant scene (add-on)

```
2×1 off            2×1 running             2×1 on                1×1        error
╭───────────────╮  ╭───────────────╮      ╭───────────────╮     ( ⌂ )     ╭─────────────────╮
│ ⌂ Movie Night │  │ ⌂ Movie Night │      │◉ Movie Night  │               │ ⌂• Movie Night  │
│   Off         │  │   …           │      │   On  (green) │               │   Can't reach HA│
╰───────────────╯  ╰───────────────╯      ╰───────────────╯               ╰─────────────────╯
```

Select calls the scene service: loading "…", then on. Scenes are momentary in Home Assistant, so "On" shows for 3 s and the pill then returns to off. If the scene has a matching entity that stays on, it stays on.

### 6.3 Claude Code (add-on: Claude Code Bridge, research §8b)

```
2×1 working           2×1 waiting                1×1                  offline
╭────────────────╮    ╭────────────────╮        ( ✳ )  (badge "1")    ╭────────────────╮
│ ✳ Agents       │    │◉ Agents        │                               │ ✳• Agents       │
│   3 working •  │    │   1 waiting    │  ← amber disc                 │   Mac offline   │
╰────────────────╯    ╰────────────────╯                               ╰────────────────╯
```

- **Tile:** Select opens the Agents card, one row per session: project, last prompt, current tool, elapsed time.
- **The permission prompt is the one place an add-on can raise UI over Control Center**, and Glass draws it. When a `PermissionRequest` reaches the bridge:
  - **Control Center open:** the card grows from the Agents tile (MOTION-05).
  - **Control Center closed:** a small glass banner appears top-right on any screen: "✳ Claude Code needs approval · open Control Center". It doesn't take focus and stays until the request is answered or expires. Opening Control Center lands focus straight on the card.

```
                 ╭──────────────────────────────────────────────╮
                 │ ✳  Allow Bash?                                │
                 │    glass-launcher · Yang                       │
                 │  ┌──────────────────────────────────────────┐ │
                 │  │ ./gradlew :app:testDebugUnitTest --tests │ │  ← monospace, max 3 lines,
                 │  │   "*CoverPreviewTest*"                   │ │    "…" + Menu (≡) for full text
                 │  └──────────────────────────────────────────┘ │
                 │    in ~/Documents/glass-sdd-preview            │
                 │                                                │
                 │  ╭────────────────────────────────────────╮   │
                 │  │                Deny                     │   │  ← focused first (white capsule)
                 │  ╰────────────────────────────────────────╯   │
                 │  ╭────────────────────────────────────────╮   │
                 │  │              Allow once                 │   │
                 │  ╰────────────────────────────────────────╯   │
                 │           Expires in 9:42 · then asks on Mac   │
                 ╰──────────────────────────────────────────────╯
```

- **Security rules:**
  - **Deny gets focus first.** Back = Deny, and Allow needs a deliberate Down then Select.
  - There's no "Always allow" in v1.
  - When the 600 s hook timeout runs out, the request **falls through to the Mac's own prompt**, as the docs default. It's never auto-approved.
  - Command text is never truncated silently: "…" plus Menu shows it all.
  - The bridge requires a bearer token typed on the phone.
- **Several requests** queue one card at a time, with a "1 of 3" counter.

### 6.4 Plex continue (add-on)

```
2×1                                   1×1
╭──────────────────────────╮          ( ▶ )   value replaces nothing; Select = resume
│ ▶ Plex                   │
│   The Bear · 23 min left │   ← value line ≤18 chars → "The Bear 23m left"
╰──────────────────────────╯
```

Select deep-links into Plex to resume. **No artwork in the tile**, because add-ons are glyph-only. Plex's art belongs in its Top Shelf feed (add-ons spec; P81 gate).

### 6.5 Weather (add-on, or built-in if the weather widget moves here)

```
1×1          2×1
( 18° )      ╭──────────────────╮
             │ ☀ 18° Clear       │
             │   H 22  L 11      │
             ╰──────────────────╯
```

Select opens a forecast card, a Glass-drawn list. The value refreshes at most every 15 min.

## 7. Rendering, memory and contract notes

### 7.1 GPU

- **One baked sheet per backdrop:** today's P76 `panelSheet`, about 80×90 px. Every page and every tile samples it, and page switches and edit mode never re-bake.
- Tiles are the sheet clipped to their shape, plus flat fills (white focus, disc).
- **Wobble is `graphicsLayer { rotationZ }` only:** ±0.8°, 190 ms half-period, phase = tile index × 37 ms. There's no re-layout and no recomposition per frame; it's a single `rememberInfiniteTransition` read in the draw phase.
- **The lifted tile** reuses the pre-blurred focus shadow bitmap (one shared bitmap), scaled. Its elevation is never animated.
- **Gallery and approve card:** the Settings-layout surfaces, one glass layer each. They never sit on top of tile glass, so glass never stacks on glass (GLASS-04).

### 7.2 Memory (budget ≤ 120 MB with Control Center open)

- **Glyphs:** a fixed vector set inside Glass (Material Symbols subset). Add-ons choose a `glyphKey` and can't ship bitmaps, so the cost is constant: about 1 MB of cached vectors.
- **Rasters:** only the Now Playing art (~170 KB) and the baked sheet.
- **Add-on data:** fetched through the bound service **only while Control Center is open**, at most 1 update per tile per second (Yang §7), and unbound on close. That keeps processes inside lmkd's 12 cached apps.
- **Target:** opening Control Center adds ≤ 8 MB over Home's PSS. Measure with the P79b script plus a Control Center open/close.

### 7.3 Contract change for Yang

The add-ons spec lists "tap and **long-press** handlers". **Hold-Select must stay reserved for edit mode** (rule 2 in the research), so:
- drop long-press from the tile contract;
- add-ons get Select (`onTap`) and an optional **Menu (≡)** action (`onMore`) outside edit mode, which opens their detail card or config;
- in edit mode, Menu means resize.

## 8. Motion

All numbers are at 30 fps on the stick. The springs are the existing ones (MOTION-02/13). Continuity rule: nothing teleports; every change is a move or a crossfade.

| Moment | Spec |
|---|---|
| Enter edit | Hold progress shows no ring (tvOS has none). At 600 ms every tile starts wobbling (phase-offset), ⊖ badges scale 0→1 over 160 ms with a 20 ms per-tile stagger (max 200 ms total), the ＋ slot and hint bar fade in over 160 ms. One haptic-equivalent: the focused tile dips ×0.96→×1.0 over 120 ms |
| Exit edit | Wobble eases to 0 over 120 ms (FocusTile's tween), badges scale out over 140 ms, the ＋ slot fades over 140 ms; total ≤ 180 ms |
| Lift / drop | Focus spring `spring(0.65, 850)` to ×1.28 with the shared shadow bitmap; drop uses `spring(1.0, 500)` back to ×1.21 |
| Move | The lifted tile slides cell to cell, 160 ms exponential decelerate. Displaced neighbours slide on the same curve at the same time. Under held D-pad, moves chain without waiting for the slide to finish (interruptible, MOTION-14) |
| Resize | Bounds animate over 220 ms (decelerate); the glyph stays centred; the label and value cross-fade in during the last 120 ms when growing and out in the first 80 ms when shrinking; neighbours repack in the same 220 ms |
| Remove | The tile scales to ×0.6 and fades over 160 ms; then neighbours close the gap over 160 ms (sequential, so the eye follows) |
| Add | From the gallery: the gallery closes (170 ms), then the new tile scales ×0.6→×1.0 with `spring(0.9, 700)` in its packed slot and takes focus; neighbours open space first (160 ms) |
| Page switch | Tiles cross-fade, 150 ms; the old page slides 24 dp down and the new one rises 24 dp. The sheet doesn't change (same backdrop), so there's no re-bake |
| Approve card | Grows from the Agents tile (or the banner), uniform scale 0.5→1 anchored at the source, about 130 ms, no overshoot; the screen behind dims a further step. Closing on an answer reverses it, about 170 ms; the tile then flashes its value ("Allowed" or "Denied") for 2 s (`controlWidgetStatus` analogue) |
| Banner (Control Center closed) | Slides 12 dp down and fades in over 200 ms; leaves with a 150 ms fade |
| Reduce Motion | No wobble (the ⊖ badges carry edit mode alone). Moves, resizes and page switches become 120 ms dissolves. Lift is a static ×1.28 |

## 9. Persistence

Control Center's layout is one JSON value in the existing launcher DataStore config, replacing `ccHidden`:

```json
{"cc": {"v": 1,
  "pages": [
    {"id": "controls", "icon": "grid", "tiles": [
      {"id": "fire_settings", "size": "2x2"}, {"id": "wifi", "size": "2x1"}, {"id": "bluetooth", "size": "2x1"},
      {"id": "sleep_timer", "size": "2x1"}, {"id": "vpn", "size": "2x1"}, {"id": "launcher", "size": "4x1"},
      {"id": "controllers", "size": "1x1"}, {"id": "theme", "size": "1x1"}, {"id": "screensaver", "size": "1x1"},
      {"id": "switcher", "size": "1x1"}, {"id": "airplay", "size": "1x1"}, {"id": "performance", "size": "1x1"},
      {"id": "cleanup", "size": "1x1"}, {"id": "restart", "size": "1x1"}]},
    {"id": "media", "icon": "music", "tiles": [{"id": "now_playing", "size": "2x2"}, {"id": "addon:plex/continue", "size": "2x1"}]}
  ],
  "parked": ["addon:ha/scene#movie_night"]}}
```

- **Migration:** today's order is the default list, and every id in `ccHidden` is left out. `ccHidden` is read once, then deleted.
- **When an add-on is uninstalled,** its tiles move to `parked` together with their page and slot. If it's re-installed they come back there; if not, they're dropped on the next cleanup.
- **An unknown id,** such as a disabled add-on, is skipped while packing and keeps its list position, so the layout comes back exactly when the add-on returns.
- **Reset:** Settings › Control Center › Reset Control Center, with a Cancel-first alert, restores the default list.
- **Save timing:** written when edit mode ends, never per move, so a cancelled lift costs nothing.

## 10. Open items

1. **Stick check:** the accessibility lock action sleeps Fire OS 8 (Sleep Timer's no-root tier).
2. **Stick check:** hold-Select at 600 ms doesn't collide with the remote-keys service or Fire's own long-press (Fire uses long-Home and long-Select in some apps; Control Center is Glass's own window, so it should be clean).
3. **Yang:** the contract change in 7.3 (no long-press; Menu = `onMore`), and the `glyphKey` vocabulary (start with about 60 Material Symbols).
4. **Perf gate after building:** Control Center open/close bench (baseline >33 ms 3.0/cycle), edit mode with all tiles wobbling (expect 0 extra slow frames because only transforms change), and PSS ≤120 MB with the Media page's Now Playing art.
5. **User taste call:** Deny-first on the Claude Code card (recommended) versus Allow-first.
