"""Settings laid out like tvOS 27: the words (explanations) on the left under a muted per-page icon, only
rows on the right, and a confirmation card before anything drastic."""

import pytest

from tv import PKG


def _icon(tree):
    n = tree.find(rid_prefix="settings-icon:")
    return n.rid.split(":", 1)[1] if n else None


def test_explanations_sit_on_the_left_under_the_page_icon(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Accessibility")
    tree = tv.wait_for(lambda t: _icon(t) == "Accessibility" and t, 4, "the Accessibility page icon")
    hint = next((n for n in tree.nodes() if n.text.startswith("Text size, bold text")), None)
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
    settings.open_page("Display & Text Size")
    settings.open_page("Text Size")
    tree = tv.wait_for(lambda t: t.find(rid="text-size-slider") and t, 4, "the slider")
    slider = tree.find(rid="text-size-slider")
    name = tree.find(rid="text-size-name")
    assert name and name.bounds[3] <= slider.bounds[1] + 2, f"the size name should be above the slider ({name and name.bounds} vs {slider.bounds})"


def _confirm_then_cancel(tv, settings, row, button):
    settings.focus_text(row)
    settings.select()
    tree = tv.wait_for(lambda t: t.top_overlay() == "Confirm" and t, 4, f"a confirmation for {row}")
    assert tree.focused() and tree.focused().label.startswith(button), f"the confirm button ({button}) should be first and focused"
    settings.focus_text("Cancel")
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
