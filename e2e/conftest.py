"""Fixtures for the device suite. Run through scripts/e2e, which takes the stick lock."""

from __future__ import annotations

import os
import re
import time
import subprocess
from pathlib import Path

import pytest

from pages import AppMenu, ControlCenter, Home, Settings
from tv import PKG, ROOT, TV

ARTIFACTS = ROOT / "build" / "e2e"


def pytest_configure(config):
    config.addinivalue_line("markers", "root: needs Magisk root on the device")
    config.addinivalue_line("markers", "slow: launches other apps or restarts things")
    config.addinivalue_line("markers", "perf: frame-time measurements (scripts/perf-gate)")
    config.addinivalue_line("markers", "emulator_gap: passes on the stick but not on the emulator (timing or rendering); skipped there, listed in P22")
    config.addinivalue_line("markers", "stick: needs the real Fire TV (Fire OS, Amazon apps, its remote); skipped on the emulator")


# Modules that only make sense on Fire OS: Amazon's launcher and apps, Fire's settings and Alexa.
# Tests elsewhere can opt in with @pytest.mark.stick. Everything else runs on the emulator too.
STICK_ONLY_MODULES = {"test_device_setup", "test_launch_and_remote"}


def pytest_collection_modifyitems(config, items):
    if os.environ.get("GLASS_TARGET") != "emulator":
        return
    skip = pytest.mark.skip(reason="stick-only: run with scripts/e2e --stick")
    for item in items:
        if item.get_closest_marker("emulator_gap"):
            item.add_marker(pytest.mark.skip(reason="emulator gap: passes on the stick (P22)"))
        elif item.get_closest_marker("stick") or item.get_closest_marker("perf") or item.module.__name__ in STICK_ONLY_MODULES:
            item.add_marker(skip)


def _only_device() -> str:
    """The single attached device; set ANDROID_SERIAL when there are several."""
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout.splitlines()[1:]
    serials = [l.split()[0] for l in out if l.strip().endswith("device")]
    assert len(serials) == 1, f"expected one device, found {serials}; set ANDROID_SERIAL"
    return serials[0]


@pytest.fixture(scope="session")
def tv() -> TV:
    serial = os.environ.get("ANDROID_SERIAL") or _only_device()
    owner = os.environ.get("TV_OWNER", "glass-e2e")
    t = TV(serial, owner)
    assert "device" in t.adb("get-state"), f"{serial} isn't connected"
    t.sh("input keyevent KEYCODE_WAKEUP")
    _preflight(t)
    return t


DATASTORE = f"/data/data/{PKG}/files/datastore/launcher.preferences_pb"


def _varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b, n = n & 0x7F, n >> 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def _config_json(raw: bytes) -> str | None:
    """The config JSON in Glass's DataStore file, when it is the file's one entry ("config" -> a string value)."""
    start, end = raw.find(b"{"), raw.rfind(b"}")
    if start < 0 or raw[:start].count(b"config") != 1 or end != len(raw) - 1:
        return None
    text = raw[start:end + 1]
    return text.decode("utf-8") if _datastore_with(text.decode("utf-8")) == raw else None


def _datastore_with(text: str) -> bytes:
    """A DataStore preferences file holding one entry: "config" -> the string [text]."""
    body = text.encode("utf-8")
    value = b"\x2a" + _varint(len(body)) + body
    entry = b"\x0a\x06config" + b"\x12" + _varint(len(value)) + value
    return b"\x0a" + _varint(len(entry)) + entry


def _write_datastore(t: TV, data: bytes) -> None:
    """Replaces Glass's saved config with Glass stopped (its DataStore would write its own copy back), then starts Home."""
    t.sh(f"am force-stop {PKG}")
    subprocess.run(["adb", "-s", t.serial, "shell", "su", "-c", f"'cat > {DATASTORE}'"], input=data, timeout=30, check=True)
    t.sh("input keyevent KEYCODE_HOME")
    time.sleep(8)
    subprocess.run([str(ROOT / "scripts" / "ensure-keys-service")], capture_output=True, timeout=60)


@pytest.fixture(scope="session", autouse=True)
def default_control_center(tv: TV):
    """The Control Center tests measure the default layout. A user's edited layout (the real stick has one) is set aside
    for the run and put back after, byte for byte. Needs root; without it, or with no edited layout, nothing is touched."""
    raw = subprocess.run(["adb", "-s", tv.serial, "exec-out", "su", "-c", f"cat {DATASTORE}"], capture_output=True, timeout=30).stdout
    text = _config_json(raw) if raw else None
    if text is None:
        yield
        return
    import json
    cfg = json.loads(text)
    if "ccLayout" not in cfg and not cfg.get("ccHidden"):
        yield
        return
    cfg.pop("ccLayout", None)
    cfg.pop("ccHidden", None)
    _write_datastore(tv, _datastore_with(json.dumps(cfg, indent=4)))
    try:
        yield
    finally:
        _write_datastore(tv, raw)


def _preflight(t: TV) -> None:
    """Stop the run with one clear line if the tree can't be read or keys don't move focus, instead of
    letting every test time out on its own (P22: 61 failures that were all the same problem)."""
    t.press("home")
    try:
        start = t.wait_for(lambda tr: tr.focused(), 10, "a focused node")
    except AssertionError:
        pytest.exit(f"preflight: no focused node in the accessibility tree of {t.serial} "
                    "(is Glass Home and is uiautomator dump working?)", returncode=3)
    # Home on Home scrolls back to the top and re-homes focus a moment later; a key sent in that moment is lost.
    time.sleep(1.5)
    for key in ("right", "left"):
        t.press(key)
        try:
            t.wait_for(lambda tr: tr.focused() and tr.focused().rid != start.rid, 10, "focus to move")  # a Cover Flow shelf slows the tree dump
            t.press("home")
            return
        except AssertionError:
            pass
    pytest.exit(f"preflight: Left/Right don't move focus on {t.serial} (stuck on {start.rid}); "
                "key input isn't reaching Glass", returncode=3)


@pytest.fixture(scope="session")
def rooted(tv: TV) -> bool:
    return "uid=0" in tv.su("id")


@pytest.fixture
def home(tv: TV) -> Home:
    """Every test starts on Home, at the top, with nothing open, and is checked for crashes after."""
    # adb commands don't count as activity, so Fire TV's sleep timer can run out during a long run.
    tv.sh("input keyevent KEYCODE_WAKEUP")
    marker = tv.now_marker()
    pid = tv.glass_pid()
    h = Home(tv)
    h.reset()
    before = _config(tv)
    yield h
    # A test that failed with Control Center up leaves it open, and every later test would run behind it.
    if tv.tree().find(rid="control-center"):
        tv.sh("input keyevent KEYCODE_BACK")
        time.sleep(1.5)
    # A Control Center window still attached once Control Center is closed is invisible but takes every
    # Select (seen once on 2026-10-09: no tile, menu row or button responded until it was removed).
    # Since P111 d the closed window stays parked (1x1, not focusable, not touchable), which takes no input: only a
    # window that still takes input counts.
    def stale_cc() -> bool:
        block = tv.sh("dumpsys window windows | sed -n '/Glass Control Center}:/,/mFrame=/p'")
        if not block.strip():
            return False
        parked = "NOT_FOCUSABLE" in block and "NOT_TOUCHABLE" in block
        return not parked and not tv.tree().find(rid="control-center")
    if stale_cc():
        time.sleep(1.5)  # it may still be playing its close
        assert not stale_cc(), "a closed Control Center left its window attached (it swallows Select)"
    after = _config(tv)
    # Flattened keys are "closedRecents.<pkg>": volatile by their top-level name.
    changed = sorted(k for k in set(before) | set(after)
                     if before.get(k) != after.get(k) and k not in VOLATILE and k.split(".", 1)[0] not in VOLATILE)
    assert not changed, f"the test left launcher settings changed: {changed}"
    crashes = tv.crashes_since(marker)
    assert not crashes, "Glass crashed or stopped responding:\n" + "\n".join(crashes)
    # A silent restart (process replaced without a crash line) also counts.
    now = tv.glass_pid()
    assert now, "Glass isn't running after the test"
    if pid and now != pid:
        restarted = tv.adb("logcat", "-b", "events", "-d", "-T", marker)
        assert "installPackageLI" in restarted or not re.search(rf"am_(proc_died|kill).*{PKG}", restarted), \
            f"Glass's process was replaced during the test ({pid} → {now})"


# Settings that change on their own (recent apps, seen apps, timestamps).
VOLATILE = {"recentApps", "closedRecents", "seenApps", "tipsSeen", "lastUpdateCheck", "seededDefaults"}


def _config(tv: TV) -> dict:
    """Glass's saved config (one JSON string in its DataStore), flattened one level: {"featured.mode": ...}."""
    import json
    import subprocess
    raw = subprocess.run(["adb", "-s", tv.serial, "exec-out", "su", "-c", f"cat /data/data/{PKG}/files/datastore/launcher.preferences_pb"],
                         capture_output=True, timeout=30).stdout.decode("utf-8", "replace")
    start, end = raw.find("{"), raw.rfind("}")
    try:
        data = json.loads(raw[start:end + 1])
    except Exception:
        return {}
    flat = {}
    for k, v in data.items():
        if isinstance(v, dict):
            for k2, v2 in v.items():
                flat[f"{k}.{k2}"] = json.dumps(v2, sort_keys=True)
        else:
            flat[k] = json.dumps(v, sort_keys=True)
    return flat


@pytest.fixture
def cc(tv: TV) -> ControlCenter:
    return ControlCenter(tv)


@pytest.fixture
def settings(tv: TV) -> Settings:
    return Settings(tv)


@pytest.fixture
def menu(tv: TV) -> AppMenu:
    return AppMenu(tv)


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    outcome = yield
    rep = outcome.get_result()
    if rep.when == "call" and rep.failed and "tv" in item.funcargs:
        t: TV = item.funcargs["tv"]
        name = re.sub(r"[^\w.-]+", "_", item.nodeid)
        try:
            t.screenshot(ARTIFACTS / f"{name}.png")
            (ARTIFACTS / f"{name}.tree.txt").write_text("\n".join(repr(n) for n in t.tree().nodes() if n.rid or n.desc or n.text))
        except Exception:
            pass


@pytest.fixture
def shelf_app(tv, home) -> str:
    """The package of a tray app whose shelf is the usual card row and that has titles to show, whatever apps the
    device's tray holds (no app is assumed): the first such app from the left, or the test is skipped."""
    for node in home.card_row_apps():
        pkg = node.rid.removeprefix("app:")
        home.focus_app(pkg)
        # Titles already on screen may be the previous app's (they stay until the hero swaps): let focus and
        # the hero settle on this app first, so the test starts with focus where the fixture says.
        time.sleep(1)
        try:
            tv.wait_for(lambda t: t.focused() and t.focused().rid == f"app:{pkg}", 3, f"focus held on {pkg}")
            tv.wait_for(lambda t: t.find(rid="shelf-title"), 8, f"{pkg}'s titles")
            tv.wait_for(lambda t: t.focused() and t.focused().rid == f"app:{pkg}", 3, f"focus still on {pkg} with its titles")
        except AssertionError:
            continue
        return pkg
    pytest.skip("no tray app shows Top Shelf titles (Top Shelf Content off, Show Titles Never, or offline)")
