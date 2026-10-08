"""Folders and the app switcher."""

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


def test_tv_settings_lists_fire_tv_sections(tv, home, cc):
    cc.open()
    cc.press_tile("Settings, Fire TV")
    tree = tv.wait_for(lambda t: t.top_overlay() == "TvSettings" and t, 6, "TV Settings")
    for row in ["Network", "Display & Sounds", "Applications"]:
        assert any(row in n.text for n in tree.nodes()), f"TV Settings has no '{row}'"


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
