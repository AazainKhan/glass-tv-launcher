"""Launcher Settings: opening it, the root list, page push/pop, Featured Row, Root."""

import pytest

ROWS = ["Appearance", "Set Up from Phone", "Featured Row", "Hidden Apps", "Icon Pack", "Screensaver",
        "Widgets", "Accessibility", "Home Button", "Remote Buttons", "Updates", "Backup & Restore", "About"]


def test_opens_from_control_center_with_every_section(tv, home, settings, rooted):
    settings.open_from_control_center()
    rows = settings.all_rows()
    for row in ROWS:
        assert row in rows, f"Settings has no '{row}' row (saw {sorted(rows)})"
    assert ("Root" in rows) == rooted, "the Root section should show exactly when root is granted"


def test_page_push_and_back(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Appearance")
    tv.wait_for(lambda t: t.has_text("Light") or t.has_text("Dark"), 4, "the Appearance page's options")
    settings.back()
    tv.wait_for(lambda t: t.has_text("Featured Row") and t.top_overlay() == "Settings", 4, "back on the main list")
    settings.back()
    tv.wait_for(lambda t: "Settings" not in t.overlays(), 4, "Settings to close")


def test_featured_row_offers_focused_app_one_source_off(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Featured Row")
    tree = tv.tree()
    for row in ["Focused App", "One Source", "Off", "Stremio", "TMDB", "YouTube", "Plex"]:
        assert tree.has_text(row), f"Featured Row has no '{row}'"


@pytest.mark.root
def test_root_page_shows_state(tv, home, settings, rooted):
    if not rooted:
        pytest.skip("needs root")
    settings.open_from_control_center()
    settings.open_page("Root")
    assert settings.value_of("Home Takeover") == "On", "Glass is Home, so Home Takeover should read On"
    rows = settings.all_rows()
    for row in ["System App", "Home Takeover", "Balanced", "Fast", "Memory Tuning", "Free Memory", "App Freezer", "Restart Now", "Root Log"]:
        assert row in rows, f"Root page has no '{row}'"


@pytest.mark.root
def test_root_log_lists_actions(tv, home, settings, rooted):
    if not rooted:
        pytest.skip("needs root")
    settings.open_from_control_center()
    settings.open_page("Root")
    settings.open_page("Root Log")
    assert any(":  " not in n.text and ("ok" in n.text or "failed" in n.text) for n in tv.tree().nodes() if n.text), \
        "Root Log is empty"
