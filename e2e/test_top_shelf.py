"""The Top Shelf moves with the user: the focused app's own hero at once, its titles only after a dwell
(tvOS 27), and none at all when Show Titles is Never."""

import time

import pytest

NETFLIX, YOUTUBE, STREMIO = "com.netflix.ninja", "com.amazon.firetv.youtube", "com.stremio.one"


def _hero(tree):
    return next((n.rid.split(":", 1)[1] for n in tree.nodes() if n.rid.startswith("top-shelf-app-hero:")), None)


def test_focusing_a_tray_app_shows_its_hero_at_once(tv, home):
    """Moving along the tray, each app's own hero follows within a second (titles wait for a dwell)."""
    for _ in range(2):
        before = tv.tree().focused().rid
        tv.press("right")
        nxt = tv.wait_for(lambda t: t.focused() and t.focused().rid != before and t.focused().rid.split(":", 1)[1], 3, "focus to move")
        tv.wait_for(lambda t: _hero(t) == nxt, 1.2, f"{nxt}'s hero within a second")
    assert not tv.tree().has_text("Press up for full screen"), "the hint is just the chevron now"


def test_titles_follow_after_a_dwell(tv, home):
    home.focus_app(STREMIO)
    tv.wait_for(lambda t: _hero(t) == STREMIO, 2, "Stremio's hero first")
    tree = tv.wait_for(lambda t: t.find(rid="shelf-title") and t, 6, "titles after resting on Stremio")
    assert _hero(tree) is None, "the app hero should give way to the titles"
    assert tree.find(rid="shelf-chevron"), "with titles to open, the chevron shows"


def test_show_titles_never_keeps_the_app_hero(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Top Shelf Content")
    settings.focus_text("Show Titles")
    before = settings.value_of("Show Titles")
    if before != "Never":
        settings.select()
        tv.wait_until(lambda: settings.value_of("Show Titles") == "Never", 4, "Show Titles: Never")
    try:
        home.reset()
        home.focus_app(STREMIO)
        tv.wait_for(lambda t: _hero(t) == STREMIO, 2, "Stremio's hero")
        time.sleep(4)
        tree = tv.tree()
        assert not tree.find(rid="shelf-title") and _hero(tree) == STREMIO, "titles appeared with Show Titles off"
        assert not tree.find(rid="shelf-chevron"), "no titles, no chevron"
    finally:
        home.reset()
        settings.open_from_control_center()
        settings.open_page("Top Shelf Content")
        settings.focus_text("Show Titles")
        if settings.value_of("Show Titles") != before:
            settings.select()
            tv.wait_until(lambda: settings.value_of("Show Titles") == before, 4, "Show Titles restored")
        home.reset()


@pytest.mark.perf
def test_browsing_the_tray_stays_smooth(tv, home):
    time.sleep(2)
    tv.su("pkill screenrecord")
    tv.frames_reset()
    for _ in range(5):
        tv.press("right", "wait:400", "right", "wait:400", "left", "wait:400", "left", "wait:400")
    f = tv.frames()
    assert f["janky_pct"] < 5 and f["p90"] <= 16, f"browsing the tray: {f}"


PRIME = "com.amazon.firebat"


@pytest.fixture
def focused_app(tv, home, settings):
    """Top Shelf Content › Focused App for the test (titles follow the focused app), then as it was."""
    def choose(row):
        settings.open_from_control_center()
        settings.open_page("Top Shelf Content")
        settings.focus_text(row)
        settings.select()
        tv.wait_for(lambda t: t.find(desc=f"{row}, ✓"), 4, f"{row} chosen")
        home.reset()
    settings.open_from_control_center()
    settings.open_page("Top Shelf Content")
    before = next((r for r in ("Focused App", "One Source", "Off") if tv.tree().find(desc=f"{r}, ✓")), "Focused App")
    home.reset()
    if before != "Focused App":
        choose("Focused App")
    yield
    if before != "Focused App":
        choose(before)


def _expand(tv, home, pkg):
    """The app's titles in full screen (Up from the tray once they've arrived)."""
    home.focus_app(pkg)
    tv.wait_for(lambda t: t.find(rid="shelf-title"), 10, f"{pkg}'s titles")
    tv.press("up")
    return tv.wait_for(lambda t: t.find(rid="featured-row") and t, 4, "the full-screen shelf")


@pytest.mark.parametrize("pkg,service", [(NETFLIX, "Netflix"), (PRIME, "Prime Video")])
def test_streaming_apps_show_their_own_titles(tv, home, focused_app, pkg, service):
    if pkg not in tv.sh(f"pm list packages {pkg}"):
        pytest.skip(f"{service} isn't installed")
    # Only top-row (tray) apps fill the shelf: the tray is the row the first app sits in after a reset.
    tray_y = tv.tree().focused().center[1]
    node = home.focus_app(pkg)
    if abs(node.center[1] - tray_y) > 20:
        pytest.skip(f"{service} isn't in the top row (only top-row apps fill the shelf)")
    tree = _expand(tv, home, pkg)
    tree = tv.wait_for(lambda t: t.has_text(f"Popular on {service}") and t, 10, f"{service}'s own titles, not another source's")
    assert tree.find(rid="shelf-play") and tree.find(rid="shelf-info"), "the full-screen shelf needs Play and More Info"


@pytest.mark.slow
def test_play_opens_the_title_in_its_app(tv, home, focused_app):
    _expand(tv, home, NETFLIX)
    tv.press("up")
    tv.wait_for(lambda t: t.focused() and t.focused().rid == "shelf-play", 3, "Play focused above the row")
    marker = tv.now_marker()
    tv.press("select")
    tv.wait_until(lambda: tv.resumed_package() == NETFLIX, 15, "Netflix in front")
    started = tv.adb("logcat", "-d", "-T", marker, "-s", "ActivityTaskManager:I")
    # Android redacts the path ("dat=https://www.netflix.com/..."); a VIEW of a Netflix link, not a plain launch.
    assert "act=android.intent.action.VIEW dat=https://www.netflix.com/" in started, f"Play should open the title itself, not just the app:\n{started[-600:]}"


def test_more_info_opens_and_closes(tv, home, focused_app):
    _expand(tv, home, NETFLIX)
    tv.press("up")
    tv.wait_for(lambda t: t.focused() and t.focused().rid == "shelf-play", 3, "Play focused")
    tv.press("right")
    tv.wait_for(lambda t: t.focused() and t.focused().rid == "shelf-info", 3, "More Info focused")
    tv.press("select")
    tree = tv.wait_for(lambda t: t.find(rid="shelf-info-sheet") and t, 3, "the info sheet")
    assert any(len(n.text) > 40 for n in tree.nodes()), "the sheet should show the synopsis"
    tv.press("back")
    tv.wait_for(lambda t: not t.find(rid="shelf-info-sheet") and t.find(rid="featured-row"), 3, "back to the shelf")
