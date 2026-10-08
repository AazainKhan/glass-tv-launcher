"""Control Center over any app: the remote's Settings button opens it on top of whatever is playing, with
the screen behind dimmed (tvOS 27), not blurred, and the app left running underneath."""

import io
import subprocess

import pytest
from PIL import Image, ImageFilter, ImageStat

from pages import CONTROL_CENTER

APP = "com.esaba.downloader"
REMOTE_LAYOUT = "/system/usr/keylayout/Vendor_0171_Product_0427.kl"


def _press_settings_button(tv):
    tv.press("wait:1")  # makes sure scripts/key has pushed glass-press
    tv.sh("/data/local/tmp/glass-press 120 185")  # what glass-keymap turns the Settings button's 249 into


def _needs_remote_keys(tv):
    if "BUTTON_9" not in tv.sh(f"cat {REMOTE_LAYOUT}"):
        pytest.skip("remote-keys module not installed")
    if "RemoteKeysService" not in tv.sh("settings get secure enabled_accessibility_services"):
        pytest.skip("Glass Launcher Remote Buttons (accessibility) is off")


def _shot(tv) -> Image.Image:
    png = subprocess.run(["adb", "-s", tv.serial, "exec-out", "screencap", "-p"], capture_output=True).stdout
    return Image.open(io.BytesIO(png)).convert("L")


def _detail(img: Image.Image) -> float:
    """How much fine detail a region has (mean edge strength): a blur wipes it out, a dim only scales it."""
    return ImageStat.Stat(img.filter(ImageFilter.FIND_EDGES)).mean[0]


@pytest.fixture
def in_app(tv, home):
    _needs_remote_keys(tv)
    tv.launch(APP)
    tv.wait_until(lambda: tv.resumed_package() == APP, 10, "Downloader in front")
    yield
    tv.sh(f"am force-stop {APP}")


@pytest.mark.root
def test_settings_button_opens_control_center_over_the_app(tv, home, in_app):
    _press_settings_button(tv)
    tv.wait_for(lambda t: t.find(rid="control-center"), 6, "Control Center over Downloader")
    assert tv.resumed_package() == APP, "the app should keep running underneath"
    home.back()
    tv.wait_for(lambda t: not t.find(rid="control-center"), 4, "Back to close it")
    assert tv.resumed_package() == APP, "closing Control Center should leave the app in front"


@pytest.mark.root
def test_settings_button_again_closes_it(tv, home, in_app):
    _press_settings_button(tv)
    tv.wait_for(lambda t: t.find(rid="control-center"), 6, "Control Center")
    _press_settings_button(tv)
    tv.wait_for(lambda t: not t.find(rid="control-center"), 4, "the second press to close it")


@pytest.mark.root
def test_control_center_closes_when_another_app_comes_forward(tv, home, in_app):
    _press_settings_button(tv)
    tv.wait_for(lambda t: t.find(rid="control-center"), 6, "Control Center")
    tv.home_intent()
    tv.wait_until(lambda: tv.resumed_package() == "dev.glasslauncher", 8, "Glass in front")
    tv.wait_for(lambda t: not t.find(rid="control-center"), 4, "Control Center gone with the app")


def test_home_behind_control_center_is_dimmed_not_blurred(tv, home):
    region = (60, 760, 1200, 1000)  # the dock, left of Control Center's column
    before = _shot(tv).crop(region)
    tv.intent(CONTROL_CENTER)
    tv.wait_for(lambda t: t.find(rid="control-center") and t.focused(), 8, "Control Center")
    import time
    time.sleep(0.6)
    after = _shot(tv).crop(region)
    ratio = ImageStat.Stat(after).mean[0] / max(1.0, ImageStat.Stat(before).mean[0])
    assert 0.4 < ratio < 0.85, f"Home should be dimmed (brightness ratio {ratio:.2f})"
    kept = _detail(after) / max(0.01, _detail(before))
    assert kept > ratio * 0.6, f"Home should stay sharp behind the dim, not blurred (detail kept {kept:.2f})"


@pytest.mark.perf
def test_control_center_opens_and_closes_smoothly(tv, home):
    """As an overlay window over a dim, Home isn't redrawn or captured: opening should stay within budget."""
    import time
    # From the grid, where Home's featured slideshow is paused: its cross-fades (Home's own cost) landed at
    # random inside the measurement and swung it from 11% to 40%.
    tv.press("down", "down", "wait:1500")
    tv.su("pkill screenrecord")
    tv.frames_reset()
    for _ in range(5):
        tv.intent(CONTROL_CENTER)
        tv.wait_for(lambda t: t.find(rid="control-center"), 6, "Control Center")
        time.sleep(0.8)
        tv.press("back")
        tv.wait_for(lambda t: not t.find(rid="control-center"), 4, "closed")
        time.sleep(0.5)
    f = tv.frames()
    assert f["janky_pct"] < 10 and f["p90"] <= 16, f"Control Center open/close: {f}"


CARD = "/sdcard/Download/glass-e2e-card.jpg"
VIEWER = "com.estrongs.android.pop"


@pytest.fixture
def card(tv, home):
    """A colourful app in front: the magenta test card full screen in ES File Explorer's image viewer."""
    from pathlib import Path
    _needs_remote_keys(tv)
    if VIEWER not in tv.sh(f"pm list packages {VIEWER}"):
        pytest.skip("ES File Explorer (the image viewer) isn't installed")
    tv.adb("push", str(Path(__file__).parent / "fixtures" / "slideshow.jpg"), CARD)
    tv.sh(f"am start -n {VIEWER}/.app.PopRemoteImageBrowser -a android.intent.action.VIEW -d file://{CARD} -t image/jpeg")
    tv.wait_until(lambda: tv.resumed_package() == VIEWER, 10, "the test card in front")
    import time
    time.sleep(1.5)
    yield
    tv.sh(f"am force-stop {VIEWER}; rm -f {CARD}")


def _open_over_app(tv):
    _press_settings_button(tv)
    return tv.wait_for(lambda t: t.find(rid="control-center") and t, 6, "Control Center over the app")


@pytest.mark.perf
def test_control_center_opens_and_closes_smoothly_over_an_app(tv, home, card):
    """Over another app the overlay is all Glass draws: open and close stay within budget there too."""
    import time
    tv.su("pkill screenrecord")
    tv.frames_reset()
    for _ in range(5):
        _open_over_app(tv)
        time.sleep(0.8)
        tv.press("back")
        tv.wait_for(lambda t: not t.find(rid="control-center"), 4, "closed")
        time.sleep(0.5)
    f = tv.frames()
    assert tv.resumed_package() == VIEWER, "the app should still be in front"
    assert f["janky_pct"] < 10 and f["p90"] <= 16, f"Control Center over an app: {f}"


def _saturation(img: Image.Image, box) -> float:
    return ImageStat.Stat(img.crop(box).convert("HSV").split()[1]).mean[0]


def _rgb_shot(tv) -> Image.Image:
    png = subprocess.run(["adb", "-s", tv.serial, "exec-out", "screencap", "-p"], capture_output=True).stdout
    return Image.open(io.BytesIO(png)).convert("RGB")


def test_control_center_tiles_are_clear_glass(tv, home, card):
    """The tiles are the tray's clear glass: the colour of what's behind shows through them, not a flat
    milky fill (the overlay window can't sample the screen, so it bakes a capture of it)."""
    import time
    before = _rgb_shot(tv)
    tree = _open_over_app(tv)
    tile = tree.find(desc_prefix="Launcher Settings")
    l, t, r, b = tile.bounds
    # The tile's right part (past its label), inset from the 48 dp accessibility padding.
    box = (l + (r - l) * 2 // 3, t + (b - t) // 3, r - 30, b - (b - t) // 3)
    behind = _saturation(before, box)
    assert behind > 100, f"the test card isn't behind the tile ({behind:.0f})"
    # The glass arrives a moment after the tiles (a capture of the screen, baked); allow for a retry.
    seen = []
    deadline = time.time() + 3
    while time.time() < deadline:
        seen.append(_saturation(_rgb_shot(tv), box))
        if seen[-1] > behind * 0.8:
            break
    tv.press("back")
    assert seen[-1] > behind * 0.8, f"the tile washes out what's behind it (saturation {[round(x) for x in seen]} vs {behind:.0f} behind)"
