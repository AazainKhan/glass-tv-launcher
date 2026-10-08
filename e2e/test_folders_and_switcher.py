"""Folders and the app switcher."""

import time

import pytest

from pages import APP_SWITCHER


def test_folder_opens_and_back_closes(tv, home):
    if not home.grid_items() or not any(n.rid.startswith("folder:") for n in tv.tree().nodes()):
        tv.press("down")
    if not tv.tree().find(rid_prefix="folder:"):
        pytest.skip("no folder on Home")
    folder = home.focus_folder()
    home.select()
    tv.wait_for(lambda t: t.top_overlay() == "FolderOpen" and t.find(rid="folder-title"), 6, "the folder to open")
    home.back()
    tv.wait_for(lambda t: "FolderOpen" not in t.overlays(), 4, "the folder to close")
    assert tv.tree().focused().rid == folder.rid, "focus should return to the folder"


def test_folder_menu_has_its_actions(tv, home):
    tv.press("down")
    if not tv.tree().find(rid_prefix="folder:"):
        pytest.skip("no folder on Home")
    home.focus_folder()
    tv.press("menu")
    tree = tv.wait_for(lambda t: t.top_overlay() == "FolderMenu" and t, 4, "the folder menu")
    for row in ["Open", "Rename", "Move", "Remove Folder"]:
        assert tree.has_text(row), f"folder menu has no '{row}'"


def test_app_switcher_opens_and_closes(tv, home):
    tv.intent(APP_SWITCHER)
    tree = tv.wait_for(lambda t: t.top_overlay() == "AppSwitcher" and t, 6, "the app switcher")
    assert tree.find(rid="app-switcher") or tree.has_text("Apps you open will appear here.")
    home.back()
    tv.wait_for(lambda t: "AppSwitcher" not in t.overlays(), 4, "the switcher to close")


OLDER, NEWER = "com.esaba.downloader", "org.videolan.vlc"
PREVIEW_DELAY_S = 5


@pytest.fixture
def two_recent_apps(tv, home):
    """Opens Downloader, then VLC, then comes back Home, so VLC is the newest app and Downloader the one before."""
    for pkg in (OLDER, NEWER):
        if pkg not in tv.sh(f"pm list packages {pkg}"):
            pytest.skip(f"{pkg} isn't installed")
        tv.launch(pkg)
        tv.wait_until(lambda: tv.resumed_package() == pkg, 10, pkg)
        time.sleep(PREVIEW_DELAY_S)  # long enough for the switcher's preview of it
    tv.home_intent()
    tv.wait_until(lambda: tv.resumed_package() == "dev.glasslauncher", 8, "Glass in front")
    home.reset()
    yield
    tv.sh(f"am force-stop {OLDER}; am force-stop {NEWER}")


def _switcher(tv):
    tv.intent(APP_SWITCHER)
    return tv.wait_for(lambda t: t.top_overlay() == "AppSwitcher" and t.find(rid="switcher-title") and t, 6, "the app switcher")


def test_app_switcher_is_laid_out_like_tvos(tv, home, two_recent_apps):
    """tvOS: the newest app's card large in the centre with its icon and name above it, earlier apps stacked
    and overlapping to its left, Home peeking in on the right."""
    _switcher(tv)
    # Last time's list shows at once and the fresh one can re-centre a beat later: wait for it to land.
    tree = tv.wait_for(lambda t: t.focused() and t.focused().rid == f"switcher-card:{NEWER}"
                       and abs(sum(t.focused().bounds[::2]) / 2 - 960) < 40 and t, 3, "the newest card centred")
    focused = tree.focused()
    assert focused and focused.rid == f"switcher-card:{NEWER}", f"focus should start on the newest app, not {focused}"
    assert tree.find(rid="switcher-title").label == "VLC", "the focused app's name sits above its card"
    l, t, r, b = focused.bounds
    assert abs((l + r) / 2 - 960) < 40, f"the focused card should be centred ({focused.bounds})"
    assert r - l > 900, f"the focused card should be large, about half the screen ({r - l}px wide)"
    older = tree.find(rid=f"switcher-card:{OLDER}")
    assert older, "the earlier app should be stacked to the left"
    ol, ot, orr, ob = older.bounds
    # Accessibility clips a card to the part the front card doesn't cover, so overlapping reads as touching.
    assert ol < l and orr >= l, f"earlier apps sit to the left, overlapping the focused card ({older.bounds} vs {focused.bounds})"
    assert (orr - ol) < (r - l), "cards behind are smaller than the focused one"
    home_card = tree.find(rid="switcher-card:home")
    assert home_card and home_card.bounds[0] > r, "Home peeks in to the right of the focused card"


def test_app_switcher_cards_show_blurred_previews_of_the_apps(tv, home, two_recent_apps):
    """Each card shows a blurred snapshot of the app as it was last seen, not its icon blown up."""
    tree = _switcher(tv)
    for pkg in (NEWER, OLDER):
        assert tree.find(rid=f"switcher-preview:{pkg}"), f"{pkg}'s card has no preview"
    size = tv.su(f"stat -c %s /data/data/dev.glasslauncher/cache/previews/{NEWER}.jpg").strip()
    assert size.isdigit() and int(size) < 60_000, f"previews should be small, cheap images ({size} bytes)"


def test_app_switcher_moves_through_the_stack(tv, home, two_recent_apps):
    _switcher(tv)
    tv.press("left")
    tree = tv.wait_for(lambda t: t.focused() and t.focused().rid == f"switcher-card:{OLDER}" and t, 4, "focus on the earlier app")
    l, t, r, b = tree.focused().bounds
    tv.wait_for(lambda t: abs(sum(t.focused().bounds[::2]) / 2 - 960) < 40, 3, "the earlier app sliding to the centre")
    assert tv.tree().find(rid="switcher-title").label == "Downloader"
    tv.press("right", "right")
    tree = tv.wait_for(lambda t: t.focused() and t.focused().rid == "switcher-card:home" and t, 4, "focus on Home")
    home.select()
    tv.wait_for(lambda t: "AppSwitcher" not in t.overlays(), 4, "Home card closes the switcher")


def test_app_switcher_opens_the_focused_app(tv, home, two_recent_apps):
    _switcher(tv)
    tv.press("left")
    tv.wait_for(lambda t: t.focused() and t.focused().rid == f"switcher-card:{OLDER}", 4, "focus on the earlier app")
    home.select()
    tv.wait_until(lambda: tv.resumed_package() == OLDER, 10, "Downloader in front")


@pytest.mark.perf
def test_moving_through_the_app_switcher_is_smooth(tv, home, two_recent_apps):
    _switcher(tv)
    time.sleep(1.5)
    tv.su("pkill screenrecord")
    tv.frames_reset()
    for _ in range(3):
        tv.press("left", "wait:600", "left", "wait:600", "right", "wait:600", "right", "wait:600")
    f = tv.frames()
    assert f["janky_pct"] < 5 and f["p90"] <= 16, f"moving through the switcher: {f}"


@pytest.mark.perf
def test_app_switcher_opens_and_closes_smoothly(tv, home, two_recent_apps):
    # From the grid, where Home's featured slideshow is paused (its cross-fades are Home's own cost). One
    # open first to load the previews; no tree reads inside the measured window (uiautomator makes Glass
    # build its accessibility tree mid-animation, which is jank of its own).
    tv.press("down", "down", "wait:1500")
    _switcher(tv)
    home.back()
    tv.wait_for(lambda t: "AppSwitcher" not in t.overlays(), 4, "the switcher to close")
    tv.su("pkill screenrecord")
    tv.frames_reset()
    for _ in range(3):
        tv.intent(APP_SWITCHER)
        time.sleep(1.5)
        tv.press("back")
        time.sleep(1.2)
    f = tv.frames()
    assert f["janky_pct"] < 10 and f["p90"] <= 16, f"app switcher open/close: {f}"


TV_SECTIONS = {
    "Network": "NetworkActivity",
    "Display & Sounds": "DisplayAndSoundsActivity",
    "Applications": "ApplicationsActivity",
    "Controllers & Bluetooth Devices": "ControllersAndBluetoothActivity",
    "Preferences": "PreferencesActivity",
    "My Fire TV": "DeviceActivity",
    "Accessibility": "AccessibilityActivity",
    "Account & Profile Settings": "MyAccountActivity",
}


@pytest.mark.slow
@pytest.mark.parametrize("section", list(TV_SECTIONS))
def test_every_tv_settings_section_opens(tv, home, cc, section):
    """Each row opens Fire TV's own page for it, and the page stays up (some finish at once without an action)."""
    cc.open()
    cc.press_tile("Settings, Fire TV")
    tv.wait_for(lambda t: t.top_overlay() == "TvSettings", 6, "TV Settings")
    cc.focus_text(section)
    cc.select()
    want = TV_SECTIONS[section]
    tv.wait_until(lambda: want in tv.resumed(), 8, f"Fire's {want}")
    import time
    time.sleep(2)
    assert want in tv.resumed(), f"{section} opened and closed again ({tv.resumed()})"
    tv.home_intent()
    tv.wait_until(lambda: tv.resumed_package() == "dev.glasslauncher", 8, "back on Glass")


@pytest.mark.perf
def test_folder_open_and_close_are_smooth(tv, home):
    """Five open/close cycles: under 10% janky frames, p90 within one frame (16 ms)."""
    tv.press("down")
    if not tv.tree().find(rid_prefix="folder:"):
        pytest.skip("no folder on Home")
    home.focus_folder()
    tv.su("pkill screenrecord")
    tv.frames_reset()
    for _ in range(5):
        home.select()
        tv.wait_for(lambda t: t.top_overlay() == "FolderOpen", 6, "the folder to open")
        home.back()
        tv.wait_for(lambda t: "FolderOpen" not in t.overlays() and not t.find(rid_prefix="overlay-leaving"), 6, "the folder to close")
    f = tv.frames()
    assert f["janky_pct"] < 10 and f["p90"] <= 16, f"folder open/close: {f}"
