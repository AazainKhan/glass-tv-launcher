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

## Phase 1: provider points (Top Shelf, Home row, Settings page)
- Declared on the add-on's service: metadata `dev.glasslauncher.addon.provider` (authority) and `...points` (`tiles,topshelf,homerow,settings`). The provider must be the add-on's own, exported, with `android:permission=BIND_ADDON`; otherwise Glass drops the provider points.
- Wire: `ContentResolver.call` (unstable client, 2 s, one call in flight per add-on, same mute rules as tiles). Methods `getFeed`, `getHomeRow`, `getSettingsForm`, `onSettingsAction`; the argument is a `ProviderRequest` carrying the user's current settings, which is how an add-on reads them back. Replies may carry `error`.
- Base class `GlassAddonProvider` (permission re-check, exceptions become error replies); pure `AddonProviderServer`.
- Glass cleans everything (printable text, caps, 30 items, 12 fields), keeps only https art and https/market links, fetches art itself at the shown size (the 1280 px long side is a guideline for authors; decode size is Glass's), and builds every Intent itself: a validated link, an activity of the add-on's own package, or its launcher entry. Never Glass, never another package, no URI grants.
- Top Shelf: Settings > Top Shelf Content > "<add-on> (add-on)" (`FeaturedSourceId.Addon`); never cached to disk or memory, so switching the add-on off clears it; heading ends "· via <add-on>". Home row: after the app grid, same mark and "via". Both are asked for again after the add-on's `refreshMinutes` (15 min minimum, 30 default) or when its settings or approval change.
- Settings page: Settings > Add-ons > "<add-on> Settings": toggle, choice, text (TV keyboard), action; Glass stores values in `addonSettings` (`pkg/field`).
- Not done: remote-button action, SDK publishing, template, 10-minute guide; refresh on every Home focus; dynamic forms (the form is read when the page opens); Top Shelf cover-flow square art rules; PhairPlay migration.

### Phase 1 hardening
- Art: add-on image URLs go through `AddonArt`, a dedicated OkHttp client wired in as a Coil interceptor (the general client is never a fallback for them): https on every hop, no https-to-http redirects, max 3 hops, public names only (no localhost, local-network suffixes or IP literals; DNS answers that are private, loopback, link-local, CGNAT or unique-local are refused on every lookup), 2 MB cap enforced while reading, static JPEG/PNG/WebP only (GIF, APNG and animated WebP refused), at most 1280 px on the long side, both read from the header before decoding. Market links: only `market://details?id=<package>`.
- The 15-minute floor holds for everything an add-on or its re-signing could cause. Exceptions, all explicit user actions: changing one of that add-on's settings, switching it on, and Settings > Top Shelf > Refresh Now. A failed Home-row read keeps the last row up to 30 min and retries after 90 s; at most 3 add-on rows.
- Stored settings are purged when the add-on is uninstalled and for fields it stops declaring. The provider is re-checked to be the add-on's own right before it is acquired; acquiring is inside the 2 s budget and the client is always closed.

## Phase 1b: remote-button actions
- Point `actions` on the add-on's service (same bind, `BIND_ADDON`, cert pin, 2 s, one call in flight, mute, 1 call/s per action). Codes `GET_ACTIONS=5`, `ON_ACTION=6`; `AddonHandler.actions()` and `onAction(id)` (returns an optional notice, cleaned, shown with RemoteNotice).
- Stored in the button mapping as `addon:<package>/<service>/<action id>`; a malformed or absent one is "unavailable" and does nothing. Picking it in Remote Buttons > a button > "Add-on actions" is the consent. Glass binds off the main thread on press and runs nothing for the add-on.
- Emulator: picker and mapping verified by screenshot. Injected keys (`input keyevent`) skip the accessibility key filter and the AVD has no key device, so the physical press was exercised with a temporary broadcast into the same code path (not committed): the sample returned "Counter: 1". The real press needs the stick.

## Phase 1c: refresh on focus, dynamic forms
- Focusing an add-on's Home row, or showing its Top Shelf, asks for a refresh (`FocusRefresh`): only while Home is visible, only after the 15-minute floor (or an explicit user change), one at a time, off Main. Focus never polls.
- Forms: `FormField.showIf` (one rule, depth 1) and `optionsFrom` + provider method `getFieldOptions` (`OptionsReply`, up to 8 options, asked again only when the other field's value changes, cached per value). Glass evaluates rules from the stored values; a broken rule drops the field; a dependent choice's stored value is reset when its dependency changes.
- Review fixes: dependencies (showIf and optionsFrom alike) are depth 1 and resolved against the final field list; options reads are capped at 20 a minute per add-on; a choice never shows a value that is not stored ("Not set"), and a failed options read shows "Unavailable" and retries on focus. A Home row refresh is per add-on, one at a time, re-checks that Home is visible right before the call, and a call that was not made (rate limited, muted) never overwrites a fresh interval mark. Stored values are purged only for ids a valid reply no longer names.
