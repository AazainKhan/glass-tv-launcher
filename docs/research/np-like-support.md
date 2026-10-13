# Now Playing: which apps expose seek and like through MediaSession (P145)

Glass reads only what a session publishes. Seek shows when the PlaybackState actions include `ACTION_SEEK_TO` and the item has a
duration. Like shows when the session has a heart or thumb-up/down rating type (with `ACTION_SET_RATING`), else when it has a
custom action whose id or label reads as like / favorite / heart / thumbs up. Everything below is **unverified**: no app was
run against it on the stick. Check each one with a real session (`adb shell dumpsys media_session`: look at `state=...actions=`,
`ratingType` and the custom actions list) during the stick pass.

| App | Seek | Like | Notes |
|---|---|---|---|
| Spotify | likely (tracks and podcasts; ads no) | likely, as a custom action (Android Auto / Wear expose "Add to Liked Songs" / "Remove from..."), not a rating | The action id and label flip when the state flips; Glass reads "Remove" / "Unlike" as liked. |
| YouTube Music | likely | likely, thumbs up as a custom action; may also publish a rating | A thumbs-down action exists and is ignored on purpose. |
| SoundCloud | likely | possible, a like custom action | Unknown on Android TV builds. |
| Amazon Music | unknown | unknown | Fire OS sessions may hide controls. |
| Plex / Plexamp | likely seek (video and music) | Plexamp: possible rating; Plex video: unlikely | Plexamp star ratings are a 5-star style, which Glass ignores (only heart and thumb count). |
| Jellyfin | likely seek | possible favorite action | Unverified. |
| Kodi | likely seek | unlikely | Kodi's session exposes transport; no rating known. |
| VLC | likely seek for files, none for live streams | none expected | |
| Netflix, Disney+, Prime Video, Apple TV | video: sessions often missing or limited | none | Netflix publishes little to MediaSession on TV; expect no scrubber or no card at all. |
| Podcast apps (Pocket Casts, AntennaPod) | likely seek | unlikely | Pocket Casts may publish a "star" action; Glass does not match "star". |
| Live radio / streams | no (duration 0 hides the scrubber) | varies | |

Matching is by whole words (so "dislike" and "unlikely" never match "like"), on the action id and its label.
Emulator check: debug builds carry a fake session, `adb shell am broadcast -n dev.glasslauncher/.debug.FakeSessionReceiver
--es cmd start --ez seek true --es like heart|custom|none --ei pos 62000 --ei dur 214000 --ez playing true` (and `--es cmd stop`);
it logs `onSeekTo`, `onSetRating` and `onCustomAction` to logcat under `FakeSession`. Grant the listener first:
`cmd notification allow_listener dev.glasslauncher/dev.glasslauncher.widgets.NowPlayingService`, then restart Glass.
