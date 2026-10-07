# Device end-to-end tests

Drives the real Fire TV stick the way a person does (remote presses) and checks what's on screen, for
every important thing in Glass. Complements the JVM tests in `app/src/test` (screenshots, focus crawl,
unit tests), which can't see Fire OS.

```bash
scripts/e2e                    # everything except the perf gate (~20 min)
scripts/e2e --build            # build and install a release first
scripts/e2e -m "not slow"      # skip tests that launch other apps
scripts/e2e -k control_center  # one area
scripts/e2e -m perf            # frame times / memory / idle CPU (scripts/perf-gate)
```

Failures leave a screenshot and the accessibility tree in `build/e2e/`.

## How it works

- **Input:** `scripts/key`, real remote events: the Bluetooth remote's input node, or a temporary
  virtual remote (`tools/uinput/glass-press`) when it sleeps. Injected keys (`input keyevent`,
  UiAutomator, Espresso) can't be used: Fire OS doesn't let an injected Select click Glass's tiles.
- **State:** `uiautomator dump`. Compose test tags are resource-ids (`app:<package>`, `dock`, `clock`,
  `date`, `cc-clock`, `control-center`, `move-banner`…); every overlay is tagged `overlay-top:<Type>`
  (`ControlCenter`, `AppMenu`, `Settings`…; R8 keeps those names, see proguard-rules.pro). Labels are
  content descriptions, and the focused node is marked.
- **Page objects** (`pages.py`): Home, ControlCenter, Settings, AppMenu. `focus()` steers by on-screen
  position one press at a time and checks where focus landed, scrolling lists as needed. Nothing uses
  fixed key paths or sleeps; everything waits on a condition (`wait_for`).
- **Every test** starts on Home at the top with nothing open, and afterwards checks that Glass didn't
  crash, stop responding, or restart (`conftest.py`). Tests that change something put it back (theme,
  text size, AirPlay, performance profile, a hidden app, the Appstore's launcher).

## What's covered

| Area | File |
|---|---|
| Top row, grid, focus labels, row ends, Back/Home to the top, full-screen shelf | `test_home.py` |
| Control Center: every control, clock (seconds, AM/PM, right-aligned), date, weather, Appearance, Text Size, AirPlay, Free Memory, Performance, status pill | `test_control_center.py` |
| Settings: sections, page push/pop, Featured Row options, Root page and log | `test_settings.py` |
| App menu: actions, Edit Home Screen, Move to, Hide/show again, Uninstall confirmation, App Info | `test_app_menu.py` |
| Folders, folder menu, app switcher, TV Settings | `test_folders_and_switcher.py` |
| Opening apps and coming back to their tile, Home on Home, Appstore round trip, remote buttons (Recents, Settings, app button) | `test_launch_and_remote.py` |
| Device setup Glass depends on (Home app, one instance, remote-button service, permissions, root modules, Fire's Home-key flash off, network) | `test_device_setup.py` |

Bugs these tests found when first run: Back from the grid only dropped focus (needed two presses);
Home pressed within 1.5 s of the Appstore opening left you in Amazon's launcher.
