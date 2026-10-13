# Playing on a Sonos speaker (P121)

Glass captures what other apps play (AudioPlaybackCapture: USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN) and streams it to one Sonos speaker.

## Consent: the same in APK, Glass Pro and Glass OS

- RECORD_AUDIO (runtime permission) and the MediaProjection dialog are asked **every session**.
- No appops pre-grant, no silent grant, not even with root or as a system app. `CaptureConsentActivity` is the only path.
- Manifest: RECORD_AUDIO and FOREGROUND_SERVICE_MEDIA_PROJECTION, both normal/runtime. No privileged permission.

## Opt-outs are never bypassed

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
