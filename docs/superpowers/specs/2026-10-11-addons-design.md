# Glass Add-ons platform (A1): design spec

Status: draft for the user's decision, 2026-10-11. Spec only; no code. Demand source:
`docs/research/fire-tv-demands.md` (untracked in the main checkout).

## 1. Goal and principle

Glass should be more than a launcher: people install add-ons, and anyone can write one. The principle that
makes this safe on a 2 GB stick: **Glass renders, add-ons only supply data and handle events.** Contracts are
declarative (rows, tile state, image URLs). Add-on UI is never drawn inside Glass, so Glass's own pipeline (the
P81 image gate, decode at shown size, the 120 MB PSS budget, GPU rules) governs every pixel.

Glass already has de-facto add-ons: PhairPlay (a separate APK toggled from Control Center) and
`glass-remote-keys` (remaps buttons). They are the reference migrations.

## 2. What an add-on is

A **separate APK**, own package, own signature, own process, updateable independently. It declares
extension points in its manifest; Glass discovers it through package visibility (`<queries>` for the
`dev.glasslauncher.ADDON` intent action; Glass already uses `<queries>`) and talks to it over Binder or a
ContentProvider. **No code is ever loaded into Glass** (no DexClassLoader, no plugin classloading). A
crash, leak or ANR in an add-on cannot take down Home.

Manifest metadata: `dev.glasslauncher.addon.api` (integer), `...min_glass_api`, `...title`, `...icon`,
`...needs_root` (bool, drives the Root badge, section 6).

## 3. Extension points

Each is an exported component guarded by a Glass-only permission
(`dev.glasslauncher.permission.BIND_ADDON`, signature-protected on Glass's side; the add-on declares that it
requires Glass to hold it), the same pattern as `BIND_QUICK_SETTINGS_TILE`.

| Point | Component | Add-on supplies | Glass does |
|---|---|---|---|
| Top Shelf provider | ContentProvider | `FeaturedFeed`/`FeaturedItem` rows (same fields as `featured/FeaturedSources.kt`), refresh hint | Renders in the Top Shelf, applies the image gate, owns the Cover Flow if the feed is square |
| Control Center tile | Service (bound on demand) | Label, icon, state (on/off/value), tap and long-press handlers | Draws the tile in the CC material; calls handlers |
| Home row / widget | ContentProvider | A row of items (title, image, intent) | Draws a normal row; `Select` fires the intent |
| Remote-button action | Service | A named action; Glass lists it in the button-mapping picker | Binds on press, with a timeout |
| Settings page | ContentProvider | A declarative form (toggle, choice, text, action) | Draws it in Glass's settings style |
| Screensaver | `DreamService` | Standard Android dream | Lists it next to Aerial in the picker |
| Theme pack (later) | Asset-only APK | Palette, wallpaper set, icon pack refs | Applies through the existing theme model |

A **custom drawing surface** (a canvas, a game) is deliberately absent: it is the main way to break the
frame budget. Add-ons that need real UI launch their own Activity.

## 4. SDK and developer path

- `glass-addon-sdk`: a tiny Kotlin library (Maven Central + source), under ~50 KB: base classes for each
  point, the row/tile data classes, an `AddonApi.LEVEL` constant, and a test harness that renders the contract
  as JSON.
- A template repo (`glass-addon-template`): one add-on per extension point, signing and CI preconfigured.
- Docs: "Add-on in 10 minutes": clone, change the tile label, `./gradlew installDebug`, see it in Control
  Center. Plus a manifest reference and the performance budget (section 7).
- A `scripts/addon-lint`: checks the manifest metadata, image sizes and refresh intervals before publishing.

## 5. Discovery and install

1. **Catalog in Glass** (Settings > Add-ons): lists add-ons from a **curated F-Droid-style repo** that we
   run (signed index, per-APK hash, changelog, declared permissions and extension points).
2. **Install**: Glass does not install APKs itself. It delegates to Droid-ify (or Obtainium) through its
   privileged install path, or falls back to a user-confirmed `PackageInstaller` session. Assumption to
   verify: that Droid-ify can silently install on this ROM with the glass-system module; if not, the
   confirm dialog is acceptable.
3. **Sideload** works for anyone: any APK declaring the action appears under "Installed", marked "Not from
   the Glass repo".

Why not install ourselves: Glass is a privileged system app behind an enforced privapp allowlist
(`scripts/privapp-check`, bootloop risk). Adding `INSTALL_PACKAGES` there is the wrong place to take that risk.

## 6. Security and trust

- **No privilege proxying.** Glass holds a Magisk su grant and privileged permissions. Its add-on IPC never
  executes root commands or privileged calls on an add-on's behalf (confused deputy). A root-only add-on
  (cache clear, sleep timer that needs `WRITE_SECURE_SETTINGS`) gets its own Magisk grant from the user and
  carries a **Root badge** in the catalog, driven by `needs_root` and checked against its manifest.
- **Signatures**: the repo pins each add-on's signing key; an update signed differently is refused. Sideloaded
  add-ons are trusted per package and key, shown once at first bind.
- **Permissions**: the catalog shows what each add-on requests, in plain language, and which extension points
  it uses. The user enables an add-on's points explicitly (off by default after sideload).
- **Network**: Glass fetches add-on images itself (so add-ons need no reason to expose raw bitmaps over
  Binder); data URLs are https only.
- **Review** for the curated repo: manifest and permission diff, no unneeded permissions, source link
  required, rebuild from source where possible, a kill switch (revoked key list) the catalog consults.

## 7. Performance budget (the stick's rules)

- No persistent add-on processes: **bind on demand**, unbind after the call, 2 s timeout, no more than one
  call per tile per second. Each live process counts against lmkd's 12 cached apps.
- Rows: at most 30 items per feed, images at most 1280 px on the long side; Glass decodes at shown size.
- Providers are polled on Home focus or on a declared interval of at least 15 minutes, never continuously.
- Glass keeps its ≤120 MB PSS: add-on data is rows and strings, not bitmaps. An add-on that breaks the
  contract (slow, crashing, over budget) is **muted** and shown as such, never able to stall Home.
- Each extension point gets a JVM contract test and a golden in Glass's existing `scripts/shots` loop.

## 8. Versioning and stability

Integer API level; additive-only changes within a level; Glass supports the last two levels and reads
`min_glass_api`/`target_api`. Unknown fields are ignored (as `FeaturedSources` already does with
`ignoreUnknownKeys`). A removed field is a new level with at least two releases of overlap.

## 9. Launch add-ons (by feasibility, mapped to demand)

| Add-on | Demand | Points | Note |
|---|---|---|---|
| Sleep timer + Reboot tiles | #14 | CC tile | Needs root for reboot; Root badge |
| Cache clear | #7 | CC tile + remote action | Root; reuses debloat tooling |
| SMB browser | #15 | Home row + own Activity | Needs an SMB client library; own UI |
| PhairPlay as an add-on | #11 (AirPlay) | CC tile + settings | The reference migration |
| Profiles (app sets per person) | #13 | Settings + home row | Uses the row contract, no root |

**Feasibility unverified** (listed, not promised): Google Cast receiver (#11; certification), private
listening (#12; system audio capture is restricted on Fire OS).

## 10. Phases and effort (one person, JVM + stick tests)

| Phase | Scope | Effort |
|---|---|---|
| 0 | Contract + permission + discovery for **one** point (CC tile) and PhairPlay migrated | 1.5 weeks |
| 1 | Top Shelf provider, Home row, remote action, settings page; SDK + template + 10-minute guide | 3 weeks |
| 2 | Catalog + curated repo + Droid-ify install; Root badge; revocation | 2 weeks |
| 3 | Launch add-ons (sleep/reboot, cache clear, SMB, profiles); theme packs | 3 weeks |

Total about 9–10 weeks; phase 0 alone proves the model.

## 11. Open questions (for the user)

1. Will Droid-ify's privileged install work without a confirm dialog on this ROM, or accept the dialog?
2. Do we run the curated repo ourselves (hosting, review time), or start sideload-only?
3. Is a Root badge enough, or should root-only add-ons live in a separate repo section?
4. Who may publish: only us at first, or open submissions with review?
5. Is a Cover Flow shelf from third-party covers desirable (image quality we don't control)?
6. Cast receiver: is a separate certification-free "Cast-like" receiver acceptable, or drop it?
7. Does a theme pack count as an add-on in phase 1, or later?
