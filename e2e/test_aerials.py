"""Aerials: clips change through black (the old one fades out, the new one fades up), never a hard cut.

Measured from a screen recording, frame by frame: screenshots take ~2 s each, far too slow for a 1.4 s fade.
"""

import re
import subprocess
import tempfile
from pathlib import Path

import pytest

AERIAL = "dev.glasslauncher/.dream.AerialActivity"
NEAR_END = "dev.glasslauncher.extra.NEAR_END_MS"
CLIP = "/data/local/tmp/glass-aerial.mp4"


def _frame_levels(video: Path) -> list[float]:
    """Average luma (0..255) of every frame."""
    out = subprocess.run(
        ["ffmpeg", "-hide_banner", "-i", str(video), "-vf", "signalstats,metadata=print:key=lavfi.signalstats.YAVG", "-f", "null", "-"],
        capture_output=True, text=True, timeout=120,
    ).stderr
    return [float(v) for v in re.findall(r"lavfi\.signalstats\.YAVG=([\d.]+)", out)]


@pytest.mark.root
@pytest.mark.slow
def test_aerial_clips_change_through_black(tv, home, rooted):
    if not rooted:
        pytest.skip("AerialActivity isn't exported; starting it near a clip's end needs root")
    if subprocess.run(["which", "ffmpeg"], capture_output=True).returncode != 0:
        pytest.skip("ffmpeg isn't installed on this computer")
    tv.su(f"am start -n {AERIAL} --el {NEAR_END} 9000")
    try:
        tv.wait_until(lambda: tv.resumed().endswith("AerialActivity"), 8, "Aerials in front")
        # Record from the start: the first clip loads, starts 9 s before its end, then the next one comes in.
        tv.sh(f"screenrecord --size 480x270 --bit-rate 2000000 --time-limit 22 {CLIP}", timeout=40)
        with tempfile.TemporaryDirectory() as d:
            local = Path(d) / "aerial.mp4"
            tv.adb("pull", CLIP, str(local), timeout=60)
            levels = _frame_levels(local)
        assert len(levels) > 60, f"the recording is too short ({len(levels)} frames)"
        # Recorded video is limited-range: black is luma 16, not 0. Clips can be night scenes (~25-35), so
        # "lit" is measured from black, not as an absolute brightness.
        black, lit = 16, 22
        bright = [i for i, v in enumerate(levels) if v > lit]
        assert bright, f"no clip ever showed: {levels[::10]}"
        start = bright[0]
        after = levels[start:]
        dip = min(range(len(after)), key=lambda i: after[i])
        assert after[dip] < black + 3, f"no fade to black between clips (darkest {after[dip]:.0f}/255)"
        assert max(after[dip:], default=0) > lit, "the next clip never came up after the black"
        # A hard cut is a one-frame jump between two lit frames; fades move a little each frame.
        jumps = [(i, abs(b - a)) for i, (a, b) in enumerate(zip(after, after[1:])) if min(a, b) > lit]
        worst = max(jumps, key=lambda j: j[1], default=(0, 0))
        assert worst[1] < 45, f"a hard cut at frame {worst[0]}: luma jumped {worst[1]:.0f}/255 in one frame"
    finally:
        tv.sh(f"rm -f {CLIP}")
        tv.press("back")
        tv.wait_until(lambda: not tv.resumed().endswith("AerialActivity"), 8, "back on Home")
