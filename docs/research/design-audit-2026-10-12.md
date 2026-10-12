# Design audit: Glass vs tvOS 26/27 (2026-10-12)

**Scope:** what the user asked for, and only that: pop-ups and menus, styling consistency, Control Center behaviour and smoothness, transitions, and edit menus. This is a read-only audit; no code was changed and nothing ran on the stick.

**Code audited:**
- Everything on `origin/master` at `c536582`; line numbers refer to that commit.
- Control Center edit mode and the Remotes panel on branch `sdd/cc-edit` at `881962a`, cited as `ControlCenter.kt@881962a:NNN` because the line numbers differ from master.

## Method and sources

**tvOS reference (the 27 frames win when sources disagree):**
- **Measured motion:** the `tvos27-guidelines` skill: `references/motion-spec.md` (MS), `references/liquid-glass-motion.md` (LGM), `references/control-center-grid.md` (CCG), `references/visual-spec.md` (VS) and `references/materials-and-colors.md` (MC).
- **Frames:** `tvos27-inspo/screenshots/…` and the skill's `assets/screenshots/`.
- **tvOS 26.6 class-dump index:** class and member *names* only. Names are evidence of behaviour, not of values. Wherever a behaviour below is read from a name alone, it is labelled "inferred from name".
- **`animations.tsv`** (tvos-26-decompiled index) is built from the `.ca` glyph packages: the Sleep Timer ring, the Do Not Disturb moon, Volume and Spatial Audio. Its two "common" springs, m1/k435/d25 and m2/k300/d50, are glyph and state animations, not panel or overlay motion. This audit uses the file only for glyph and state findings.

**Converting the springs:**
- CoreAnimation (m, k, d) becomes Compose `spring(ζ = d / (2√(k·m)), stiffness = k/m)`.
  - m2/k300/d50 = `spring(1.02, 150)`: 90% at about 320 ms, 99% at about 545 ms.
  - m1/k435/d25 = `spring(0.60, 435)`.
- Timings for Glass's springs come from simulating each spring numerically, for example `spring(0.9, 260)`: 90% at 212 ms, 99% at 320 ms. The tvOS overlay fit `spring(0.9, 700)` reaches 90% at 129 ms and 99% at 196 ms.

**Glass visuals:**
- Roborazzi goldens in `app/src/test/screenshots/`.
- `build/clips/`.
- The P101 frames for edit mode, from the `sdd/cc-edit` worktree's `build/p101/`.

**Clean room:** only numbers, behaviour and symbol names are quoted here. There is no Apple code, no header text and no Apple asset.

**GPU cost on the stick:**
- **cheap:** constants, or fewer frames.
- **medium:** one extra draw while something animates, or a new baked bitmap.
- **risky:** an extra full-screen pass at rest, or a per-frame shader.

---

## Findings, ranked by how much a tvOS user would notice

### 1. Control Center opens about 2× slower than tvOS 27's "almost instant" one
- **Glass:** `CcMorph.openSpring = spring(0.9, 260)` (`home/ControlCenter.kt:134`), plus the tile stagger (`TILE_FROM 0.2`, `TILE_SPREAD 0.25`, `:152–153`).
  - The farthest tile starts at p = 0.45, so it is 90% grown at p ≈ 0.945. That is about **245 ms**, and it is fully landed at about **330 ms**.
  - The close is `spring(1, 450)` (`:136`): 90% at 184 ms. The overlay is removed at `CLOSE_MS = 320` (`:141`).
- **tvOS:**
  - The tile group is at 95% by **133 ms** and done by **167–200 ms**, with no overshoot (LGM §4 open table; MS §8).
  - Close totals **170–200 ms** (LGM §4 close).
  - "Faster Control Center" is a headline tvOS 27 change (TVOS27-DESIGN-NOTES §1, Motion).
- **Symptom:** holding the CC button gives a visible grow that is still settling about a third of a second later. Next to tvOS's snap, it feels laggy.
- **Fix:**
  - Keep the pill split, which is a **user decision** (comment at `ControlCenter.kt:128–131`).
  - Raise the open to `spring(0.9, 700)`. The skill's floor for a deliberate liquid bubble is `spring(0.85, 600)` (LGM §13, "Decision for the user"). At 700, the farthest tile reaches 90% at about 150 ms.
  - Make the stagger `TILE_SPREAD` 0.15.
  - Close with `spring(1, 900)` (90% at 130 ms), and lower `CLOSE_MS` to about 230.
- **GPU:** cheap. It needs fewer frames, and the same draws.

### 2. CC edit mode leaves the tvOS position and draws a box (branch `sdd/cc-edit`)
- **Glass:**
  - Edit mode moves the whole column to the screen centre (`ControlCenter.kt@881962a:658–671`, `EDIT_POPUP_MIN` at `:243`).
  - It draws a flat `Color.Black` at alpha 0.52, as a 30 dp round rect, behind the column (`:694–704`).
  - In `build/p101/edit-mode-1080p.png` and `gallery-1080p.png`, this reads as a dark rectangle with a hard vertical edge at x ≈ 1255 px, cutting through the hero.
- **tvOS:**
  - tvOS 26/27 Control Center has **no edit mode**. There are no edit or jiggle members under the TVSS*/TVSP* Control Center classes (class-dump index), and it is fixed (`CC-CUSTOMIZATION-RESEARCH.md`, headline).
  - The column always sits on the right: grid x ≈ 1416–1842 px, a right inset of about **78 px (39 dp)**, and its top at about 132–138 px (`control-center-homekit.jpg` × 1.5).
  - There is no slab behind the controls (CCG, "What is tvOS 18 styling"; LGM §4 "There is no panel").
  - The 27 rules are "Morph the real shapes, never a box" (MOTION-12) and "one material" (CCG, last paragraph).
- **Symptom:** entering edit mode slides the panel across the screen into a muddy box that matches neither Control Center nor Home.
- **Fix (also the user's direction):**
  - Keep edit mode anchored right, at the open position. Delete `editT`, the offset at `:658` and the backing at `:694`.
  - The 0.42 dim stays the only backing. Edit state is shown by the wobble and the minus badges alone.
- **GPU:** cheap. It removes one translucent rect and one layout animation.

### 3. CC edit mode is cut off by a hard row clip, and its controls sit off the grid (branch)
- **Glass:**
  - The viewport is cut to whole rows by `.layout { … rowsFit … }` and then a plain `verticalScroll` clip (`ControlCenter.kt@881962a:790–800`), so a scrolled row ends on a hard line.
  - In edit mode the height is capped at screen height − 48 dp (`:689`).
  - "Add a Control" is a full-column-width pill outside the grid (`:1104–1109`).
  - The hint line is centred and unbounded (`:1110–1114`). In `p101/edit-mode-1080p.png` it runs over the poster row ("Select Move · ≡ Size · ⏯ Remove · Back Done" at y ≈ 925 px).
- **tvOS:**
  - Scroll edges in 26 are soft. UIKit has `ScrollEdgeEffectView` with `PocketBlur`, `PocketMask` and `LuminanceAdjustment` subviews (UIKitCore class-dump; soft by default, inferred from names).
  - TVSystemPanelUI has `TVSPConstants.useSolariumFadeoutBlur` (inferred from name: Control Center platters fade out at an edge with a blur).
  - The platter layout is a fixed grid with nothing placed off it (CCG: u 48 / g 8 / pad 12 dp).
  - In iOS 18, "Add a Control" sits in the flow at the end of the page (CC-CUSTOMIZATION-RESEARCH §1).
- **Symptom:**
  - Rows end on a sharp cut while scrolling.
  - The add button and the hint text break the grid.
  - The hint is unreadable over posters.
- **Fix:**
  - Edit mode uses the full height, from the header's bottom to the screen's bottom.
  - Replace the hard clip with an alpha fade about **24 dp** tall at the top and bottom edges: one `DstIn` gradient on the column's own layer, only while it can scroll.
  - Make "Add a Control" a **2×1 grid cell** placed first-fit after the last tile, at the same pitch `u + g`.
  - Put the hint on one caption line under the menu bar, right-aligned to the grid's right edge.
  - Keep the lift (×1.28) inside `contentPadding`, so its shadow isn't clipped (skill, android-fire-tv "Lessons").
- **GPU:** medium. The mask is one offscreen layer, and only while edit mode can scroll. A baked gradient keeps it to one extra blend.

### 4. CC edit-mode glass shows mosaic blocks when the panel leaves the right half (branch)
- **Glass:**
  - The CC sheet is baked at 80 columns (`GlassMatch.CC_COLS`, `glass/GlassMatch.kt:80`) over the right half of the screen only (`CC_LEFT = 0.5`, `:78`).
  - Centred, the Add-ons and By App pills show stair-stepped blocks about 12 px wide across their right half (`p101/gallery-1080p.png`, x ≈ 940–1100 px).
- **tvOS:** each control is smooth glass tinted by what's behind it (SYS-01; LGM §4).
- **Symptom:** pixelated glass inside edit mode's Add a Control gallery.
- **Fix:**
  - Finding 2 removes the cause, because the panel stays inside the sheet's area.
  - If any CC surface can still reach x < W/2, sample the sheet with bilinear filtering (`FilterQuality.Low`, which is already used for the backdrop) and bake 160 columns.
- **GPU:** cheap (bilinear) or medium (a sheet four times larger, about 0.3 MB).

### 5. The edit-mode move uses a glyph spring that runs about 2.5× slower than tvOS overlays (branch)
- **Glass:** `editT` animates with `spring(1.0, 150)` (`ControlCenter.kt@881962a:553`). The comment at `:550–552` calls this "tvOS 26.6's own spring for a settled move" (from `animations.tsv`).
- **tvOS:**
  - m2/k300/d50 appears in `animations.tsv` only in `.ca` glyph packages, for example `TVSystemUIService.app/DoNotDisturb-Dark.ca` rows 1701–1734 (the moon's position, scale and colour), over 0.8 s. It is not panel motion.
  - Measured overlays run ≈ `spring(0.9, 700)` (LGM §3 table).
  - `spring(1, 150)`: 90% at 318 ms, 99% at 544 ms. `spring(0.9, 700)`: 129 ms and 196 ms.
- **Symptom:** entering and leaving edit mode drags for about half a second.
- **Fix:**
  - The motion disappears with finding 2.
  - Correct the comment so the glyph spring isn't reused for layout moves.
  - For edit-mode state changes (badges in, wobble start), use `spring(1, 900)` or `tween(160)`.
- **GPU:** cheap.

### 6. The confirm card (Glass's alert) inverts tvOS's alert layout and timing
- **Glass:** `ConfirmCard` (`home/Menus.kt:659–675`).
  - The card is **380 dp** wide.
  - The title and body are **centred**, but the buttons are `MenuRow`s with **left-aligned** labels (`ui/Controls.kt:153`) at `heightIn(min = 38.dp)` (`:114`).
  - A full-screen `Scrim` (0x59 black, `home/Overlay.kt:222`) sits inside `FullOverlay`, so the scrim scales 0.94 → 1 together with the card (`Overlay.kt:167–184`). Its edges are visible as a shrinking dark rectangle during the 130 ms fade.
- **tvOS:**
  - Alert panel about **504 px (252 dp)** wide, buttons **440 × 66 px (220 × 33 dp)** (VS §8, §11).
  - The title and body are **left-aligned**, and button labels are **centred** (`tvos27-inspo/…/00-09_software-update-alert-glass.jpg`).
  - Motion: the screen **dims first**, ramping over about 230 ms. The card then **fades in at full size** over about 70–100 ms, with no scale (LGM §5, Alert).
- **Symptom:** a wide, centred, Android-looking dialog whose dark backdrop visibly zooms in.
- **Fix:**
  - Width 252 dp.
  - Title and body start-aligned.
  - Buttons as 220 × 33 dp capsules with centred labels (a `centered` flag on `MenuRow`).
  - Draw the scrim outside the scaled layer. Fade it over 200 ms, then fade the card with no scale, starting about 80 ms later.
  - Apply the same to `CcConfirmCard` (`home/CcShowcase.kt:216–240`, 26 dp radius, left-aligned button text).
- **GPU:** cheap. The scrim is already drawn; it just stops scaling.

### 7. Settings opens as a scaled full-screen box, not from its tile
- **Glass:**
  - The Settings tile and the Settings page use `SettingsPage` → `FullOverlay` (`home/Overlay.kt:325–328`, `167–184`): `alpha` and scale 0.94 → 1 over `Motion.overlay()` = `tween(130, FastOutSlowIn)` (`ui/Motion.kt:32`).
  - The page is an opaque gradient, so mid-fade Home shows around a shrinking rectangle with hard edges.
- **tvOS:** Settings is an app. It opens from its icon with the launch zoom: instant blur, a rounded window, τ ≈ 130 ms, 90% at about 290 ms (MS §9; MOTION-09). Pages inside push sideways over 200 ms (MS §6).
- **Symptom:** the only "app" on Home that doesn't open like an app, and a visible box edge for 2–3 frames.
- **Fix:**
  - From the Home tile, route `Overlay.Settings` through the existing `AppTransition` window, using the page's own colour as the launch surface and the gear as the glyph.
  - From Control Center, fade with **no scale**, so the opaque page never shows an edge.
- **GPU:** medium. It reuses `AppTransition`'s window, which is already budgeted for app launches.

### 8. The Settings page in light appearance is flat grey with a dark ring, not tinted with a soft shadow
- **Glass:**
  - The page is a fixed `#F2F2F8 → #E3E6F0` light gradient, or `#22252E → #111217` dark (`home/Overlay.kt:331–333`), although the comment there says "tinted faintly by the scene's own colour".
  - The title uses `palette.primary` (`:338`).
  - The light focus capsule gets a 1.5 dp `0x800E1015` ring (`ui/Theme.kt:41`, `ui/Controls.kt:118`; golden `settings-light.png`).
- **tvOS:**
  - The light page is a pale hued gradient (lavender, mint or sky). The title is grey. The focused row is a white capsule with a **soft shadow and no ring** (`assets/screenshots/settings-light.jpg`; VS §8–9).
  - The skill's Android choice is "a hued pastel of the art's own colour" and "a ring **or a two-layer shadow**" for 3:1 contrast (android-fire-tv.md, Light appearance).
- **Symptom:** light Settings reads as a generic Android preference screen, with an outlined button.
- **Fix:**
  - Derive the two gradient stops from the baked backdrop's dominant hue (the value `WallpaperLoader` already computes), lightened to L ≈ 94–97%.
  - Title in `palette.secondary`.
  - Replace the ring with a pre-baked two-layer capsule shadow (contact y 1 dp at 0.18, plus soft y 4 dp, blur 8 dp at 0.12), drawn as a 9-patch.
  - Re-check 3:1 with the existing contrast test.
- **GPU:** cheap. It is a gradient and one bitmap per focused row.

### 9. The folder backdrop fades into blur instead of jumping to it
- **Glass:** `FolderView` uses `SnapshotBackdrop({ backdropEnter.value }, …)` (`home/Menus.kt:423–424`). That draws the sharp snapshot and then the blurred copy at `alpha = enter` (`home/Overlay.kt:217–221`), a 130 ms crossfade of two full-screen images.
- **tvOS:** "The home grid **jumps to heavy blur in one frame**" (MS §8, Folder open). Also: "Nothing fades into blur gradually" (LGM §6 rule 4).
- **Symptom:** the background visibly "melts" over several frames while the panel grows.
- **Fix:** draw the blurred snapshot at alpha 1 from the first frame, and skip the sharp copy. For the light veil, if wanted, fade a flat colour over 300 ms (MS §8).
- **GPU:** cheap, and actually cheaper: one full-screen draw instead of two during the open.

### 10. The Control Center header shows a two-line clock with seconds
- **Glass:** `rememberClock(cfg.clock24h, seconds = true)` (`home/ControlCenter.kt:484`, `widgets/StatusBar.kt:151–160`) gives "23:41:47" plus a date line (`p101/edit-mode-1080p.png`).
- **tvOS:** the menu bar shows a single short time, "19:07", in grey with no seconds and no date (`assets/screenshots/control-center.jpg`). The header is 70 px tall (VS §11).
- **Symptom:** a busy, stopwatch-like header, and the ticking seconds draw the eye while you navigate.
- **Fix:** `seconds = false` and one line. This is a **user decision** to revisit: seconds in Control Center were chosen on purpose (CLAUDE.md, "Idle").
- **GPU:** cheap. It saves a recomposition every second while Control Center is open.

### 11. The Remotes tile closes Control Center and opens a full Settings page; part of its text never shows (branch)
- **Glass:**
  - The tile runs `closeAll(); open(Overlay.Remotes)` (`ControlCenter.kt@881962a:997`), which plays two transitions: the 320 ms CC close, then a Settings page fade.
  - `RemotesPanel` (`Menus.kt@881962a:698–716`) has rows that do nothing on Select (`MenuRow(r.name, {})`, `:706`, `:708`).
  - It calls `Hint` twice (`:712`, `:714`), but the page's help slot shows only `captions.firstOrNull` (`home/Overlay.kt:350`). The second hint, the 12-second reset instructions, is **never displayed**.
- **tvOS:**
  - Control Center items push their detail *inside the panel*: `TVSSMenuItemPanelHostingViewController` swaps a hosted view controller with a focus heading (class-dump, inferred from names).
  - Settings rows are actionable (SYS-02).
- **Symptom:**
  - Leaving Control Center to see your remote's battery feels heavy.
  - Rows that ignore Select feel broken.
  - The recovery steps are missing.
- **Fix:**
  - Show the remotes list as a Control Center page (like the Alexa page), pushed with the 200 ms side push (MS §6).
  - Make the rows non-focusable text, or give them a detail.
  - Join the two hints, or show every caption.
- **GPU:** cheap.

### 12. Top Shelf follows dock focus late
- **Glass:**
  - The hero waits `APP_HERO_MS = 250` (`home/HomeScreen.kt:142`, `:199–203`), then swaps with a 200 ms fade (`APP_HERO_FADE_MS`, `:144`, `:369`). That is about 450 ms when cached, plus a bake of about 245 ms when not.
  - Titles wait `TITLES_AFTER_MS` (`:152`, 1.5 s) and crossfade over `SLIDE_FADE_MS = 550` (`featured/TopShelf.kt:78`, `:98`, `:182`).
  - `SHELF_FOLLOW_MS` (`HomeScreen.kt:140`) is defined but unused.
- **tvOS:** new art starts about **100 ms** after focus moves. The old art dims for about 230 ms, then a **270 ms ease-in-out crossfade** runs, about 470 ms in all (MS §4).
- **Symptom:** the shelf changes noticeably after the tile has settled. On uncached apps it lags by nearly ¾ s.
- **Fix:**
  - Start the dim at 100 ms; keep the 250 ms delay only for the bake.
  - Make the hero crossfade a 270 ms `FastOutSlowIn`.
  - Shorten the title crossfade to 270 ms.
  - Delete `SHELF_FOLLOW_MS`.
- **GPU:** cheap for the timings. Starting a bake sooner while browsing the row is **risky** (bakes during D-pad moves). Keep the bake gate.

### 13. Android Toasts and an un-animated, hard-edged banner
- **Glass:**
  - `android.widget.Toast` in Settings (`settings/SettingsPanel.kt:529`) and Control Center (`home/ControlCenter.kt:984`): Fire OS's grey toast at the bottom.
  - The Sleep Timer banner is a flat `#E6202024` drawable with a hard 1 dp `0x33FFFFFF` stroke at the top centre, added and removed with no animation (`system/RemoteKeysService.kt:39–61`).
- **tvOS:**
  - System banners and toasts belong to the menu-bar system: `TVSSMenuModeBannerContext`, `TVSPBannerPillContentView` and `TVSPBannerConstants.bannerAppToastMinimumSize` / `…MaximumSize` (class-dump, inferred from names). They appear as a glass pill at the top right (PAGE-04, `now-playing-pill.jpg`).
  - Overlays come from their source and mirror on exit (MOTION-05).
- **Symptom:** a stock Android toast inside a tvOS-styled launcher, and a banner that pops in and out in one frame.
- **Fix:**
  - One `GlassToast`: a capsule at the status pill's spot, top right, on the CC sheet's material.
  - It scales from 0.9 and fades over 130 ms, mirroring on exit.
  - Text uses `Type.body`.
  - The banner is drawn in its accessibility window as a 1 dp soft rim (GLASS-02), not a hard stroke.
- **GPU:** cheap. It is a small surface and only exists while shown.

### 14. The Control Center dim is the dark-mode value in light appearance too
- **Glass:** `CC_DIM = 0.42` (`glass/GlassMatch.kt:72`), used as `CC_DIM_ALPHA` in both appearances (`home/ControlCenter.kt:422`), so luminance drops to ×0.58 in light.
- **tvOS:** ×0.69 in light (−31%), ×0.57 in dark (LGM §4, Background; LGM §13 dim comment: "D ≈ 0.31 light").
- **Symptom:** in light appearance, Control Center blacks out the pastel page noticeably more than tvOS does.
- **Fix:** `CC_DIM = if (light) 0.31f else 0.42f`. The sheet is baked muted by the same value (`GlassMatch.kt:90`), so pass the theme there too.
- **GPU:** cheap.

### 15. Control Center "on" state: a white disc where tvOS has a bare blue glyph
- **Glass:**
  - A pill's icon sits in a disc that animates to solid white when on (`discColor`, `home/ControlCenter.kt:1182–1185`; `PillContent`, `:1204`).
  - The glyph is `Blue = #0A84FF` (`:87`).
- **tvOS:** Wi-Fi on is a **blue glyph with no disc**, straight on the tinted pill. The second line is tinted secondary text (`tvos27-inspo/…/03-41_control-center-over-podcasts.jpg`, crop of the pill column).
- **Symptom:** "on" pills look like iOS 18 toggles: a white blob at the left of every connected pill.
- **Fix:**
  - When on, drop the disc fill and draw the glyph in system blue `#0091FF` (MC §3, dark).
  - Keep the disc only as a faint `fg × 0.16` well when off, if wanted.
  - The glyph's state change can use the moon's measured critically damped spring from `animations.tsv` (`DoNotDisturb-Dark.ca` rows 1701–1734: m2/k300/d50 = `spring(1, 150)`), instead of `tween(FILL_FADE_MS = 120)` (`:1178`).
- **GPU:** cheap.

### 16. iOS 18 colour tokens remain
- **Glass:** `accent` dark `#5AC8FA` and `danger` dark `#FF453A` (`ui/Theme.kt:35`, `:37`); CC `Blue #0A84FF` (`home/ControlCenter.kt:87`).
- **tvOS:** "#FF453A (red) and #5AC8FA (light blue) are iOS 18-era values". The 27 set is red `#FF4245` / `#FF383C` and blue `#0091FF` / `#0088FF` (MC §3).
- **Symptom:** the new-app dot and destructive rows are a shade off Apple's current blue and red, which you see next to Apple-styled content.
- **Fix:** dark accent `#0091FF`, dark danger `#FF4245`, CC blue `#0091FF`. Keep the darker light-mode text variants (MC §4).
- **GPU:** cheap.

### 17. The app menu and folder menu are two different kinds of pop-up, sized off tvOS geometry
- **Glass:**
  - The app menu is an `AnchoredMenu` popover beside the tile: **270 dp** wide, 26 dp radius, rows at `heightIn(min = 38.dp)` plus a 4 dp padding (`home/Overlay.kt:422–475`; `ui/Controls.kt:114`, `:149`). It animates with `tween(130, FastOutSlowIn)` (`ui/Motion.kt:32`).
  - The **folder** menu is a full-height right `SidePanel` (`home/Menus.kt:104–118`; `Overlay.kt:137–157`).
- **tvOS:**
  - Apps and folders share one context-menu path (`HBAppGridView` / `HBAppGridViewController` `contextMenuConfigurationForItemsAtIndexPaths…`, class-dump).
  - Context menu 440 px wide (**220 dp**), rows **70 px (35 dp)**, header 59 px (VS §11). This is tvOS 18 geometry, unconfirmed for 27.
  - Overlay motion ≈ `spring(0.9, 700)` and interruptible (LGM §3, §7; MOTION-14).
- **Symptom:**
  - Pressing Menu on a folder slides in a whole side panel, while on an app it pops a bubble.
  - The app menu is about 20% wider and taller than tvOS's.
- **Fix:**
  - Use `AnchoredMenu` for `FolderMenu` (and `FolderPicker`) too.
  - Width 220 dp, rows 35 dp.
  - Drive `rememberOverlayEnter` with `spring(0.9, 700)` open and `spring(1, 900)` close, so Back mid-open keeps its velocity (a tween restarts with none; LGM §7).
- **GPU:** cheap.

### 18. Home edit wiggle is rotation-only and its hint banner stacks two materials
- **Glass:**
  - `FocusTile` wiggle is ±0.8° rotation, `tween(190)` reversed (380 ms cycle), with **no position jitter** (`ui/FocusTile.kt:104–110`).
  - `MoveBanner` draws glass **and** a 0.62 black (or 0.72 white) scrim on top of it (`home/MoveHints.kt:64–69`).
- **tvOS:**
  - The Home grid's jiggle is two animations, `_jiggleTransformAnimation` and `_jigglePositionAnimation` (HeadBoardHome `HBAppGridView`, class-dump). Edit moves are one cell per press (`_handleEditLeft/Right/Up/DownGesture`), carried by a lifted proxy (`editingCellProxy`).
  - One material per surface (GLASS-04).
- **Symptom:** the wiggle reads stiffer than Apple's, and the banner is a muddy grey that is neither glass nor solid.
- **Fix:**
  - Add a ±0.5 dp x/y offset on the same clock, a quarter-cycle out of phase with the rotation (layer only).
  - Draw the banner as one surface: either glass at the "regular" tier (dark wash 0.41, MC §1) or the scrim alone.
- **GPU:** cheap. The banner saves one blended pass.

### 19. App close lands smaller and un-blurs slower than the reference
- **Glass:** `CLOSE_MS = 220` with `FastOutSlowIn`, `LAND_SCALE = 1.3`, `LAND_MS = 125` and `UNBLUR_MS = 200` (`home/AppTransition.kt:271–278`).
- **tvOS:** travel is an S-curve totalling about **340 ms**. It lands at about **1.5×** the focused tile and eases down over 125 ms, then the background sharpens over **100–130 ms** (MS §9, Close).
- **Symptom:** the return to Home feels slightly abrupt, then the blur lingers.
- **Fix:** `CLOSE_MS` 300, `LAND_SCALE` 1.5, `UNBLUR_MS` 120.
- **GPU:** cheap.

### 20. Full-screen overlay fades don't use `ModulateAlpha` (measure first)
- **Glass:** `SidePanel` and `FullOverlay` fade a full-screen `graphicsLayer { alpha = … }` with the default compositing strategy (`home/Overlay.kt:143–144`, `:173–178`). The 0.94 → 1 scale is also on that full-screen layer.
- **Reference:** CLAUDE.md, Performance rules: "Fades use `CompositingStrategy.ModulateAlpha`". An offscreen full-screen layer is an extra pass on a GPU that affords about two.
- **Symptom:** possible jank on menu and Settings open and close. Unmeasured.
- **Fix:** `compositingStrategy = ModulateAlpha` where children don't overlap. Otherwise drop the scale (see finding 7) and fade only the card. Verify with `scripts/perf-run`.
- **GPU:** medium. It needs a measurement.

---

## Open questions (not ranked)

- **CC focus scale.** Glass uses 1.02 (`ControlCenter.kt:1262`). The ×1.21 figure is the tvOS 18 Sketch value for a 1×1 button (CCG, Focus). The 27 frame shows the focused 2×2 Power Off at about 215 px against a 208 px cell, roughly ×1.03, so 1.02 may already be right for big tiles. A 27 frame with a focused round button would settle it.
- **CC primary tile radius.** Glass uses 26 dp on a 104 dp tile, about 25% (`ControlCenter.kt:933`). The 27 frame shows about 40 px on 215 px, about 19% or roughly 20 dp (VS §7). Low priority until it is checked on the stick.
- **D-pad tilt.** `TILT_DEG = 1.5` (`ui/FocusTile.kt:101–102`, `:256`) against "Tilt on D-pad move: none visible" (MS §2). This is still a **user decision** (MOTION-COMPARISON status #3).
- **Tile focus scale.** Glass uses 1.2 (`ui/FocusTile.kt:259`) against a measured 1.224 (MS §2): 6 px at 1080p, probably invisible.
- **Unmeasured in tvOS:** context-menu open and close timing, folder close, and edit-mode motion (MS §11, LGM §15). The numbers above for these are proposals.

## Shot list for the device tester (one batch, master build unless noted)

1. `scripts/clip` of a Control Center open and close from Home, three cycles, for finding 1's baseline and after the change.
2. Control Center over a sharp poster hero, still frame: check the master build for finding 4's mosaic outside edit mode.
3. App menu (Menu on a dock tile) over the dock, still: check whether the mosaic in the JVM golden `app-menu.png` also appears on the stick.
4. A confirm card (Remove Folder) in light and dark, still plus a clip of the open (finding 6).
5. Folder open, clip (finding 9).
6. Dock focus from one app to the next, clip, cached and uncached (finding 12).
7. Settings in light appearance, still with a row focused (finding 8).
8. On the `sdd/cc-edit` build: edit mode scrolled to the bottom of a full page, still (finding 3's hard clip).
