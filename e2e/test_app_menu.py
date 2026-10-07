"""The app menu (Menu on a tile): rows, Edit Home Screen, Hide/unhide, Uninstall, App Info."""

import pytest

from pages import AppMenu, Settings

SAFE_APP = "com.esaba.downloader"  # a grid app the tests may hide and open the uninstaller for (never uninstall)


def _need(tv, pkg):
    if pkg not in tv.sh(f"pm list packages {pkg}"):
        pytest.skip(f"{pkg} isn't installed")


def test_menu_lists_its_actions(tv, home, menu):
    _need(tv, SAFE_APP)
    menu.open_for(home, SAFE_APP)
    tree = tv.tree()
    for row in AppMenu.ROWS:
        assert tree.has_text(row), f"app menu has no '{row}'"
    menu.back()
    tv.wait_for(lambda t: "AppMenu" not in t.overlays(), 4, "the menu to close")
    assert tv.tree().focused().rid == f"app:{SAFE_APP}", "focus should return to the app"


def test_edit_home_screen_enters_and_leaves_move_mode(tv, home, menu):
    _need(tv, SAFE_APP)
    menu.open_for(home, SAFE_APP)
    menu.focus_text("Edit Home Screen")
    menu.select()
    # The tree can't be read while tiles wiggle (uiautomator needs an idle screen), so look instead: the
    # banner is a light glass pill in the bottom centre, where Home otherwise shows dark backdrop or tiles.
    band = (0.3, 0.87, 0.7, 0.93)
    before = 0.0
    tv.wait_until(lambda: tv.region_brightness(*band) > 0.12, 4, "the rearranging banner")
    tv.press("back")
    tv.wait_for(lambda t: t.focused() is not None and t.find(rid=f"app:{SAFE_APP}"), 6, "move mode to end (tree readable again)")
    assert not tv.tree().find(rid="move-banner"), "the banner is still up after Back"


def test_move_to_lists_destinations(tv, home, menu):
    _need(tv, SAFE_APP)
    menu.open_for(home, SAFE_APP)
    menu.focus_text("Move to…")
    menu.select()
    tv.wait_for(lambda t: t.top_overlay() == "MoveTo" and t.has_text("New Folder"), 4, "the Move to menu")


def test_hide_then_show_again_from_settings(tv, home, menu, settings):
    _need(tv, SAFE_APP)
    label = None
    menu.open_for(home, SAFE_APP)
    label = tv.tree().find(rid=f"app:{SAFE_APP}").desc
    menu.focus_text("Hide")
    menu.select()
    try:
        tv.wait_for(lambda t: not t.find(rid=f"app:{SAFE_APP}"), 6, "the app to leave Home")
    finally:
        # Show it again through Settings › Hidden Apps (the way a person would).
        home.reset()
        settings.open_from_control_center()
        settings.open_page("Hidden Apps")
        settings.focus_text(label)
        settings.select()
        home.reset()
        tv.wait_for(lambda t: t.find(rid=f"app:{SAFE_APP}"), 8, "the app back on Home")


@pytest.mark.slow
def test_uninstall_opens_the_system_confirmation(tv, home, menu):
    """Regression: without REQUEST_DELETE_PACKAGES the uninstaller flashed white and closed."""
    _need(tv, SAFE_APP)
    menu.open_for(home, SAFE_APP)
    menu.focus_text("Uninstall")
    menu.select()
    try:
        tv.wait_until(lambda: "packageinstaller" in tv.resumed(), 6, "the uninstall confirmation")
        tv.wait_until(lambda: "packageinstaller" in tv.resumed(), 2, "it to stay open")
    finally:
        if "packageinstaller" in tv.resumed():
            tv.press("back")  # Cancel; never confirm
        tv.wait_until(lambda: tv.resumed_package() == "dev.glasslauncher", 6, "back on Glass")
    assert SAFE_APP in tv.sh(f"pm list packages {SAFE_APP}"), "the app was uninstalled!"


@pytest.mark.slow
def test_app_info_opens_system_settings(tv, home, menu):
    _need(tv, SAFE_APP)
    menu.open_for(home, SAFE_APP)
    menu.focus_text("App Info")
    menu.select()
    tv.wait_until(lambda: tv.resumed_package() not in ("", "dev.glasslauncher"), 8, "the app info screen")
    tv.press("home")
    tv.wait_until(lambda: tv.resumed_package() == "dev.glasslauncher", 8, "back on Glass")
