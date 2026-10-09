"""Launcher Settings: opening it, the root list, page push/pop, Top Shelf Content, Root."""

import pytest

ROWS = ["Appearance", "Display & Text", "Control Center", "Set Up from Phone", "Top Shelf Content", "Hidden Apps", "Icon Pack", "Screen Saver",
        "Widgets", "Accessibility", "Home Button", "Remote Buttons", "Updates", "Backup & Restore", "About"]


def test_opens_from_control_center_with_every_section(tv, home, settings, rooted):
    settings.open_from_control_center()
    rows = settings.all_rows()
    for row in ROWS:
        assert row in rows, f"Settings has no '{row}' row (saw {sorted(rows)})"
    assert "Root" in rows, "the Root section is always listed (its first row says whether root is there)"


def test_page_push_and_back(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Appearance")
    tv.wait_for(lambda t: t.has_text("Light") or t.has_text("Dark"), 4, "the Appearance page's options")
    settings.back()
    tv.wait_for(lambda t: t.has_text("Top Shelf Content") and t.top_overlay() == "Settings", 4, "back on the main list")
    settings.back()
    tv.wait_for(lambda t: "Settings" not in t.overlays(), 4, "Settings to close")


def test_featured_row_offers_focused_app_one_source_off(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Top Shelf Content")
    tree = tv.tree()
    for row in ["Focused App", "One Source", "Off", "Stremio", "TMDB", "YouTube", "Plex"]:
        assert tree.has_text(row), f"Top Shelf Content has no '{row}'"


@pytest.mark.root
def test_root_page_shows_state(tv, home, settings, rooted):
    if not rooted:
        pytest.skip("needs root")
    settings.open_from_control_center()
    settings.open_page("Root")
    assert settings.toggle_state("Home Takeover") is True, "Glass is Home, so the Home Takeover switch should be on"
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


def test_display_and_text_page(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Display & Text")
    rows = settings.all_rows()
    for row in ["Bold Text", "Text Size", "Font", "Increase Contrast", "Reduce Transparency", "Fire TV Accessibility"]:
        assert row in rows, f"Display & Text has no '{row}'"
    for row in ["Bold Text", "Increase Contrast", "Reduce Transparency"]:
        assert settings.toggle_state(row) is not None, f"'{row}' isn't a switch"


@pytest.mark.emulator_gap  # passes on the stick; the emulator differs (P22)
def test_text_size_slider_grows_control_center_without_cutting_text(tv, home, settings, cc):
    """At the largest size the Control Center tiles grow (Wi-Fi's network name was cut off at Large)."""
    small = cc.tile(cc.open(), "Wi-Fi").bounds
    home.reset()
    settings.open_from_control_center()
    settings.open_page("Display & Text")
    settings.open_page("Text Size")
    slider = tv.wait_for(lambda t: t.find(rid="text-size-slider"), 4, "the slider")
    settings.focus(lambda n: n.rid == "text-size-slider", "the slider")
    tv.press(*["right"] * 5)
    try:
        tv.wait_for(lambda t: t.find(desc="Text Size, Largest"), 4, "the largest size")
        home.reset()
        tree = cc.open()
        big = cc.tile(tree, "Wi-Fi").bounds
        assert (big[3] - big[1]) > (small[3] - small[1]) * 1.25, f"the Wi-Fi tile didn't grow: {small} → {big}"
        # Everything still fits on screen.
        assert all(n.bounds[3] <= 1080 and n.bounds[0] >= 0 for n in tree.nodes() if n.desc), "Control Center runs off screen"
    finally:
        home.reset()
        settings.open_from_control_center()
        settings.open_page("Display & Text")
        settings.open_page("Text Size")
        settings.focus(lambda n: n.rid == "text-size-slider", "the slider")
        tv.press(*["left"] * 5)
        tv.wait_for(lambda t: t.find(desc="Text Size, Default"), 4, "default size again")
        home.reset()


@pytest.mark.root
def test_root_page_first_row_reports_superuser(tv, home, settings, rooted):
    settings.open_from_control_center()
    settings.open_page("Root")
    tv.wait_for(lambda t: t.find(desc_prefix="Superuser, ") and "Checking" not in t.find(desc_prefix="Superuser, ").desc, 8, "the Superuser row")
    want = "Detected" if rooted else "Not detected"
    assert tv.tree().find(desc=f"Superuser, {want}"), f"Superuser should read {want}"


def test_about_has_a_privacy_section(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("About", "Glass TV Launcher")
    rows = settings.all_rows()
    assert "Glass TV Launcher Collects No Data" in rows


def test_on_off_items_are_switches(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Widgets")
    assert settings.toggle_state("24-Hour Time") is not None, "24-Hour Time should be a switch"
    if tv.tree().has_text("Show Weather"):
        assert settings.toggle_state("Show Weather") is True


def test_remote_buttons_are_a_2x2_grid(tv, home, settings):
    """The remote's four app buttons, laid out as they sit on the remote: Button 1 and 2 on top, 3 and 4 below."""
    settings.open_from_control_center()
    settings.open_page("Remote Buttons")
    tree = tv.tree()
    assert not tree.has_text("Learn a Button…")
    cells = {i: tree.find(rid=f"remote-button-{i}") for i in range(1, 5)}
    assert all(cells.values()), f"missing grid cells: {[i for i, c in cells.items() if not c]}"
    for i, c in cells.items():
        assert c.label.startswith(f"Button {i}, "), f"cell {i} should be 'Button {i}' with its action ({c.label!r})"
    (x1, y1), (x2, y2), (x3, y3), (x4, y4) = (cells[i].center for i in range(1, 5))
    assert abs(y1 - y2) < 10 and abs(y3 - y4) < 10 and y3 > y1, "two rows"
    assert x1 < x2 and x3 < x4 and abs(x1 - x3) < 10, "two columns"
    tv.wait_for(lambda t: t.focused() and t.focused().rid == "remote-button-1", 4, "focus on Button 1")
    tv.press("right")
    tv.wait_for(lambda t: t.focused() and t.focused().rid == "remote-button-2", 4, "Right to Button 2")
    tv.press("down")
    tv.wait_for(lambda t: t.focused() and t.focused().rid == "remote-button-4", 4, "Down to Button 4")
    settings.select()
    tv.wait_for(lambda t: any(n.text == "Button 4" for n in t.nodes()) and t.has_text("Open Another App…"), 6, "Button 4's page")


@pytest.mark.emulator_gap  # flaky on the emulator only, passes on the stick (P22)
def test_rows_are_slim_with_a_gap_between_them(tv, home, settings):
    """tvOS 27 Settings: rows about 35 dp tall with a clear gap between them (Display & Text).
    Measured on screen: accessibility bounds are padded up to the 48 dp minimum touch target."""
    settings.open_from_control_center()
    tree = tv.tree()
    focused = tree.focused()
    rows = sorted((n for n in tree.nodes() if n.focusable and n.bounds[0] > 700), key=lambda n: n.bounds[1])
    img = tv.screen_image()
    x, y = focused.center
    top = y
    while top > 0 and img.getpixel((x, top - 1)) > 225:
        top -= 1
    bottom = y
    while bottom < img.height - 1 and img.getpixel((x, bottom + 1)) > 225:
        bottom += 1
    height = bottom - top + 1
    assert height <= 84, f"the focused row draws {height}px tall (want about 72)"
    pitch = rows[2].center[1] - rows[1].center[1]
    assert pitch - height / 1.02 >= 12, f"rows {pitch}px apart, {height}px tall: no visible gap"
