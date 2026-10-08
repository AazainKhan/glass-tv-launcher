"""Home: the top row, the grid, focus, the status pill, the full-screen top shelf."""

from pages import clock_ok, date_ok


def test_home_shows_top_row_status_pill_and_clock(tv, home):
    """HOME-03: the pill is just the time and the gear (the date and weather live in Control Center)."""
    tree = tv.tree()
    assert tree.find(rid="dock"), "no top row (dock)"
    assert len(home.dock_apps()) >= 1, "the top row is empty"
    pill = tree.find(rid="status-pill")
    assert pill, "no status pill"
    clock = tree.find(rid="clock")
    assert clock and clock_ok(clock.text, seconds=False), f"status pill clock reads {clock and clock.text!r}"
    inside = [n for n in tree.nodes() if n.rid in ("date", "weather")]
    assert not inside, f"the pill should hold only the time and gear, found {[n.rid for n in inside]}"
    assert home.in_dock(tree, tree.focused()), "focus should start in the top row"


def test_left_at_the_start_of_a_row_goes_nowhere(tv, home):
    first = tv.tree().focused()
    tv.press("left", "left")
    assert tv.tree().focused().rid == first.rid, "focus wandered off the start of the row"


def test_right_moves_along_the_top_row(tv, home):
    first = tv.tree().focused()
    tv.press("right")
    second = tv.wait_for(lambda t: t.focused() if t.focused() and t.focused().rid != first.rid else None, 4, "focus to move right")
    assert home.in_dock(tv.tree(), second)
    assert second.bounds[0] > first.bounds[0], "Right moved focus left"


def test_down_reaches_the_grid_and_home_returns_to_the_top(tv, home):
    tv.press("down")
    tv.wait_for(lambda t: t.focused() and not home.in_dock(t, t.focused()), 6, "focus in the grid")
    # The focused grid tile shows its name below it (labels appear on focus only).
    focused = tv.tree().focused()
    if focused.rid.startswith("app:"):
        tv.wait_for(lambda t: t.has_text(focused.desc), 3, f"label '{focused.desc}' under the focused tile")
    tv.press("home")
    tv.wait_for(lambda t: home.in_dock(t, t.focused()), 6, "Home to scroll back to the top row")


def test_back_from_the_grid_returns_to_the_top(tv, home):
    tv.press("down", "wait:300", "down")
    tv.wait_for(lambda t: t.focused() and not home.in_dock(t, t.focused()), 6, "focus in the grid")
    tv.press("back")
    tv.wait_for(lambda t: home.in_dock(t, t.focused()), 6, "Back to return to the top row")


def test_up_opens_the_top_shelf_full_screen_and_down_closes_it(tv, home):
    if not tv.tree().find(rid="shelf-chevron"):
        import pytest
        pytest.skip("no Top Shelf titles (Top Shelf Content off, Show Titles Never, or offline)")
    tv.press("up")
    shelf = tv.wait_for(lambda t: t.find(rid="featured-row"), 6, "the full-screen shelf's row")
    assert shelf
    tv.press("down")
    tv.wait_for(lambda t: home.in_dock(t, t.focused()), 6, "Down to return to the top row")


def test_full_screen_shelf_has_no_dots_and_ends_at_the_last_card(tv, home):
    """The page dots stopped at 12 while cards kept going; the dots are gone, and Right stops at the last card."""
    if not tv.tree().find(rid="shelf-chevron"):
        import pytest
        pytest.skip("no Top Shelf titles")
    tv.press("up")
    tv.wait_for(lambda t: t.find(rid="featured-row"), 6, "the full-screen shelf")
    assert not tv.tree().find(rid="shelf-dots"), "the page dots are still shown"
    seen = []
    for _ in range(40):
        tv.press("right")
        f = tv.tree().focused()
        if seen and f.rid == seen[-1]:
            break
        seen.append(f.rid)
    assert seen and seen[-1].startswith("featured:"), f"focus left the row: {seen[-1]}"
    tv.press("down")


def test_tray_apps_have_no_name_under_them(tv, home):
    """The top row shows icons only: no name under the focused tray app (the grid keeps its labels)."""
    # (Checked in the tree: a pixel check under the tile is confounded by the focused tile's scale and shadow.)
    tree = tv.tree()
    focused = tree.focused()
    assert focused and focused.rid.startswith("app:"), "focus should start on a tray app"
    assert not tree.has_text(focused.desc), f"'{focused.desc}' is written under the tray icon"


def test_tray_always_holds_six_apps(tv, home):
    """With fewer than six chosen for the top row, the first grid apps move up to fill it."""
    assert len(home.dock_apps()) == 6, f"the tray holds {len(home.dock_apps())} apps"
