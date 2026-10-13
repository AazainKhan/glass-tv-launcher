# Add-ons phase 0 (A1): Control Center tile

## Built
- `:addon-api` (module, package `dev.glasslauncher.addon`): `AddonApi` (LEVEL=1, action, permission, metadata keys, Binder codes), `TileState`, JSON `AddonCodec`, and `GlassAddonService` (base class for add-ons).
- `:addon-sample` (`dev.glasslauncher.addon.sample`): counter tile (Select +1) and a value tile. Own APK; `:app` does not depend on it.
- `:app` `dev.glasslauncher.addons`: `AddonRules` (discovery filter, trust, call policy, reply limits; pure), `AddonScanner` (PackageManager), `AddonClient` (bind, one transact, unbind), `AddonHost` (state for Control Center). `home/AddonTile.kt` draws tiles with the existing `Round`/`CcTile`.
- Settings > Add-ons (row appears only when an add-on is installed): per-add-on switch. `LauncherConfig.addonsEnabled` = package -> signing-cert SHA-256.

## Contract
- Add-on: exported service, action `dev.glasslauncher.ADDON`, `android:permission="dev.glasslauncher.permission.BIND_ADDON"`, metadata `dev.glasslauncher.addon.{api,min_glass_api,title,icon,needs_root}`.
- Wire: one JSON string in, one out, over raw `IBinder.transact`. Codes: 1 getTiles, 2 getTileState, 3 onTap (4 was onLongPress: dropped, never reuse; hold-Select is Control Center edit mode, and a tile's only action is tap). Tap returns the tile's new state. Replies may carry `error`; an add-on exception becomes one.
- `TileState(id, label, icon, on, value, enabled)`. `icon` is a glyph key Glass maps to its own drawables: add home code play music movie weather speed power download tv info settings (else puzzle piece). Ids `[A-Za-z0-9_.-]{1,40}`, max 8 tiles, label 24 / value 18 chars.
- Additive only: unknown fields ignored; Glass accepts `api >= LEVEL-1` and `min_glass_api <= LEVEL`.
- One add-on service per package (the first by class name is read). Text from an add-on (title, label, value) is cleaned to printable characters: bidi, zero-width and control characters are dropped, caps apply by code point. Add-on tiles carry a small puzzle-piece mark; Settings shows package and a certificate fingerprint prefix.
- Trust: Glass checks it owns the BIND_ADDON permission, and re-checks package and cert right before every bind. The pin is the current signing cert only and ignores `signingCertificateHistory`, on purpose: a key rotation switches the add-on off, and re-enabling shows old and new fingerprints in a confirm card.
- Policy: bind per call, one call in flight per add-on, tiles read at most every 30 s with 3 binds at a time, replies that do not parse count as failures,, 2 s timeout, one call per tile per second, 3 failures in a row mute the add-on (Settings shows "Paused"; switch off and on to resume).

## Write an add-on (10 lines)
1. New app module with `implementation(project(":addon-api"))` (or copy the two contract files).
2. Do NOT declare the permission; only require it on the service.
3. `class MyAddon : GlassAddonService()`; override `tiles()` and `onTap(id)`.
4. Return fresh `TileState`s; keep state in storage (Glass unbinds after each call).
5. Manifest: exported service, the intent filter, `android:permission`, the metadata keys.
6. Install; Settings > Add-ons > switch it on; Control Center > Add a Control > Add-ons.

## Not done
- Emulator-verified on the first cut (bind, permission denial from shell, tap, cert change, cold start about 0.3 s); the review changes (error replies, confirm card, mark) are JVM-tested only.
- Run `scripts/privapp-check` before the stick install (the manifest gained a permission).
- No catalog, repo, install flow, Root badge UI, revocation, signed-key pinning beyond the per-user cert match; no other extension points; PhairPlay not migrated; no SDK artifact or template.
- Settings > Add-ons is minimal (switch per add-on); the D2 add-on page is later.
- Add-on-driven refresh while Control Center is open (tiles are read on open and after a tap).
