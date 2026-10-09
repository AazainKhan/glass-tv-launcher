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


@pytest.mark.parametrize("pkg", [NETFLIX, STREMIO])
def test_every_tray_app_has_a_full_screen_hero(tv, home, pkg):
    """The app hero is full-screen art (Amazon's Fire TV background, else the app's store screenshot),
    not just its logo on a colour."""
    tray_y = tv.tree().focused().center[1]
    node = home.focus_app(pkg)
    if abs(node.center[1] - tray_y) > 20:
        pytest.skip(f"{pkg} isn't in the top row")
    # Leave and come back so the hero (not titles after a dwell) is what shows.
    tv.press("right")
    tv.press("left")
    tv.wait_for(lambda t: t.find(rid=f"app-art:{pkg}"), 8, f"{pkg}'s full-screen art")


def test_titles_without_a_logo_stay_hidden_at_rest(tv, home, focused_app):
    """A title with no logo art would be plain text in one generic font: at rest the shelf shows only the
    art (the name appears in full screen). JustWatch titles (Netflix) carry no logos."""
    tray_y = tv.tree().focused().center[1]
    if abs(home.focus_app(NETFLIX).center[1] - tray_y) > 20:
        pytest.skip("Netflix isn't in the top row")
    tree = tv.wait_for(lambda t: t.find(rid="shelf-title") and t, 10, "Netflix's titles")
    assert not tree.find(rid="shelf-title").texts, f"a plain-text title shows at rest: {tree.find(rid='shelf-title').texts}"
    tv.press("up")
    tv.wait_for(lambda t: t.find(rid="featured-row") and any(len(n.text) > 2 for n in t.nodes() if n.center[1] < 700 and n.center[0] < 900), 4, "the title in full screen")


APPSTORE = "com.amazon.venezia"


@pytest.mark.parametrize("pkg", [STREMIO, APPSTORE])
def test_app_heroes_are_the_apps_logo_sharp(tv, home, pkg):
    """An app's hero is its logo art full screen and crisp (vector banner, Amazon's icon), never a soft
    upscale or a screenshot of the app."""
    from PIL import ImageFilter
    tray_y = tv.tree().focused().center[1]
    if abs(home.focus_app(pkg).center[1] - tray_y) > 20:
        pytest.skip(f"{pkg} isn't in the top row")
    tv.press("left" if pkg == APPSTORE else "right")
    tv.press("right" if pkg == APPSTORE else "left")
    tv.wait_for(lambda t: t.find(rid=f"app-art:{pkg}"), 8, f"{pkg}'s hero")
    edges = sorted(tv.screen_image().crop((0, 120, 1920, 700)).filter(ImageFilter.FIND_EDGES).getdata())
    # The logo is about half the screen wide now: look at the strongest edges (its outline), not the average.
    crisp = edges[int(len(edges) * 0.9999)]
    assert crisp > 220, f"{pkg}'s hero is soft (edge strength {crisp}); it should be crisp logo art"


TIZENTUBE = "io.gh.reisxd.tizentube.cobalt"


def _show_hero(tv, home, pkg):
    tray_y = tv.tree().focused().center[1]
    node = home.focus_app(pkg)
    if abs(node.center[1] - tray_y) > 20:
        pytest.skip(f"{pkg} isn't in the top row")
    last = home.dock_apps()[-1].rid == node.rid
    tv.press("left" if last else "right")
    tv.press("right" if last else "left")
    tv.wait_for(lambda t: t.find(rid=f"app-art:{pkg}"), 8, f"{pkg}'s hero")


def test_stremio_hero_has_no_rounded_corners(tv, home):
    """The banner's transparent corners must not show: the hero is one full-screen piece of art."""
    _show_hero(tv, home, STREMIO)
    img = tv.screen_image(colour=True)
    corner, inside = img.getpixel((4, 4)), img.getpixel((60, 60))
    assert sum(abs(a - b) for a, b in zip(corner, inside)) < 40, f"the hero's corner {corner} differs from its edge {inside}"


def test_tizentube_uses_youtubes_art(tv, home):
    """TizenTube is a YouTube client: its hero is YouTube's sharp official art, not its own small banner."""
    from PIL import ImageFilter
    _show_hero(tv, home, TIZENTUBE)
    edges = sorted(tv.screen_image().crop((0, 120, 1920, 700)).filter(ImageFilter.FIND_EDGES).getdata())
    assert edges[int(len(edges) * 0.9999)] > 220, "TizenTube's hero is soft"


def test_pill_stands_out_on_light_art(tv, home):
    """On a light hero (Netflix's white icon) the status pill must still read as a surface."""
    _show_hero(tv, home, NETFLIX)
    tree = tv.tree()
    pill = tree.find(rid="status-pill")
    img = tv.screen_image()
    l, t, r, b = pill.bounds
    # A strip inside the pill above its text, against the same rows of art just left of it.
    body = img.crop((l + 30, t + 5, r - 70, t + 11))
    beside = img.crop((l - 80, t + 5, l - 20, t + 11))
    from PIL import ImageStat
    step = ImageStat.Stat(beside).mean[0] - ImageStat.Stat(body).mean[0]
    assert step >= 12, f"the pill barely differs from the light art beside it ({step:.0f}/255)"


def test_titles_show_their_official_logo(tv, home, focused_app):
    """Titles carry their real title treatment (a logo image), not one generic font."""
    tray_y = tv.tree().focused().center[1]
    if abs(home.focus_app(NETFLIX).center[1] - tray_y) > 20:
        pytest.skip("Netflix isn't in the top row")
    tree = tv.wait_for(lambda t: t.find(rid="shelf-logo") and t, 12, "a title logo at rest")
    assert not tree.find(rid="shelf-title").texts, "the title should be a logo image, not text"


def test_play_and_more_info_are_slim(tv, home, focused_app):
    tray_y = tv.tree().focused().center[1]
    if abs(home.focus_app(NETFLIX).center[1] - tray_y) > 20:
        pytest.skip("Netflix isn't in the top row")
    tree = _expand(tv, home, NETFLIX)
    play = tree.find(rid="shelf-play")
    height = play.bounds[3] - play.bounds[1]
    assert height <= 76, f"Play is {height}px tall; the slim buttons are 36 dp (72 px)"
    heading, row = tree.find(rid="shelf-heading"), tree.find(rid="featured-row")
    gap = row.bounds[1] - heading.bounds[3]
    assert gap >= 24, f"the row's heading sits {gap}px above the cards; it needs room"


def test_full_screen_row_starts_at_and_stays_on_the_slide(tv, home, focused_app):
    """Up opens the row on the title that was showing, and the slideshow doesn't move it while browsing."""
    import time
    tray_y = tv.tree().focused().center[1]
    if abs(home.focus_app(NETFLIX).center[1] - tray_y) > 20:
        pytest.skip("Netflix isn't in the top row")
    shown = tv.wait_for(lambda t: (n := t.find(rid="shelf-logo")) and n.desc, 12, "a title at rest")
    tv.press("up")
    card = tv.wait_for(lambda t: t.find(rid="featured-row") and (f := t.focused()) and f.rid.startswith("featured:") and f, 4, "a card focused")
    assert card.label.startswith(shown), f"the row opened on {card.label!r}, but {shown!r} was showing"
    time.sleep(12)  # longer than a slide
    tree = tv.tree()
    still = tree.focused()
    assert still.rid == card.rid, f"the slideshow moved focus to {still.label!r} while browsing"
    details = tree.find(rid="shelf-logo")
    title = details.desc if details else next((n.text for n in tree.nodes() if n.center[0] < 900 and n.center[1] < 600 and len(n.text) > 2), "")
    assert still.label.startswith(title), f"the details show {title!r} but focus is on {still.label!r}"


@pytest.mark.parametrize("pkg", [NETFLIX, STREMIO])
def test_app_hero_logo_sits_at_tvos_size(tv, home, pkg):
    """tvOS's logo-only shelf: the logo about half the screen wide, centred above the tray on its own
    colour, not stretched edge to edge."""
    _show_hero(tv, home, pkg)
    img = tv.screen_image(colour=True)
    bg = img.getpixel((40, 300))
    differs = lambda p: sum(abs(a - b) for a, b in zip(p, bg)) > 60
    cols = [x for x in range(0, 1920, 8) if any(differs(img.getpixel((x, y))) for y in range(180, 560, 12))]
    width = (cols[-1] - cols[0]) if cols else 0
    assert 0 < width <= 1920 * 0.62, f"{pkg}'s logo spans {width}px of 1920; tvOS draws it about half as wide"
