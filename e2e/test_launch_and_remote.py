"""Opening apps (with the launch/close animation), returning Home, the Appstore, remote buttons."""

import pytest

GLASS = "dev.glasslauncher"


def _installed(tv, pkg):
    return pkg in tv.sh(f"pm list packages {pkg}")


def _pick_top_row_app(home, tv):
    for pkg in ["com.amazon.firetv.youtube", "com.stremio.one", "com.spotify.tv.android", "io.gh.reisxd.tizentube.cobalt"]:
        if tv.tree().find(rid=f"app:{pkg}") and home.in_dock(tv.tree(), tv.tree().find(rid=f"app:{pkg}")):
            return pkg
    pytest.skip("none of the test apps is in the top row")


@pytest.mark.slow
def test_open_an_app_and_home_lands_back_on_its_tile(tv, home):
    pkg = _pick_top_row_app(home, tv)
    try:
        home.open_app(pkg)
        tv.wait_until(lambda: tv.resumed_package() == pkg, 15, f"{pkg} to stay in front")
        tv.press("home")
        tv.wait_until(lambda: tv.resumed_package() == GLASS, 8, "back on Glass")
        # The close animation lands on the app's tile, and focus stays there (not reset to the start).
        tv.wait_for(lambda t: not t.overlays() and t.focused() and t.focused().rid == f"app:{pkg}", 8, "focus on the app's tile")
    finally:
        tv.sh(f"am force-stop {pkg}")


@pytest.mark.slow
def test_home_on_home_goes_back_to_the_start(tv, home):
    tv.press("right", "wait:200", "right")
    tv.wait_for(lambda t: t.focused() and t.focused().bounds[0] > 200, 4, "focus away from the first tile")
    tv.press("home")
    first = home.dock_apps()[0]
    tv.wait_for(lambda t: t.focused() and t.focused().rid == first.rid, 6, "Home to return to the first tile")


@pytest.mark.slow
@pytest.mark.root
def test_appstore_opens_and_home_restores_glass(tv, home, rooted):
    if not rooted or not tv.tree().find(rid="app:com.amazon.venezia"):
        pytest.skip("needs root and the Appstore tile")
    stock_disabled = lambda: "com.amazon.tv.launcher" in tv.sh("pm list packages -d com.amazon.tv.launcher")
    try:
        home.focus_app("com.amazon.venezia")
        home.select()
        tv.wait_until(lambda: tv.resumed_package() == "com.amazon.tv.launcher", 12, "the Appstore (Amazon's Apps page)")
        tv.press("wait:2500", "home")  # once the page is up, as a person would
        # While Amazon's launcher is still loading, a press can arrive with a late key-up and Fire OS takes
        # it as a long press (its quick settings), not Home. A person would press again; so does the test.
        try:
            tv.wait_until(lambda: tv.resumed_package() == GLASS, 6, "Home to bring Glass back")
        except AssertionError:
            tv.press("home")
            tv.wait_until(lambda: tv.resumed_package() == GLASS, 8, "a second Home to bring Glass back")
        tv.wait_until(stock_disabled, 8, "Amazon's launcher to be switched off again")
    finally:
        if not stock_disabled():
            tv.su("pm disable-user --user 0 com.amazon.tv.launcher; cmd package set-home-activity dev.glasslauncher/.MainActivity")
        if tv.resumed_package() != GLASS:
            tv.home_intent()


def _virtual_press(tv, code):
    """A remote button by raw code through the virtual remote (same key layout as the real one)."""
    tv.press("wait:1")  # makes sure scripts/key has pushed glass-press
    tv.sh(f"/data/local/tmp/glass-press 120 {code}")


@pytest.mark.root
def test_recents_button_opens_the_app_switcher(tv, home):
    if "BUTTON_13" not in tv.sh("cat /system/usr/keylayout/Vendor_0171_Product_0427.kl"):
        pytest.skip("remote-keys module not installed")
    _virtual_press(tv, 748)
    tv.wait_for(lambda t: t.top_overlay() == "AppSwitcher", 6, "the app switcher from the Recents button")


@pytest.mark.root
def test_settings_button_opens_control_center(tv, home):
    if "BUTTON_9" not in tv.sh("cat /system/usr/keylayout/Vendor_0171_Product_0427.kl"):
        pytest.skip("remote-keys module not installed")
    _virtual_press(tv, 185)  # what glass-keymap turns the Settings button's 249 into
    tv.wait_for(lambda t: t.find(rid="control-center"), 6, "Control Center from the Settings button")


@pytest.mark.slow
@pytest.mark.root
def test_app_button_opens_its_app(tv, home):
    if "BUTTON_9" not in tv.sh("cat /system/usr/keylayout/Vendor_0171_Product_0427.kl") or not _installed(tv, "com.netflix.ninja"):
        pytest.skip("remote-keys module or Netflix missing")
    try:
        _virtual_press(tv, 744)  # app1 → Netflix by default
        tv.wait_until(lambda: tv.resumed_package() == "com.netflix.ninja", 15, "Netflix from the app button")
    finally:
        tv.sh("am force-stop com.netflix.ninja")
        tv.home_intent()


EARLY_ACCESS = "com.amazon.tv.earlyaccess"


def test_early_access_is_not_on_home(tv, home):
    """Fire TV Early Access is a beta sign-up that trapped the remote; Glass hides it (Hidden Apps can show it)."""
    tv.press("down", "down")
    assert not tv.tree().find(rid=f"app:{EARLY_ACCESS}"), "Fire TV Early Access should be hidden"
    home.reset()


@pytest.mark.slow
def test_holding_back_escapes_any_app(tv, home):
    """Holding Back for 1.5 s goes Home from any app, even one that swallows Back, Home and Recents."""
    if "RemoteKeysService" not in tv.sh("settings get secure enabled_accessibility_services"):
        pytest.skip("Glass TV Launcher Remote Buttons (accessibility) is off")
    tv.launch(EARLY_ACCESS)
    try:
        tv.wait_until(lambda: tv.resumed_package() == EARLY_ACCESS, 10, "Early Access in front")
        tv.press("wait:1")  # makes sure scripts/key has pushed glass-press
        tv.sh("/data/local/tmp/glass-press 120 158:1900")  # KEY_BACK held 1.9 s on the virtual remote
        tv.wait_until(lambda: tv.resumed_package() == GLASS, 6, "Glass after holding Back")
    finally:
        tv.sh(f"am force-stop {EARLY_ACCESS}")
        if tv.resumed_package() != GLASS:
            tv.home_intent()
