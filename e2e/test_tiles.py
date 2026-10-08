"""Tile art for apps that ship no TV banner."""

import io
import subprocess

import pytest
from PIL import Image, ImageStat


def _ring(img, cx, cy, half, inset_from, inset_to):
    """Mean luminance of a square ring around (cx, cy): from half+inset_from to half+inset_to."""
    vals = []
    a, b = half + inset_from, half + inset_to
    px = img.load()
    for y in range(int(cy - b), int(cy + b)):
        for x in range(int(cx - b), int(cx + b)):
            dx, dy = abs(x - cx), abs(y - cy)
            if max(dx, dy) >= a:
                vals.append(px[x, y])
    return sum(vals) / len(vals)


def test_generated_tile_has_no_dark_frame_around_the_icon(tv, home):
    """Regression: a 60-alpha black plate and shadow behind the icon showed as a dark grey frame."""
    pkg = "com.estrongs.android.pop"
    if not tv.tree().find(rid=f"app:{pkg}") and pkg not in tv.sh(f"pm list packages {pkg}"):
        pytest.skip("ES File Explorer isn't installed")
    home.focus(lambda n: n.rid.startswith("app:") and not home.in_dock(tv.tree(), n), "a grid app")
    home.focus_app("com.esaba.downloader")  # a neighbour, so ES is drawn at rest (no focus scale)
    n = tv.tree().find(rid=f"app:{pkg}")
    png = subprocess.run(["adb", "-s", tv.serial, "exec-out", "screencap", "-p"], capture_output=True).stdout
    l, t, r, b = n.bounds
    tile = Image.open(io.BytesIO(png)).convert("L").crop((l, t, r, b))
    h = b - t
    cx, cy, half = (r - l) / 2, h / 2, h * 0.32  # the icon area is 64% of the tile height, centred
    near = _ring(tile, cx, cy, half, -2, 2)  # straddles the icon square edge, where the plate showed
    far = _ring(tile, cx, cy, half, h * 0.12, h * 0.17)
    assert near >= far * 0.93, f"dark frame around the icon: band next to it {near:.0f} vs wash {far:.0f}"
