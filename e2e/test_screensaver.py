"""Screen Saver settings laid out like tvOS: Current Selection, Start After, Show During Music, then the
Aerials and Slideshow preferences. Choose Aerials hides clips by category; Slideshow plays a photo album."""

import json
from pathlib import Path

import pytest

from conftest import _config

FIXTURE = Path(__file__).parent / "fixtures" / "slideshow.jpg"
ALBUM_DIR = "/sdcard/Pictures/glass-e2e"
PHOTOS = "android.permission.READ_EXTERNAL_STORAGE"


def _open(tv, settings):
    settings.open_from_control_center()
    settings.open_page("Screen Saver", "Current Selection")


def _y(tree, text):
    n = next((n for n in tree.nodes() if n.focusable and n.texts[:1] == [text]), None)
    assert n, f"'{text}' is missing"
    return n.bounds[1]


def test_screen_saver_rows_follow_tvos(tv, home, settings):
    _open(tv, settings)
    tree = tv.tree()
    order = ["Current Selection", "Start After", "Show During Music", "Aerials", "Slideshow"]
    ys = [_y(tree, t) for t in order]
    assert ys == sorted(ys), f"rows out of order: {dict(zip(order, ys))}"
    assert any("Screen Saver Preferences".lower() == n.text.lower() for n in tree.nodes()), "no Screen Saver Preferences section"
    assert settings.toggle_state("Show During Music") is not None, "Show During Music should be a switch"


def test_current_selection_and_start_after_pick_from_a_list(tv, home, settings):
    _open(tv, settings)
    assert settings.value_of("Current Selection") == "Aerials"
    settings.open_page("Current Selection", "Fire TV Screensaver")
    settings.focus_text("Slideshow")
    settings.select()
    tv.wait_for(lambda t: t.find(desc="Slideshow, ✓"), 4, "Slideshow ticked")
    home.back()
    tv.wait_until(lambda: settings.value_of("Current Selection") == "Slideshow", 4, "the value to follow")
    settings.open_page("Current Selection", "Fire TV Screensaver")
    settings.focus_text("Aerials")
    settings.select()
    home.back()
    tv.wait_until(lambda: settings.value_of("Current Selection") == "Aerials", 4, "Aerials back")

    before = settings.value_of("Start After")
    settings.open_page("Start After", "30 Minutes")
    settings.focus_text("15 Minutes")
    settings.select()
    home.back()
    tv.wait_until(lambda: settings.value_of("Start After") == "15 Minutes", 4, "Start After 15")
    settings.open_page("Start After", "30 Minutes")
    settings.focus_text(before)
    settings.select()
    home.back()
    tv.wait_until(lambda: settings.value_of("Start After") == before, 4, "Start After restored")


def test_choose_aerials_hides_a_clip_and_shows_it_again(tv, home, settings):
    _open(tv, settings)
    settings.open_page("Aerials", "Choose Aerials")
    settings.open_page("Choose Aerials", "Underwater")
    tree = tv.wait_for(lambda t: t.all("aerial:") and t, 15, "aerial thumbnails")
    for cat in ("Cityscape", "Earth", "Landscape", "Underwater"):
        assert tree.has_text(cat), f"no {cat} category"
    first = tree.all("aerial:")[0]
    clip = first.rid.split(":", 1)[1]
    settings.focus(lambda n: n.rid == first.rid, first.rid)
    settings.select()
    tv.wait_until(lambda: clip in _config(tv).get("screensaver.hiddenAerials", ""), 4, "the clip hidden")
    assert tv.tree().find(rid=f"aerial-hidden:{clip}"), "a hidden clip should show the eye-slash"
    settings.select()
    tv.wait_until(lambda: clip not in _config(tv).get("screensaver.hiddenAerials", ""), 4, "the clip shown again")


@pytest.fixture
def album(tv):
    """A one-photo album the slideshow can find (MediaStore indexed), photo access granted. The grant stays:
    the slideshow needs it anyway, and revoking a runtime permission kills Glass's process."""
    tv.sh(f"mkdir -p {ALBUM_DIR}")
    tv.adb("push", str(FIXTURE), f"{ALBUM_DIR}/slideshow.jpg")
    tv.sh("content call --uri content://media --method scan_volume --arg external_primary")
    tv.sh(f"pm grant dev.glasslauncher {PHOTOS}")
    yield "glass-e2e"
    tv.sh(f"rm -rf {ALBUM_DIR}")
    tv.sh("content call --uri content://media --method scan_volume --arg external_primary")


def test_slideshow_preview_plays_the_chosen_album(tv, home, settings, album):
    _open(tv, settings)
    settings.open_page("Slideshow", "Choose Photos")
    settings.open_page("Choose Photos", "All Photos")
    settings.focus_text(album)
    settings.select()
    home.back()
    tv.wait_until(lambda: settings.value_of("Choose Photos") == album, 4, "the album chosen")
    try:
        settings.focus_text("Preview")
        settings.select()
        tv.wait_until(lambda: tv.resumed().endswith("AerialActivity"), 8, "the slideshow in front")
        # The fixture is a strong magenta: once it has faded in, most of the screen is red+blue, little green.
        def magenta():
            img = tv.screen_image(colour=True).resize((64, 36))
            px = list(img.getdata())
            return sum(1 for r, g, b in px if r > 150 and b > 100 and g < 90) / len(px)
        tv.wait_until(lambda: magenta() > 0.6, 10, "the album's photo on screen")
        tv.press("back")
        tv.wait_until(lambda: not tv.resumed().endswith("AerialActivity"), 8, "back in Settings")
    finally:
        settings.focus_text("Choose Photos")
        settings.select()
        tv.wait_for(lambda t: t.has_text("All Photos"), 6, "the album list")
        settings.focus_text("All Photos")
        settings.select()
        home.back()
