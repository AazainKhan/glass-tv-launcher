"""Now Playing: Home takeover, the Control Center card and its controls, and the way back.

Plays a generated, tagged track (fixtures/track.mp3: "Glass Test Track" by "Glass E2E", with cover art)
in VLC, which publishes a media session like Spotify or Amazon Music does.
"""

from pathlib import Path

import time

import pytest

TRACK = Path(__file__).parent / "fixtures" / "track.mp3"
REMOTE = "/sdcard/Music/glass-test-track.mp3"
VLC = "org.videolan.vlc"


def _vlc_playing(tv) -> bool:
    out = tv.sh("dumpsys media_session")
    block = out.split(f"package={VLC}", 1)[1][:1500] if f"package={VLC}" in out else ""
    return "state=3" in block and "Glass Test Track" in block


@pytest.fixture
def playing(tv, home):
    if VLC not in tv.sh(f"pm list packages {VLC}"):
        pytest.skip("VLC isn't installed")
    tv.adb("push", str(TRACK), REMOTE, timeout=60)
    # VLC sometimes opens the file without starting it (its session stays STOPPED), so: until it plays.
    for attempt in range(3):
        tv.sh(f"am force-stop {VLC}")
        tv.sh(f"am start -a android.intent.action.VIEW -d file://{REMOTE} -t audio/mpeg -p {VLC}")
        try:
            tv.wait_until(lambda: _vlc_playing(tv), 12, "VLC playing the test track")
            break
        except AssertionError:
            if attempt == 2:
                raise
    tv.home_intent()
    tv.wait_until(lambda: tv.resumed_package() == "dev.glasslauncher", 8, "Glass in front")
    yield
    tv.sh(f"am force-stop {VLC}")
    tv.sh(f"rm -f {REMOTE}")
    home.reset()


def test_music_takes_over_home(tv, home, playing):
    tree = tv.wait_for(lambda t: t.find(rid="now-playing-hero") and t, 10, "the Now Playing hero on Home")
    assert tree.has_text("Glass Test Track") and tree.has_text("Glass E2E"), "track and artist aren't shown"
    assert not tree.has_text("Press up for full screen"), "the featured shelf hint should give way"
    # The backdrop is the cover art (blue, with a yellow square), blurred full-bleed: the right edge of the
    # screen, clear of the centred hero, comes out clearly blue once it's baked (about a second).
    import io, subprocess
    from PIL import Image

    def edge_colour():
        png = subprocess.run(["adb", "-s", tv.serial, "exec-out", "screencap", "-p"], capture_output=True).stdout
        return Image.open(io.BytesIO(png)).convert("RGB").crop((1720, 150, 1900, 650)).resize((1, 1), Image.BOX).getpixel((0, 0))

    seen = []
    tv.wait_until(lambda: seen.append(edge_colour()) or seen[-1][2] > seen[-1][0] + 25, 8,
                  "the album art as the backdrop")


def test_hero_is_centred_with_playback_controls(tv, home, playing):
    tree = tv.wait_for(lambda t: t.find(rid="now-playing-hero") and t, 10, "the Now Playing hero")
    hero = tree.find(rid="now-playing-hero")
    l, t, r, b = hero.bounds
    assert abs((l + r) / 2 - 960) < 60, f"the hero should be centred ({hero.bounds})"
    for label in ("Previous Track", "Pause", "Next Track"):
        btn = tree.find(desc=label)
        assert btn and l <= btn.center[0] <= r and t <= btn.center[1] <= b, f"no '{label}' control in the hero"


def test_up_from_the_tray_reaches_the_controls_then_control_center(tv, home, playing):
    tv.wait_for(lambda t: t.find(rid="now-playing-hero"), 10, "the Now Playing hero")
    tv.press("up")
    tv.wait_for(lambda t: t.focused() and t.focused().desc == "Pause", 4, "Up from the tray onto Pause")
    home.select()
    tv.wait_for(lambda t: t.focused() and t.focused().desc == "Play", 6, "the hero's button pausing playback")
    home.select()
    tv.wait_for(lambda t: t.focused() and t.focused().desc == "Pause", 6, "playing again")
    tv.press("up")
    tv.wait_for(lambda t: t.focused() and t.focused().rid == "status-pill", 4, "Up again onto the status pill")
    home.select()
    tv.wait_for(lambda t: t.find(rid="control-center"), 6, "Control Center from the pill while music plays")
    card = tv.wait_for(lambda t: t.find(rid="now-playing-card"), 4, "the Now Playing card")
    assert card.bounds[3] <= 1080 - 30, f"the card should keep a margin above the bottom edge ({card.bounds})"
    home.back()


def test_control_center_card_controls_playback(tv, home, cc, playing):
    # Music already playing when Control Center opens, as it would be for a viewer.
    tv.wait_for(lambda t: t.find(rid="now-playing-hero"), 10, "the Now Playing hero")
    time.sleep(2)
    cc.open()
    tv.wait_for(lambda t: t.find(rid="now-playing-card") and t.has_text("Glass Test Track"), 8, "the Now Playing card")
    cc.focus_desc("Pause")
    card = tv.wait_for(lambda t: t.find(rid="now-playing-card"), 3, "the card")
    assert card.bounds[3] <= 1080, f"Control Center should scroll the focused card fully on screen ({card.bounds})"
    cc.select()
    # The button follows the session's own state, so Play appearing means VLC really paused.
    tv.wait_for(lambda t: t.find(desc="Play"), 6, "playback to pause")
    cc.select()
    tv.wait_for(lambda t: t.find(desc="Pause"), 6, "playing again")


def test_home_returns_to_the_featured_shelf_when_music_stops(tv, home, playing):
    tv.wait_for(lambda t: t.find(rid="now-playing-hero"), 10, "the Now Playing hero")
    tv.sh(f"am force-stop {VLC}")
    tv.wait_for(lambda t: not t.find(rid="now-playing-hero"), 10, "the hero to give way")
    if tv.tree().find(rid="featured-row") is None:
        tv.wait_for(lambda t: t.has_text("Press up for full screen") or not t.find(rid="now-playing-hero"), 8, "the shelf back")


@pytest.mark.perf
def test_now_playing_takeover_keeps_home_smooth(tv, home, playing):
    tv.wait_for(lambda t: t.find(rid="now-playing-hero"), 10, "the Now Playing hero")
    time.sleep(4)  # the album-art backdrop bakes once as the takeover starts; measure browsing, not that
    tv.su("pkill screenrecord")
    tv.frames_reset()
    for _ in range(3):
        tv.press("right", "wait:600", "right", "wait:600", "left", "wait:600", "left", "wait:600", "down", "wait:900", "up", "wait:900")
    f = tv.frames()
    # Home without music measures ~11% here (the featured slideshow); the takeover must not be worse.
    assert f["janky_pct"] < 10 and f["p90"] <= 16, f"browsing Home with Now Playing up: {f}"


def test_down_from_the_controls_and_pill_returns_to_the_tray(tv, home, playing):
    """With music up: Up reaches the Now Playing controls, Up again the pill; Down, Down come back to the
    same tray app (it used to need the Home button)."""
    start = tv.tree().focused()
    tv.press("up")
    tv.wait_for(lambda t: t.focused() and not t.focused().rid.startswith("app:"), 3, "the Now Playing controls")
    tv.press("up")
    tv.wait_for(lambda t: t.focused() and t.focused().rid == "status-pill", 3, "the status pill")
    tv.press("down")
    tv.press("down")
    back = tv.wait_for(lambda t: t.focused() and t.focused().rid.startswith("app:") and t.focused(), 4, "focus back on the tray")
    assert back.rid == start.rid, f"expected {start.rid}, landed on {back.rid}"
