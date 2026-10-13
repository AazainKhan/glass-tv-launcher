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
