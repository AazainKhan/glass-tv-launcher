"""Fixtures for the device suite. Run through scripts/e2e, which takes the stick lock."""

from __future__ import annotations

import os
import re
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
    return t


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
    after = _config(tv)
    changed = sorted(k for k in set(before) | set(after) if before.get(k) != after.get(k) and k not in VOLATILE)
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
VOLATILE = {"recentApps", "seenApps", "tipsSeen", "lastUpdateCheck", "seededDefaults"}


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
