# Playing on a Sonos speaker (P121)

Glass captures what other apps play (AudioPlaybackCapture: USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN) and streams it to one Sonos speaker.

## Consent per install mode (P135, decided 2026-10-13)

| Mode | RECORD_AUDIO | MediaProjection dialog | How |
|---|---|---|---|
| Plain APK | asked at runtime | **shown every session** | `CaptureConsentActivity`, unchanged. Optional: the user may run the two adb lines in the README. |
| Glass Pro (root) | pre-granted | skipped | Before the consent screen, `ProjectionPregrant` runs through `Root.action`: `appops set dev.glasslauncher PROJECT_MEDIA allow` and `pm grant dev.glasslauncher android.permission.RECORD_AUDIO` (only if `appops get` doesn't say allow yet; never on Main). The glass-system module's `service.sh` also runs both every boot, after `sys.boot_completed`, next to the location grant. |
| Glass OS | pre-granted | skipped | Image item (rom repo, not this one): ship the same two as a default appops entry or a first-boot line. |

- With both set, the system's MediaProjectionPermissionActivity approves by itself and no dialog shows (verified on the stick).
- `CaptureConsentActivity` still runs in every mode; with the grants it just passes through.
- Manifest: RECORD_AUDIO and FOREGROUND_SERVICE_MEDIA_PROJECTION, both normal/runtime. No privileged permission.
- `SonosConsentPolicyTest` allows these grants only in `ProjectionPregrant.kt` and `glass-system-service.sh`; any other location granting RECORD_AUDIO or PROJECT_MEDIA fails the build.

## Opt-outs are never bypassed

- Skipping the dialog changes nothing here: the grants are about consent, not about what an app allows.

- An app with `allowAudioPlaybackCapture=false` is silent to the capture. Glass reads the flag where the platform exposes it and otherwise detects 5 s of silence while a media session plays.
- Then the platter says "<App> doesn't allow playing on other speakers" and the TV keeps the sound (the TV mute is released).

## Order and safety

- Start = consent, then capture, then `SonosSession.start`. Stop = stop the session and the capture, restore the TV mute.
- The speaker volume is never set. `SonosSession.start` replaces the speaker's queue (see its warning).
- "Mute TV while playing on Sonos" mutes STREAM_MUSIC locally, off by default, restored on stop.

## Robustness

- Every way out of a route (Stop, capture end, error, the speaker dropping, the projection stopping, the service being destroyed, Stop during start) runs one cleanup: stop the session (speaker Stop, HTTP server closed), release the capture, restore the TV mute, stop the service.
- "Glass muted the TV" is persisted; the next app start unmutes if a crash left it set.
- The TV is muted only after real (non-silent) PCM arrives, and released when an app is judged opted out. The speaker is polled with GetTransportInfo every 10 s; two misses end the route.
- Opted-out decision: flag false while a session plays, or 10 s of silence after sound was heard, or 15 s of silence when none ever came. No playing session means no verdict.
- The stream is served at a random 128-bit path, to the speaker's address only, one client at a time.

## Needs a stick check

- Whether the STREAM_MUSIC mute applies before the capture tap. If it does, muting the TV would silence the capture too (the PCM would go to zeros and trip the silence rule). Unverified here: no device was used.

Live results 2026-10-13: YouTube (TizenTube) capture was audible on a Roam 2. The Spotify TV app opts out, so it is silent, as designed. Whether the mute applies before the capture tap is still unverified.

## Mute TV on HDMI (2026-10-13)
On the Fire TV Stick, HDMI is a fixed-volume output (`dumpsys audio`: `mFixedVolumeDevices` includes HDMI). Android ignores `adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE)` there, so "Mute TV while playing on Sonos" is hidden on HDMI. While a route plays, the panel shows "Use your TV remote to mute" instead. The toggle stays available on speaker and Bluetooth outputs, where a local mute works (`MuteSupport`).

Ticket (untested, needs a CEC TV): in Glass Pro and Glass OS (HDMI_CEC granted), mute the TV with a CEC `<User Control Pressed: Mute>` through the playback client. Most TVs treat that as a toggle and Glass can't read the TV's mute state, so it could unmute a TV that was already muted. Read the audio status first (`<Give Audio Status>`) where the TV supports it.
