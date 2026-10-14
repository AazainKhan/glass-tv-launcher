# Resume playback from the Top Shelf, per app (P140)

Goal: a Continue Watching card should start playback at the same source and position, not open the title page.
Constraint: only what apps publish for launchers (TV provider rows) and public intent URIs. No app data, sessions or tokens.

## What Glass does today

- `TvRows.item()` maps a Watch Next / PreviewProgram row to a `FeaturedItem`: `link = intent_uri`, `packages = [package_name]`,
  `progress = last_playback_position_millis / duration_millis` (the hero button already reads "Resume" with a bar when set).
- `FeaturedItem.open()` (FeaturedSources.kt) launches it: an `intent:` URI is parsed and started as published; any other link is a
  VIEW sent to each installed item package, then to anyone; last resort is the package's launcher intent.
- Gap (security, fixed in step 2): the `intent:` branch started the published intent unrestricted, so a hostile app could
  publish a row that targets another package or a Glass component.

## Findings

The position is app-side. `last_playback_position_millis` is only metadata for the launcher's progress bar; no app takes it back
through the intent. Whether the tile resumes depends on the app's own `intent_uri`. Android TV's docs make
position and duration required only for `WATCH_NEXT_TYPE_CONTINUE` and say tiles should deep link into playback
([Watch Next programs](https://developer.android.com/training/tv/discovery/watch-next-programs),
[codelab](https://developer.android.com/codelabs/watchnext-for-movie-tv-episodes)); the page does not state that the intent must resume (the
guidelines page it links was not read). So launching the published `intent_uri` exactly is the correct generic form.

| App (package) | Own row supplies | Deep link goes to | Glass can do without private data |
|---|---|---|---|
| Any app with a Watch Next row | `intent_uri`, position, duration, type | Chosen by the app; CONTINUE rows are meant to resume (docs) | Launch the published intent, restricted to the app |
| Stremio (`com.stremio.one`) | Continue Watching preview row; `stremio:///detail/{type}/{id}[/{videoId}]` seen in rows (observed in Glass code, unverified for the player) | Detail page ([addon SDK deep-links doc](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/deep-links.md): only `board`, `discover`, `detail`, `search`) | No documented player/resume route. Published URI, else detail, else launch. Do not invent a player URL |
| Nuvio | Not verified: no public deep-link doc found | Unknown | Published intent, else package launch |
| Netflix (`com.netflix.ninja`) | Watch Next rows on Android TV (unverified: format not documented publicly) | Unknown | Published intent. JustWatch fallback link opens the title page |
| Prime Video (`com.amazon.firebat`), Disney+, Max, Hulu, Apple TV | Mostly no readable rows on Fire OS; rows if present (unverified) | JustWatch links are title pages | Published intent if a row exists, else JustWatch title link, else launch |
| YouTube / TizenTube / SmartTube | Watch Next on Google builds (unverified on Fire builds) | `vnd.youtube:ID` / `https://www.youtube.com/watch?v=ID` open the video; the `t=` start-time parameter is documented for the web player, not verified for the TV app | Published intent only; do not append `t=` |
| Plex (`com.plexapp.android`) | Watch Next row (unverified format) | Unknown. Glass's own On Deck source has no link | Published intent, else launch |
| Jellyfin | Watch Next row in the Android TV client (unverified) | Unknown | Published intent, else launch |
| Spotify | Preview rows of albums/playlists/episodes, `spotify:` URIs (unverified) | Opens the item; the TV app does not document autoplay from a URI | Published intent, else launch. No position resume |
| VLC | Watch Next with a custom scheme; a VLC issue shows such rows fail with "Activity not found" when the filter is missing ([issue](https://code.videolan.org/videolan/vlc-android/-/issues/1216)) | Playback | Published intent; catch ActivityNotFoundException, fall back |

Result: no app has a verified richer public resume form than its published `intent_uri`, so step 2 adds no per-app overrides
(it would mean guessing URLs). Resume depends on each app; the stick must show which apps' rows resume.

## Safe launch rules (implemented)

Order: published intent/URI, then (non-TV-row items) the existing VIEW chain, then package launch; each falls through on
`ActivityNotFoundException`, `SecurityException` or no resolver. Published intents must: have no selector; use action VIEW, MAIN,
or an action in the app's own package; carry package or component only equal to the item's package; use scheme http, https, market or a
custom scheme (never file, content, javascript, intent, android-app); never grant URI permissions. The intent is forced to
`setPackage(item package)`, and `resolveActivity` must succeed. Rows for Glass's own package are refused.

## P160: Resume presses one key after the launch

The Resume button (a card with progress) opens the item as before, then `RemoteKeysService.autoPlay` waits for the app's window and
its media session and sends ONE key (`ResumePlayback.decide`, tested): session playing -> no key; session paused -> `KEYCODE_MEDIA_PLAY`
through `AudioManager.dispatchMediaKeyEvent`; window up and no session after 1.9 s -> ACTION_CLICK on the focused node (what DPAD_CENTER
does, no root); 4 s with nothing -> give up, no key. The tile click and the Play button of an item with no progress still only open the
page. No Glass remote-keys service (APK mode with it off): the page opens and a one-line hint says to turn it on.

Per-app result. The Fire TV twin has none of these apps installed (only fillers), so none of the rows below could be run in this
session: the "expected" column is the code path each app should take, and Yin's stick run fills in "result".

| App | Page the published intent opens | Expected key | Result on the stick |
|---|---|---|---|
| Stremio | detail page (no player route) | no media session until playing: Select on the focused control (its Play button, if it is focused) | to verify |
| Netflix | title page or player, depends on the row | session appears when playing; paused session: MEDIA_PLAY | to verify |
| Prime Video | title page | usually no session on the page: Select on the focused control | to verify |
| YouTube / TizenTube | the video | session paused after load: MEDIA_PLAY | to verify |
| Plex | title page | no session: Select | to verify |

Limits that remain: Select presses whatever the app focused on first, which is its Play button only on some title pages; where it is not, one wrong
click can happen (never a second key). Apps that publish no row cannot resume at all.
