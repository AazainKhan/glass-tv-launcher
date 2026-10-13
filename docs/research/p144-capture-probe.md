# P144 capture probe: does Fire OS cap privileged capture?

Question: AOSP caps a privileged AudioPolicy capture at 16 kHz mono (`AudioMix.PRIVILEDGED_CAPTURE_MAX_SAMPLE_RATE`, `..._MAX_CHANNEL_NUMBER`). Does Fire OS 8 enforce it, or can a privileged app capture the system mix at 48 kHz stereo? This gates "route all TV audio to a network speaker" (Pro/OS only).

The probe captures the **system mix** of the chosen usages (no uid or package rule) for 4 s, keeps only a level and a format, discards the audio, and writes one text file. Debug build only; nothing streams.

## Run (user present, stick lock held)

1. `scripts/stick-lock hold yin --max 30 -- scripts/build debug --device`
2. Allowlist before the debug APK is trusted: `scripts/privapp-check <debug apk> --device`. It only adds `MODIFY_AUDIO_ROUTING` and `CAPTURE_AUDIO_OUTPUT` to the privapp list; the module refresh needs a **reboot** (the user is present). Without it, registration fails with a SecurityException.
3. Play audio on the stick (see below), then trigger (any one):
   - `adb shell am broadcast -a dev.glasslauncher.debug.CAPTURE_PROBE -n dev.glasslauncher/.debug.CaptureProbeReceiver`
   - `scripts/tv`-style app event: `glassdev://event?name=capture-probe` (payload = usages list, e.g. `game,unknown`)
   - Extra usages (default is `media` only): add `--es usages game,unknown` to the broadcast. Run them as **separate runs**.
4. Wait about 6 s. Read: `adb shell cat /sdcard/Android/data/dev.glasslauncher/files/capture-probe.txt` (fallback: `run-as`/filesDir) and `adb logcat -d -s GlassCaptureProbe`.

## What to play ("all TV audio"), one run each

Keep it playing for the whole 4 s: (a) Spotify; (b) a YouTube title; (c) a Netflix title (expect DRM or opt-out effects); (d) system sounds (navigation clicks, Alexa chime); (e) a game, with `usages game`; (f) a `usages unknown` run while an app with unclassified audio plays. The file reports level per run, so compare the runs.

## Reading the result

First line is the verdict:
- `CAP 16k/mono ENFORCED`: only 16 kHz mono was registerable or granted. Universal routing at music quality is not possible on this platform.
- `FULL FORMAT ALLOWED (48k stereo)`: full format granted. (`44k stereo` if only 44.1 worked.)
- `PARTIAL: ...`: something between (for example 48k mono).
- `REGISTRATION REFUSED: <reason>`: see failures. The `ladder attempts` line shows what each requested format did (48k stereo, 44.1k stereo, 48k mono, 16k mono).

Also read `AudioRecord granted` (the real rate and channels, which can differ from what was registered), `usages in rule`, and `level` (`AUDIO`, `SILENT`, `NO DATA`, rms in dBFS, peak).

## Silence: why, and how the file tells

`level ... SILENT` means all samples were zero. Check the line `active playback during capture`, which lists each live player's usage and `capturePolicy`:
- A player with `capturePolicy=3 (NONE)` (`ALLOW_CAPTURE_BY_NONE`) is excluded from capture by the framework: silence is correct, the app opted out. Spotify TV is expected to be `ALL`.
- Players listed as `ALL` or `SYSTEM` but still silent: a protected (DRM/secure) path, or the usage was not in the rule. Compare with a system-sounds run.
- `none` listed: nothing was playing, or the playback started late. Repeat.
- `NO DATA` with a granted format: the read returned nothing; treat as an inconclusive run.

## Failure modes

- `REGISTRATION REFUSED` with `SecurityException ... MODIFY_AUDIO_ROUTING` or `CAPTURE_AUDIO_OUTPUT`: the app is not privileged, or step 2 or its reboot was skipped.
- `NoSuchMethodException` or a hidden-API error: Amazon changed the hidden API; the message names the method.
- Registered for every format but data is zeros: see Silence.
- Another app already holds a conflicting AudioPolicy: unregister it first (rare).

## Revert

Nothing persists: the policy is unregistered in `finally` and the result file is the only artifact. The privapp additions are harmless. Remove with `adb uninstall dev.glasslauncher` plus `rm` of the result file if wanted.

## Platform evidence (read-only, no device)

`fireos-karat-decompiled` has only member indexes. `jar-framework.members.tsv` still lists `AudioMix.PRIVILEDGED_CAPTURE_MAX_SAMPLE_RATE`, `..._MAX_CHANNEL_NUMBER` and `..._MAX_BYTES_PER_SAMPLE`, so Amazon did not remove the cap fields. The values are not in the index. Whether the check is enforced is what this probe measures.
