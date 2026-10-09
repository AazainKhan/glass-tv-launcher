"""What Glass relies on being set up on the stick (each is something that broke at least once)."""

import pytest

GLASS = "dev.glasslauncher"


def test_glass_is_the_home_app(tv):
    out = tv.sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
    assert out.strip().splitlines()[-1] == f"{GLASS}/.MainActivity", f"Home resolves to {out.strip().splitlines()[-1]}"


def test_only_one_glass_activity(tv, home):
    count = tv.sh(f"dumpsys activity activities | grep -c 'Hist #.*{GLASS}/.MainActivity'").strip()
    assert count == "1", f"{count} Glass activities (a plain `am start -n` makes a second one)"


def test_remote_buttons_service_is_on(tv):
    assert f"{GLASS}/{GLASS}.system.RemoteKeysService" in tv.setting("secure", "enabled_accessibility_services")
    # dumpsys cuts long labels short ("Glass TV Launcher Remo…"), so match the start of it.
    assert "Glass TV Launcher Remo" in tv.sh("dumpsys accessibility | grep 'Bound services'"), "service enabled but not bound"


def test_uninstall_permission_is_granted(tv):
    assert "REQUEST_DELETE_PACKAGES: granted=true" in tv.sh(f"dumpsys package {GLASS}")


def test_network_is_up(tv):
    assert "Active default network: none" not in tv.sh("dumpsys connectivity | grep 'Active default network'"), "no network"


@pytest.mark.root
def test_root_setup(tv, rooted):
    if not rooted:
        pytest.skip("needs root")
    modules = tv.su("ls /data/adb/modules").split()
    assert "glass-remote-keys" in modules
    flags = tv.sh(f"dumpsys package {GLASS} | grep -m1 pkgFlags")
    if "glass-system" in modules:
        assert "SYSTEM" in flags, "glass-system module installed but Glass isn't a system app"
        assert "ACCESS_ALL_EPG_DATA: granted=true" in tv.sh(f"dumpsys package {GLASS}")
    if "glass-tuning" in modules:
        assert tv.sh("getprop ro.lmk.kill_heaviest_task").strip() == "false"
        assert "max_cached_processes=12" in tv.setting("global", "activity_manager_constants")
        assert int(tv.sh("cat /sys/block/zram0/disksize").strip()) > 1_000_000_000
    assert tv.sh("getprop persist.perfanimation.enabled").strip() == "false", \
        "Fire's Home-key black flash (KeyFeedbackUI) is back on"
    assert "com.amazon.tv.launcher" in tv.sh("pm list packages -d com.amazon.tv.launcher"), "Amazon's launcher is enabled"


@pytest.mark.perf
def test_perf_gate(tv):
    """Frame times, memory and idle CPU against perf-budget.json (slow; close tv-live first)."""
    out = tv.script("perf-gate", "--no-build", timeout=1500)
    assert "perf-gate: pass" in out, out


@pytest.mark.root
def test_glass_switches_the_stock_launcher_back_off(tv, home, rooted):
    """If Amazon's launcher gets turned on outside a store visit (it took over Home on the stick once),
    Glass turns it off again and stays Home as soon as it comes forward."""
    if not rooted:
        pytest.skip("needs root")
    disabled = lambda: "com.amazon.tv.launcher" in tv.sh("pm list packages -d com.amazon.tv.launcher")
    try:
        tv.su("pm enable com.amazon.tv.launcher")
        assert not disabled()
        tv.sh("am start -n dev.glasslauncher/.MainActivity")
        tv.wait_until(disabled, 10, "Glass to switch Amazon's launcher off again")
        assert "dev.glasslauncher" in tv.sh("cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME | grep -m1 packageName")
    finally:
        if not disabled():
            tv.su("pm disable-user --user 0 com.amazon.tv.launcher; cmd package set-home-activity dev.glasslauncher/.MainActivity")
