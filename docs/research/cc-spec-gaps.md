# Control Center: spec vs Glass (gap table, P138)

Written 2026-10-13 against `origin/master` 83cb1a7 (includes P136: AirPlay platter / audio picker `3d4b0b2`, Sonos consent gate `69fa145`,
Now Playing card placement `bc9ceb7`). Scope: geometry, type and glyphs. Colours, contrast, palette, fills and the `CcSurface` material are
out of scope here and tagged **P137 / colour**.

## Sources, and how far to trust each

| Tag | Source | Trust |
|---|---|---|
| **FW** | tvOS 26.6 firmware, `TVSystemPanelUI` / `TVSystemUIService`: `index/constants/TVSystemPanelUI.tsv` and `curated/control-center-controls.tsv` (constants decoded from the disassembly; method named per value) | **Measured** (exact numbers). Behaviour of tvOS 26.6, which 27 keeps unless a frame says otherwise |
| **F27** | tvOS 27 frames: skill `assets/screenshots/control-center.jpg` (1280x720), `control-center-homekit.jpg`, `fidelity-audit-2026-10-12/control-center--03-41_control-center-over-podcasts.jpg`. I re-measured the first one at 4x zoom for this table | **Measured on a compressed frame**, +-3% |
| **SK** | tvOS 18 Sketch "Design Templates" Control Center artboard (grid 96 / 16 / 24) via skill `references/control-center-grid.md`; the 27 frame matches it within 3% | Geometry measured (tvOS 18); styling must not be copied |
| **KIT** | Apple iOS 27 UI Kit, System > Control Center > Controls (`kit-cc-controls.txt`), iPhone points | Measured, but iPhone sizes: use proportions only |
| **AUD** | `FIDELITY-AUDIT-2026-10-12.md` (round 1; the artifact link in the brief, claude.ai/artifact/UdLShztqcb7aoqZtRrJaJK, is a different report, "Glass Install Modes Audit", with no Control Center content; the audit's own published copy is claude.ai/artifact/N49oMoAHX5JuaMik3KL4ti, named in the .md, which is the round-1 source used here) | Derived from the above |
| **REA** | `rom/research/re-findings.md` (no Control Center numbers: Phase 1/2 triage and IPSW notes only), `cc-toggle-states.md`, `tvos-airplay-picker.md` | Firmware-derived (FW) behaviour |
| **SPEC** | repo specs `2026-10-11-customizable-cc-design.md`, `...cc-quickwins-and-addons-ux.md`; F01 to F06 in git log (`14e36cd`, `d838234`, `a6d9f53`, `c5e005e`, `5ae42b3`, `847e891`) | Product decisions, not Apple data |

**Layout fixes beyond the table (user feedback, P138):** header icon spacing (H1), focus growth vs the gutters (F1) and label truncation (T1) are rows above.

**Units.** tvOS UI space is 1920x1080 pt. Glass's UI is 960x540 dp. **dp = pt / 2 = px(1080p) / 2.** The twin and the stick both render 1 dp = 2 px.
A 1280-wide frame is multiplied by 1.5 to get 1080p px. Type: the skill's scale maps tvOS px to sp at /2 (Caption 2 = 23 px = 11.5 sp; Caption 1 = 25 px = 12.5 sp).

**Measured vs estimated.** Every spec cell below is tagged. "m" = measured (FW exact constant, or a frame/Sketch measurement), "e" = my estimate
from a frame (+-1 dp). Spec values with no firmware or frame source are written "none".

## What the user sees (twin screenshot of master, 2026-10-13, vs the 27 frame)

Cell 56 dp vs 48 dp (+17%); pill text 13 sp vs 11.5 sp; the pill's glyph disc is 28 dp with an 18.5 dp glyph where tvOS's disc is about 21 dp with a
glyph of about 10 dp; the page icons are 40 dp circles where tvOS's header controls are 29 dp; a focused first-row tile is clipped by the scroll viewport.
Together this reads "far too big". The "full height" complaint is addressed in rows P1 and P2 below (what the sources do and do not say).

## Gap table

Status: **FIX** = done in STEP 2. **P137** = colour/contrast/fill, left alone. **DEFER** = decision or unclear spec (reason given).
Size/risk: S/M/L = effort; risk low/med/high (regression in motion, tests or add-ons).

### Panel

| # | Item | Spec (value, unit, source, m/e) | Glass now (file:line) | Gap | Status, size/risk |
|---|---|---|---|---|---|
| P1 | Panel height | FW `platterSizeForPlatterController` = 860 pt (430 dp) for a pushed platter; bar 70 pt + spacer 16 pt; bottom inset about 78 px (m): the platter column spans the screen height down to the bottom inset (e). AUD lists "right-side placement at full height" as a user decision. F27 shows no slab behind the grid | panel `heightIn(max = screenH - 39)`, content-height everywhere (P101 `4a6010e` made it "as tall as its content") | The column was content-height on every page; the user asked for full height | **FIX** the column is `screenH - 39 dp` tall on every page; the grid page sits at the top of it and scrolls inside it, a pushed platter (Remotes, AirPlay) fills it. The open's stagger still measures from where the controls end (`motion.panel` is clamped to the content). With no slab drawn, the visible difference is the platters; a drawn full-height panel would be a materials change (**P137 / colour**). M / med |
| P2 | Panel vertical bounds | top: header centre about 40 dp (F27: 54 px in 1280 = 81 px = 40 dp, m); bottom inset 78 px = 39 dp (AUD, e) | top `chromeInset` 22 dp + 40 dp icon = centre 42 dp; bottom `CC_BOTTOM_INSET` 39 dp (`:325`) | Within 2 dp | none |
| P3 | Panel width | FW `defaultPlatterDesiredWidth` 480 pt = **240 dp** (520 pt scaled UI) (m). SK: 4 x 96 + 3 x 16 = 432 pt grid + 2 x 24 padding = 480 | `CcSizes.column` = 4 x 56 + 3 x 9 = **251 dp** (`:105-110`); plus 16 dp bleed each side | +4.6% over the FW width; the grid is 216 dp in SK, Glass 251 (+16%) | **FIX** via u 48 / gap 8 (216 dp grid; 240 dp with 12 dp padding). S / med (see G1) |
| P4 | Panel position, margins | right edge flush with the status pill's right edge; 36 to 50 px from the screen edge (SK/F27, e). F27: grid right edge at x 1228 of 1280 = 1842 px = 921 dp, so a 39 dp right margin | right margin `chromeInset + 14 - 16` = 20 dp + 16 bleed + 2 = about 38 dp (`:781`) | OK (about 1 dp) | none |
| P5 | Platter corner / outer | FW `defaultPlatterCornerRadius` 48 pt = 24 dp (64 scaled) (m). F27: no slab behind the grid | no platter background drawn (tiles are each glass); the confirm card is a `26.dp` round-rect (`CcShowcase.kt:221`) | Confirm card r 26 vs 24 | **FIX** (S, low) |

### Header (menu bar)

| # | Item | Spec | Glass now | Gap | Status |
|---|---|---|---|---|---|
| H1 | Page-icon size and spacing | SK: header controls 58 px circles at a **60 px = 30 dp pitch** (gap 1 dp, reads as touching). F27 (control-center.jpg, 4x zoom): the toggles icon about 48 px = **24 dp** across, centres 60 px = 30 dp apart: **gap about 6 dp** (m, +-1 dp). The user's `p138-spacing.png` shows the 1 dp version touching | `iconSize` 40 / 34 dp, then 29 dp with a 1 dp gap (first pass, too close) | 40 vs 24 dp; pitch 50 vs 30 | **FIX** 24 dp circles at a 30 dp pitch (6 dp gap). S / med (the morph's `loneSpot` is now 24 dp) |
| H2 | Page-icon glyph | 21 pt Bold symbol in a 58 pt control (about 36%: e) | 20 dp image in a 40 dp circle (`:1304`) | 20 vs 12 dp at the 24 dp circle | **FIX** (scale with the icon, 0.5 x size). S / low |
| H3 | Header height / gap to grid | FW `barHeight` 70 pt = 35 dp; `controlCenterSpacerHeight` 16 pt = 8 dp (m) | the header Row is content-height (about 40 dp icons) + `bottom = 16 - CC_BLEED` (`:793`); icon top 22 dp, tile top 70 dp (twin: 140 px) | F27: grid top at 66 dp; Glass 70 dp (+4 dp) | **FIX** with H1 (smaller icons pull the grid up to about 66 dp). S / low |
| H4 | Clock | one line, about 26 px = 13 sp, white 0.6, no seconds/date (F27, m; AUD #3) | `Type.caption` (13 sp), alpha 0.6, one line (`:577`, `:512`) | none (F03 landed) | none |

### Grid

| # | Item | Spec | Glass now | Gap | Status |
|---|---|---|---|---|---|
| G1 | Cell `u` | SK 96 pt = **48 dp**; F27 power tile about 109 dp for 2u+g = 104 (+5%, compression), circles 47 dp (m) | `val u = 56 * k` (`:105`) | 56 vs 48 (+17%) | **FIX** u 48 (the 13 sp floor was the reason for 56: dropped, text becomes Caption 2, see T1). M / med: every page's rows, packing, tests, add-on tiles size from `CcSizes` |
| G2 | Gap | 16 pt = **8 dp** (m) | `9 * k` (`:106`) | +1 dp | **FIX** 8 (the 1.10 focus lift then overlaps by 2.4 dp: see F1). S / low |
| G3 | Inner padding | 24 pt = 12 dp (SK, m); no slab is drawn (F27), so it is spacing only | tiles at `CC_BLEED` 16 + 2 dp from the column edge (`:921-922`) | invisible (the bleed room is for focus growth and the shadow) | none (keep the bleed; it also clips nothing) |
| G4 | Row pitch | u + g = 112 pt = 56 dp | 65 dp | follows G1/G2 | **FIX** (derived) |
| G5 | Rows per page | tvOS: 4 rows in the 480 pt panel; extra modules scroll (AUD #10, e) | `MAX_ROWS = 7`, `PAGE_ROWS = 4` (`CcLayout.kt:275-277`); SPEC says 6 | 7 vs 4 | **DEFER**: product decision (SPEC: 6 rows, user layouts already stored); `PAGE_ROWS 4` already matches the default page |
| G6 | Scroll / page behaviour | `wantsScrollingContentTopBlur`, `wantsContentClipped` (FW, m): the list scrolls under a top blur, clipped | `ccEdgeFade` top and bottom fade, `verticalScroll` (`:915-923`, `:1627`) | **a focused tile at the top is clipped by the viewport**: the twin shows the 2x2 Settings tile cut flat at its top edge when focused (content top padding 2 dp, lift 1.10 = +5 dp) | **FIX** top room = lift growth (about 6 dp). S / low |

### Controls

| # | Item | Spec | Glass now | Gap | Status |
|---|---|---|---|---|---|
| C1 | 1x1 circle: size | 96 pt = 48 dp (SK, F27 47 dp, m) | `round = u` 56 | G1 | **FIX** (via u) |
| C2 | 1x1 glyph size | FW `controlCenterGlyphPointSize` **42 pt = 21 dp** (48 pt scaled) (m); F27: gamepad 28 dp wide, accessibility ring 20.6, search 19.7 (m) | `roundGlyph = 21 * k` (`:116`) | size right; ratio 38% of 56 vs 44% of 48 (corrected by G1) | none |
| C3 | 1x1 glyph weight | KIT: SF Symbol 21 Bold (m, iPhone); F27 strokes look Medium-Bold | drawables are 24 dp Material vectors, regular weight | unverified: no weight source for tvOS 27 beyond KIT | **DEFER** (asset change; no hard number) |
| C4 | 1x1 label | none drawn (FW format 6: glyph only; m) | none (F01) | none | none |
| C5 | 2x1 pill: size | 208 x 96 pt = **104 x 48 dp** (SK; F27 101 x 48, m) | `pillWidth = 2u + gap` 121 x 56 | G1 | **FIX** (via u) |
| C6 | 2x1 pill: corner | full capsule | `Shapes.pill` (50%) | none | none |
| C7 | 2x1 pill: leading column | FW `defaultSymbolReservedSpace` 86 pt = **43 dp** (m); F27: text starts 42.6 dp from the pill's left edge (m) | 40 dp column, text starts at 40 dp | 2.6 dp narrower than F27 so "Bluetooth" and "Connected" fit whole (T3) | **FIX** done with T3 |
| C8 | Pill glyph disc (off toggle) | KIT 41 pt disc in a 69 pt tile = 59% of the tile height at 15 pt Bold glyph (36% of the disc); F27 (DND pill, 4x zoom): **disc 21 dp, moon about 10 dp** = 44% of the pill height, glyph 50% of the disc (m, +-1 dp). White at 0.15 (KIT, FW cc-toggle-states). | `disc = u * 0.5f` = 28 dp, `discGlyph = u * 0.33f` = 18.5 dp (`:113-114`) | disc **+33%**, glyph **+85%**: the main "glyphs too big" | **FIX** disc 0.44u = 21 dp, glyph 0.24u = 11.5 dp (F27 zoom: moon about 54% of the disc). S / med (tests, golden) |
| C9 | Pill glyph (no disc: on, or a menu pill) | FW 42 pt = **21 dp** glyph, centred in the leading column; F27: Wi-Fi (on) 23 dp wide, Audio 21, Sleep Timer ring 21.6 (m) | on: was the 18.5 dp disc-glyph slot | on-glyph 18.5 vs 21 | **FIX** an on pill's glyph is 21 dp and eases from the disc glyph over the same 0.25 s as the disc fades (never pops). Which pills keep a disc is unchanged (P129 rule; the disc is a fill). **P137 / colour:** tvOS's menu pills (Audio, Sleep Timer) have *no* disc in F27, so Glass's action pills (Clean Up, Restart, Launcher Settings, Add, platter rows) show an 11.5 dp glyph in a disc where tvOS shows a bare 21 dp glyph: a decision on fills, left for P137. |
| C10 | Pill title | FW `stylingForState` format 0x11/0xc: **style 7 = Caption 2 = 23 px = 11.5 sp**, 1 line, SemiBold? (the enum has regular = Medium, emphasized = Bold; F27 title looks Medium) (m size, e weight) | `Type.caption` 13 sp SemiBold, `lineHeight 15.sp` (`:1396`) | 13 vs 11.5 sp (+13%), weight SemiBold vs Medium | **FIX** 11.5 sp, Medium (title) / Medium (value, 0.6 alpha). S / med (clipping: marquee handles) |
| C11 | Pill value line | FW format 0x11: style 7 as the title, directly under it, `defaultDarkModeDetailTextColor` white 0.376 dark / 0.502 light (m); KIT 0.33 | caption 13 sp, `fg.copy(alpha = 0.7f)` | size (C10); alpha is a colour item | size **FIX** with C10; alpha **P137 / colour** |
| C12 | Pill line spacing | Caption 2 line height 30 px = 15 dp (m); F27 baseline pitch 13.5 dp (e) | `lineHeight 15.sp` | OK | none |
| C13 | Pill label rules | 1 line, no shrink, clip, marquee only while focused (FW TVSPLabelAttribute, m) | `PillText` fade + focused `basicMarquee` (`:128-150`, F05) | none | none |
| C14 | Icon-to-text spacing | FW `defaultImageTextHorizontalSpacing` 7 pt = **3.5 dp** (m, the gap between the symbol's reserved column and the text) | 7 dp padding (`:1395`) after the 28 dp disc box | with the 43 dp column fixed, the text start is what matters (C7) | none |
| C15 | 2x2 tile: size | 208 x 208 pt = 104 dp square (SK; F27 109 dp, m) | `big = 2u + gap` 121 | G1 | **FIX** (via u) |
| C16 | 2x2 corner radius | FW `defaultCornerRadius` 24 pt = 12 dp for elements (m); F27: radius about 40 px on 215 px = 20 dp (AUD, e); SK 24/96 = 25% (tvOS 18, old) | `RoundedCornerShape(26.dp)` (`:1109`, `:1455`, `:1345`) | 26 vs 20 dp | **FIX** 20 dp (F27 wins). S / low |
| C17 | 2x2 glyph | FW format 0xe: glyph box **113 pt tall (128 scaled)**, 34 pt from the top, centred; F27: power glyph ring 49.7 dp (m) | `bigGlyph = u * 0.8f` = 44.8 dp (`:117`) | at u 48 it would be 38 dp: under F27's 50 dp | **FIX** 0.5 x tile height = 52 dp at 104 (`bigGlyph = u * 1.04`). S / low |
| C18 | 2x2 label | FW format 0xe: title **13 pt = 6.5 dp below the glyph**; style: F27 "Power Off" about 14 sp-class (cap height 9 dp -> about 13 sp), Medium (e) | `Type.caption` SemiBold 13 sp, `spacedBy(6.dp)` (`:1289-1292`) | spacing OK; weight SemiBold vs Medium (e) | **FIX** weight Medium; size stays 13 sp (e: format 0xe title style not decoded). S / low |
| C19 | 4x1 tile | full panel width, pill capsule (SPEC, Glass-specific; tvOS has none) | pill with `PillContent` | follows C8-C10 | **FIX** (via PillContent) |
| C20 | Value replacing glyph (1x1 Sleep Timer minutes) | none in tvOS (SPEC: allowed) | `Type.heading` 19 sp (`:1169`) | 19 sp digits in a 48 dp circle fit | none |
| T1 | Pill text fit (no truncation) | FW: 1 line per element, clip and marquee on focus; tvOS wraps a title with no value line onto two lines at its spaces (F27 "Do Not / Disturb", "Sleep / Timer") (m). User (P138): no "Connecte" / "Bluetooth" cut, no mid-word cut | pill text 13 sp then 11.5 sp with a 53 dp text area: "Connecte", "Bluetoot" cut in the stick-like twin and the user's screenshot | text area 53 dp for a 55 dp word | **FIX** text area 61 dp (40 dp column, 1 dp end padding), tracking -0.1 sp, a title with no value wraps to two lines at spaces (never mid-word, never an ellipsis); the value line stays one line. **Test:** `CcLabelFitTest` composes every default title and value (and the long names: Launcher Settings, Now Playing, Screen Saver, App Switcher, Performance) in a 104 dp pill and asserts no overflow; `labelsFitTheirTiles` does it in the live panel. Values that depend on the network or a running task (a long SSID, "Nothing to clean") still clip and scroll on focus, as tvOS's do. S / med |

### Page switching, header glyphs, Performance (user additions, REA answers in `cc-toggle-states.md` section "CC page/panel switching and header glyphs")

| # | Item | Spec | Glass now | Gap | Status |
|---|---|---|---|---|---|
| S1 | Switching pages from the menu bar (Controls <-> More) | FW (REA, `cc-toggle-states.md` "Two different transitions" 2): the whole hosted panel slides in from the focus-heading direction (`setHostedViewController:focusHeading:orthogonalNavigation:`) on the platter animator; no paging; sub-panels (a control's menu) are the zoom-push from the tapped control, Menu pops. Springs mass 1, stiffness 150 / damping 20 (180/20, 300/28 per segment) (m) | a 140 ms `Crossfade` of the two grids, the box snapping to the new height | not a slide, not on the springs; the height jumped | **FIX** `AnimatedContent`: the new page slides in a third of the panel width from the heading side (the old one leaves a sixth the other way) on spring(150, damping 20 = ratio 0.8165), the box height follows the same spring, interruptible (one transition); the two fades are linear and complementary over 150 ms (in + out = 1: no empty frame, the old page never at full strength once the new one has begun). Reduce Motion: a plain fade. The zoom-push for platters (Remotes, AirPlay) is unchanged (N4). **Test:** `ControlCenterMotionTest.switchingPagesIsContinuous` (40 frames on a paused clock: no frame with almost nothing, no frame carrying 60% of the change). M / med |
| S2 | App-switcher glyph | REA: tvOS has no app-switcher control in Control Center; its closest system symbol is `rectangle.on.rectangle` (Glass's choice); `square.grid.2x2` (the 9 dots) is the **More page's** header icon | the switcher tile used the same 9-dot `ic_apps` as the More page icon | same glyph for two controls | **FIX** new `ic_app_switcher` (two overlapping rectangles, Material `filter_none`, the nearest vector to `rectangle.on.rectangle`) on the tile and in the gallery. The header glyphs REA names (`switch.2` for Control Center, `homekit` for Home, avatar for the profile) are not changed: Glass's Controls page icon is a TV glyph (unchanged), the Home/profile modules do not exist. S / low |
| S3 | Performance: an on/off toggle | P129 rules (`cc-toggle-states.md`): ON = accent glyph, no disc, value "On"; OFF = white glyph in a white @ 0.15 disc, value "Off"; 250 ms swap. Round (1x1) controls never show an on-state | it was a 1x1 circle by default (no state shown) and, as a pill, said "Fast" / "Balanced" | no toggle look at the default size | **FIX** the control's allowed sizes are now 2x1 (default) and 2x2: never a circle (a toggle needs a value line); the default More page has it as a 2x1; a layout saved with it as a 1x1 loads with the 2x1 (`CcLayout.fromJson` corrects a size the control no longer allows). Value "On" = Fast, "Off" = Balanced (the spoken label still says "Performance, Fast/Balanced"); it uses the same `PillContent` toggle styling as Wi-Fi (disc and glyph ease over 250 ms). Root gating is unchanged: the tile is only offered when rooted (`avail`), so there is no un-rooted tap to show a "needs root" notice for; `RootFeatures.setFast` is called as before. Not verifiable on the twin (root-only tile): covered by `CcLayoutTest.performanceIsAToggleSoItNeverStaysACircle`. S / low |

### Focus

| # | Item | Spec | Glass now | Gap | Status |
|---|---|---|---|---|---|
| F1 | Focus growth | SK: **96 -> 116 px: an absolute 20 px = 10 dp** of growth in total, so a circle is x1.21 and a wide tile much less (m). F27: 2x2 about +21% (e). AUD: 1.10 to 1.15, "the grid is tight, measure". User (P138): "about 1.06 to 1.1 with a gap that never closes" | `focusedScale = 1.10`, then x1.21 for everything (first pass): a 104 dp pill grew 11 dp a side past the 8 dp gap (screenshot `p138-spacing.png`: Sleep over Settings and Clean Up) | gutters did not fit the scale | **FIX** `CcSizes.focusScale(width) = 1 + 10 dp / width`: circle x1.21, pill and 2x2 x1.096, a 216 dp row x1.046; growth per side is at most 5 dp, under the 8 dp gap, so a focused control never touches a neighbour (at any text scale: the growth scales with the gap). The focused control is also raised above its neighbours (zIndex). **Test:** `CcFocusFitTest` packs every size (1x1, 2x1, 2x2, 4x1) in several mixed pages at text scales 1.0 to 1.5 and asserts the focused bounds overlap no neighbour. M / low |
| F2 | Focus shadow | FW radius 30 pt, y 30 pt (20 scaled), opacity 0.3; resting radius 20, y 4 (m) = 15 dp, 15 dp, 0.3 | baked (F06) `shadow = true` | same profile, baked | none |
| F3 | Focus fill and content colours | white 0.95, near-black content (FW, m) | `palette.focusFill`, `onFocusFill` | colour | **P137 / colour** |
| F4 | On/off transition | 0.25 s property animator (FW, m) | `TOGGLE_FADE_MS`, `FILL_FADE_MS` | none | none |

### Cards, platters, edit mode

| # | Item | Spec | Glass now | Gap | Status |
|---|---|---|---|---|---|
| N1 | Now Playing card | tvOS 27: a module of the panel (HomeKit camera card in the F27 HomeKit frame: full panel width, about 100 dp tall, radius about 24 dp, m) | `NowPlayingCard`: radius 24 dp, padding 12, art 46 dp, transport 34 dp, captions 13 sp (`NowPlayingViews.kt:180-215`); placed under the grid in the panel (P136 `bc9ceb7`) | width follows G1; type 13 vs 11.5 sp is a legibility choice for a text-heavy card | **FIX** width only (derived); type **DEFER** (no tvOS Now Playing card in the frames) |
| N2 | AirPlay platter / Audio picker | FW `tvos-airplay-picker.md`: title row (title + audio format), separator, group routes, "Headphones" header, "Speakers" header; rows format 8 (96 pt = 48 dp tall rows, spacing 10 pt = 5 dp, groups 20 pt = 10 dp); title style 6 Caption 1 12.5 sp, subtitle style 7 Caption 2 11.5 sp; `checkmark.circle.fill` / `circle`; volume row 54 pt = 27 dp (m) | `CcAirPlayPlatter` / `CcRouteRow` / `CcVolumeRow` (`:1720`, `:1820`, `:1845`): rows `sz.pill` (56 dp), gap = `sz.gap`, section headers 13 sp SemiBold, volume row `sz.pill` (56 dp), permanent 0.15 disc with a 18.5 dp glyph | rows 56 vs 48 (G1 fixes), spacing 9 vs 5, volume row 56 vs 27, route glyph in a disc (tvOS format 8: bare glyph), headers 13 vs 12.5 sp, no audio-format in the title, no separator | **FIX**: rows follow u; route glyph bare 21 dp; row spacing `gap`; volume row 27 dp tall; headers 12.5 sp. The audio-format title and the "Headphones" section: **DEFER** (no data source on the stick). M / med |
| N3 | Remotes platter | pushed platter: same row anatomy as N2 (FW) | `CcRemotesPlatter` (`:1687`): `sz.pill` rows with disc glyph | follows N2 | **FIX** (via u and the bare-glyph rule) |
| N4 | Platter transition | spring mass 1, stiffness 150, damping 20; zoom from the pill; pop 0.5 speed-up (FW, m) | `PLATTER_STIFFNESS 150`, `PLATTER_DAMPING 0.8165`, from-scale 0.3 (`:112-114`) | matches | none |
| N5 | Confirm card (Restart) | FW `standardAlertLayoutContentInsets` 45/24/20 pt (alerts), alert buttons 440 x 66 pt (SK) = 220 x 33 dp; radius 24 dp platter | `CcConfirmCard` (`CcShowcase.kt:221-240`): width `sz.column`, radius 26, padding 14 x 8, buttons 42 dp tall, gap 6 | button 42 vs 33 dp; radius 26 vs 24 | **FIX** radius 24; buttons 33 dp tall would hurt focus targets: keep 42 -> **DEFER** button height, mark unclear (alerts differ from CC cards) |
| N6 | Edit mode: hint toast, jiggle, remove badge | none in tvOS (SPEC) | key-cap hint, `18.dp` badge, +-0.8 deg | n/a | none |
| N7 | Gallery ("Add a Control") | none in tvOS (SPEC) | pills of `sz.column` x `sz.pill` | follows G1 | **FIX** (derived) |
| N8 | Add-on / Downloads / Power / Restart tiles | none in tvOS: same sizes as their `CcSize` | `AddonTile`, `ShowcaseTile` use `CcSizes` | follow G1 and the pill rules | **FIX** (derived); verify in shots |

### Type scale (reference)

tvOS: Caption 2 23/30 px, Caption 1 25/32, Body 29/36, Callout 31/38, Headline 38/46, Title 3 48/56 (SK Type Styles, m). In sp: 11.5, 12.5, 14.5, 15.5, 19, 24.
Glass tokens: caption 13 sp (the legibility floor, `ui/Theme.kt` caption). Control Center now uses 11.5 sp locally for pill text only (C10); a global Caption 2 token is **DEFER**.

## Open questions (unclear spec)

1. "Panel takes the full screen height": no source shows a full-height **slab**; the 27 frames show the grid at content height with no background, and the firmware's platter is 860 pt. P1 gives the column the full height on every page (as the user asked), so pushed platters fill it, but the grid page's controls stay at the top of it. If the user means a visible full-height panel (a drawn background), that is a materials decision.
2. Pill title weight, 2x2 title style and symbol weight (C3, C10, C18): no decoded constant for 27; measured by eye on a compressed frame.
3. Whether tvOS 27's menu pills (Audio, Sleep Timer) are discless in the product (F27 says yes; KIT says every 2x1 has a disc).
