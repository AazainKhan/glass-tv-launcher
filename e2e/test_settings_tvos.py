"""Settings laid out like tvOS 27: the words (explanations) on the left under a muted per-page icon, only
rows on the right, and a confirmation card before anything drastic."""

import pytest

from tv import PKG


def _icon(tree):
    n = tree.find(rid_prefix="settings-icon:")
    return n.rid.split(":", 1)[1] if n else None


@pytest.mark.emulator_gap  # passes on the stick; the emulator differs (P22)
def test_explanations_sit_on_the_left_under_the_page_icon(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Accessibility")
    tree = tv.wait_for(lambda t: _icon(t) == "Accessibility" and t, 4, "the Accessibility page icon")
    hint = next((n for n in tree.nodes() if n.text.startswith("Turns off tilt")), None)
    assert hint, "the page's explanation is missing"
    assert hint.center[0] < 900, f"explanations belong on the left, found at x={hint.center[0]}"


def test_each_page_has_its_own_icon(tv, home, settings):
    settings.open_from_control_center()
    seen = {_icon(tv.tree())}
    for page in ("Widgets", "Accessibility"):
        settings.open_page(page)
        seen.add(tv.wait_for(lambda t: _icon(t) not in seen and _icon(t), 4, f"{page}'s icon"))
        home.back()
        tv.wait_for(lambda t: t.has_text("Appearance"), 4, "back on the main list")
    assert len(seen) == 3, f"pages share icons: {seen}"


def test_text_size_name_sits_above_the_slider(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Display & Text")
    settings.open_page("Text Size")
    tree = tv.wait_for(lambda t: t.find(rid="text-size-slider") and t, 4, "the slider")
    slider = tree.find(rid="text-size-slider")
    name = tree.find(rid="text-size-name")
    assert name and name.bounds[3] <= slider.bounds[1] + 2, f"the size name should be above the slider ({name and name.bounds} vs {slider.bounds})"


def _confirm_then_cancel(tv, settings, row, button):
    settings.focus_text(row)
    settings.select()
    tree = tv.wait_for(lambda t: t.top_overlay() == "Confirm" and t, 4, f"a confirmation for {row}")
    # Destructive confirms open on Cancel (P72): a stray Select must not replace or restart anything.
    assert tree.focused() and tree.focused().label.startswith("Cancel"), f"a destructive confirm ({button}) should open on Cancel"
    assert tree.has_text(button), f"the confirm button ({button}) should be there"
    settings.select()
    tv.wait_for(lambda t: t.top_overlay() == "Settings", 4, "Cancel to close it")


@pytest.mark.root
def test_restart_asks_first(tv, home, settings, rooted):
    if not rooted:
        pytest.skip("needs root")
    pid = tv.glass_pid()
    settings.open_from_control_center()
    settings.open_page("Root")
    _confirm_then_cancel(tv, settings, "Restart Now", "Restart")
    assert tv.glass_pid() == pid, "Cancel restarted anyway"


def test_restore_asks_first(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Backup & Restore")
    _confirm_then_cancel(tv, settings, "Restore from Downloads", "Restore")


def _left_words(tree):
    """Text in the left column under the icon (not the centred title at the top)."""
    return [n.text for n in tree.nodes() if n.text and n.center[0] < 840 and n.center[1] > 160 and not n.focusable]


def test_left_text_follows_the_focused_row(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Screen Saver", "Current Selection")
    settings.focus_text("Show During Music")
    music = tv.wait_for(lambda t: _left_words(t), 3, "help for Show During Music")
    assert any("music" in w.lower() for w in music), f"the left text should explain the focused row: {music}"
    settings.focus_text("Start After")
    after = tv.wait_for(lambda t: _left_words(t) != music and _left_words(t), 3, "help for Start After")
    assert any("idle" in w.lower() for w in after), f"the left text should follow focus: {after}"


def test_left_column_holds_one_short_text(tv, home, settings):
    """Purposeful text: at most one short line of words on the left of any page, never a pile."""
    settings.open_from_control_center()
    rows = [r for r in ["Appearance", "Display & Text", "Control Center", "Top Shelf Content", "Hidden Apps", "Icon Pack",
                        "Screen Saver", "Widgets", "Home Button", "Remote Buttons", "Accessibility", "Updates", "Backup & Restore", "Root"]
            if any(r in n.texts for n in tv.tree().nodes()) or True]
    piles = {}
    for row in rows:
        try:
            settings.open_page(row)
        except AssertionError:
            continue
        import time
        time.sleep(0.5)
        words = _left_words(tv.tree())
        if len(words) > 1 or sum(len(w) for w in words) > 160:
            piles[row] = words
        home.back()
        tv.wait_for(lambda t: t.has_text("Appearance"), 4, "back on the main list")
    assert not piles, f"pages with too much text on the left: {piles}"


def test_page_icon_has_no_tile_behind_it(tv, home, settings):
    """Just the muted icon on the left: no glass tile or border around it."""
    settings.open_from_control_center()
    settings.open_page("Accessibility")
    node = tv.wait_for(lambda t: t.find(rid="settings-icon:Accessibility"), 4, "the page icon")
    width = node.bounds[2] - node.bounds[0]
    assert width <= 260, f"the icon area is {width}px wide: the 200 dp glass tile is still behind it"


def test_main_settings_gear_matches_the_other_page_icons(tv, home, settings):
    settings.open_from_control_center()
    node = tv.wait_for(lambda t: t.find(rid="settings-icon:Settings"), 4, "the main page's gear")
    width = node.bounds[2] - node.bounds[0]
    assert width <= 260, f"the gear sits on a {width}px tile; it should be the muted icon alone"


def test_settings_title_is_a_real_title_and_rows_are_grouped(tv, home, settings):
    """A page title at tvOS's size (48 px or more), and the main list grouped into sections."""
    settings.open_from_control_center()
    tree = tv.tree()
    title = next((n for n in tree.nodes() if n.text == "Settings" and n.center[1] < 140), None)
    assert title, "no page title"
    assert title.bounds[3] - title.bounds[1] >= 44, f"the title is {title.bounds[3] - title.bounds[1]}px tall"
    sections = [n.text for n in tree.nodes() if n.text and n.text.isupper() and len(n.text) > 3]
    assert len(sections) >= 2, f"the main list should be grouped into sections, found {sections}"
