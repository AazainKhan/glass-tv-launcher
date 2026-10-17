# Remote keys an accessibility service sees without the key-layout module (P167)

Goal: open Control Center over any app in APK mode. Pro and OS builds get it from the `glass-remote-keys` module (the remote's Settings button
remapped to a code only Glass sees). Without root, Glass has only `RemoteKeysService`, an ordinary accessibility service with key filtering.

## How a held key reaches the service

The system makes key repeats after accessibility services have seen the key, so a held key arrives as one DOWN (repeatCount 0) and, later,
one UP; there are no repeats to count. A hold is therefore timed from the first down (the same way the Back escape hatch is, P87). Glass does not swallow
the first down: the app still gets the press, and once the hold has fired the UP is swallowed so the app does not also see a release.

## The choice

Settings > Remote Buttons > Control Center Shortcut: **Hold Menu** (default, 700 ms), **Hold Play/Pause** (800 ms), **Settings key** (press, for
remotes that have one: some Android TV remotes), **Off**. The shortcut acts over other apps only (on Glass's own Home, Menu keeps opening the app menu). With
the accessibility service off, the page shows one line saying to turn on Glass TV Launcher Remote Buttons in the TV's Accessibility settings.

## Key table

"Reaches the service" means `onKeyEvent` is called with the key. Evidence tags: **code** (this repo already relies on it), **framework** (AOSP's
input filter sees every key a window would get, before the window manager's policy), **unverified** (nobody has logged it on that device).

| Key | Stock Android TV | Fire OS (Fire TV Stick 4K 2nd gen, Fire OS 8) |
|---|---|---|
| Back | yes (framework) | yes, KEYCODE_BACK, scan code 158 (stick, P167; also code: the Back escape hatch and instant-back run on it) |
| Menu (the three-line key) | yes (framework) | yes, KEYCODE_MENU, scan code 139 (stick, P167): a hold opened Control Center over YouTube |
| Play/Pause, Rewind, Fast-forward | yes (framework) | Play/Pause yes, KEYCODE_MEDIA_PLAY_PAUSE, scan code 164 (stick, P167); Rewind and Fast-forward unverified |
| Settings key (KEYCODE_SETTINGS) | yes where a remote has one | the gear arrives as KEYCODE_UNKNOWN, scan code 185 (the module remaps 249 to 185), with the key-down only: its key-up never reaches an accessibility service (stick, P167). Fire does not send KEYCODE_SETTINGS; its Settings button is caught by Fire's key policy before apps and only reaches Glass with the module (code: P75) |
| Home | yes (framework) | no, scan code 172: Fire intercepts it (stick, P167; code: P87 comment in RemoteKeysService) |
| Voice / Alexa button | Assistant key: yes (framework) | consumed by Fire (Alexa) |
| Volume, Mute, Power | volume keys: yes (framework) | consumed by Fire's key policy |
| App buttons 1-4 | brand buttons: usually consumed by the launcher policy | consumed by Fire (reach Glass only after the module remaps them to gamepad codes) |
| D-pad, Select | yes | yes |

## How to fill the unverified cells (stick, 2 minutes)

A debug build logs every key that reaches the service: `adb logcat -s GlassRemote | grep "key KEYCODE"`, with Glass Remote Buttons turned on and an app
in front; press each remote button once. A key that never prints does not reach the service. Fill the Fire column from that.

The Fire TV twin emulator cannot do this: `input keyevent` is injected after the accessibility filter, the console's event send reaches no input
device on the AOSP image, and `sendevent` needs root. Only a real remote exercises the path.

## Why Menu hold

Menu is on every Fire remote and on most Android TV remotes, apps use its short press for their own menus (kept: the press still reaches the app) and
rarely use a hold. Play/Pause is the fallback for remotes without Menu; the Settings key is the cleanest where it exists.
