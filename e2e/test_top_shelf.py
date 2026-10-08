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
