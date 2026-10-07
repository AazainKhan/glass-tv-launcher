"""Control Center: opening, contents, clock and weather, toggles (each restored), closing."""

import time

import pytest

from pages import clock_ok, date_ok


def test_opens_with_every_control(tv, home, cc, rooted):
    tree = cc.open()
    for prefix in ["Settings, Fire TV", "Wi-Fi", "Bluetooth", "Launcher Settings", "Text Size",
                   "Game Controllers", "Appearance, ", "Screen Saver", "App Switcher"]:
        assert cc.tile(tree, prefix), f"Control Center has no '{prefix}' control"
    if "com.phairplay" in tv.sh("pm list packages com.phairplay"):
        assert cc.tile(tree, "AirPlay"), "PhairPlay is installed but there's no AirPlay toggle"
    if rooted:
        assert cc.tile(tree, "Performance, "), "rooted, but no Performance button"
        assert cc.tile(tree, "Free Memory"), "rooted, but no Free Memory button"
    assert tree.focused() and tree.focused().desc.startswith("Settings"), "focus should start on Settings"


def test_clock_is_right_aligned_with_seconds_and_ticks(tv, home, cc):
    tree = cc.open()
    clock = tree.find(rid="cc-clock")
    assert clock, "no clock in Control Center"
    assert clock_ok(clock.text, seconds=True), f"clock reads {clock.text!r}"
    width = tv.tree().root.children[0].bounds[2] if tree.root.children else 1920
    assert clock.bounds[2] > width * 0.8, f"clock isn't right-aligned (right edge {clock.bounds[2]} of {width})"
    date = tree.find(rid="cc-date")
    assert date and date_ok(date.text), f"Control Center date reads {date and date.text!r}"
    assert date.bounds[2] <= clock.bounds[0] and abs(date.center[1] - clock.center[1]) < 20, "date should be beside the time"
    first = clock.text
    tv.wait_for(lambda t: t.find(rid="cc-clock") and t.find(rid="cc-clock").text != first, 4, "the seconds to tick")


def test_weather_sits_beside_the_time(tv, home, cc):
    if not tv.tree().find(rid="weather"):
        pytest.skip("no weather city set")
    tree = cc.open()
    clock = tree.find(rid="cc-clock")
    weathers = [n for n in tree.nodes() if n.rid == "weather" and abs(n.center[1] - clock.center[1]) < 30]
    assert weathers and weathers[0].bounds[2] <= clock.bounds[0], "weather should be just left of the time"


def test_back_closes(tv, home, cc):
    cc.open()
    cc.back()
    tv.wait_for(lambda t: "ControlCenter" not in t.overlays(), 4, "Control Center to close")


def test_status_pill_opens_it(tv, home):
    home.focus_desc("Status and Control Center")
    home.select()
    tv.wait_for(lambda t: t.top_overlay() == "ControlCenter", 6, "Control Center from the status pill")


def test_appearance_toggles_and_back(tv, home, cc):
    before = cc.appearance(cc.open())
    try:
        cc.press_tile("Appearance, ")
        tv.wait_for(lambda t: cc.appearance(t) != before, 8, "appearance to change")
    finally:
        now = cc.appearance()
        if now != before:
            cc.press_tile("Appearance, ")
            tv.wait_for(lambda t: cc.appearance(t) == before, 8, "appearance to be restored")


def test_text_size_cycles_and_back(tv, home, cc):
    tree = cc.open()
    start = cc.tile(tree, "Text Size").desc
    seen = [start]
    for _ in range(3):  # Default → Large → Larger → Default
        cc.press_tile("Text Size")
        prev = seen[-1]
        tile = tv.wait_for(lambda t: t.find(desc_prefix="Text Size") if t.find(desc_prefix="Text Size") and t.find(desc_prefix="Text Size").desc != prev else None, 8, "text size to change")
        seen.append(tile.desc)
    assert seen[-1] == start, f"text size didn't cycle back: {seen}"
    assert len(set(seen)) == 3, f"expected three sizes, saw {seen}"


@pytest.mark.slow
def test_airplay_toggles_phairplay(tv, home, cc):
    if "com.phairplay" not in tv.sh("pm list packages com.phairplay"):
        pytest.skip("PhairPlay isn't installed")
    running = lambda: "PhairPlayService" in tv.sh("dumpsys activity services com.phairplay.firetv")
    tree = cc.open()
    was_on = cc.tile(tree, "AirPlay").desc.startswith("AirPlay, On") or cc.tile(tree, "AirPlay").desc.startswith("AirPlay, Connected")
    try:
        cc.press_tile("AirPlay")
        tv.wait_until(lambda: running() != was_on, 10, "the receiver to switch")
        tv.wait_for(lambda t: t.find(desc_prefix="AirPlay") and (t.find(desc_prefix="AirPlay").desc != ("AirPlay, On" if was_on else "AirPlay, Off")), 6, "the pill to show it")
    finally:
        if running() != was_on:
            cc.press_tile("AirPlay")
            tv.wait_until(lambda: running() == was_on, 10, "AirPlay restored")


@pytest.mark.root
def test_free_memory_reports_what_it_freed(tv, home, cc, rooted):
    if not rooted:
        pytest.skip("needs root")
    cc.open()
    cc.press_tile("Free Memory")
    tv.wait_for(lambda t: any(n.text.endswith("MB freed") for n in t.nodes()), 8, "the 'MB freed' note")


@pytest.mark.root
def test_performance_profile_applies_and_restores(tv, home, cc, rooted):
    if not rooted:
        pytest.skip("needs root")
    tree = cc.open()
    before = cc.tile(tree, "Performance, ").desc
    scale = lambda: tv.setting("global", "window_animation_scale")
    start_scale = scale()
    try:
        cc.press_tile("Performance, ")
        tv.wait_for(lambda t: t.find(desc_prefix="Performance, ").desc != before, 6, "the profile to switch")
        fast = tv.tree().find(desc_prefix="Performance, ").desc.endswith("Fast")
        tv.wait_until(lambda: scale() == ("0" if fast else "1.0"), 6, "window animation scale to follow")
    finally:
        if tv.tree().find(desc_prefix="Performance, ").desc != before:
            cc.press_tile("Performance, ")
            tv.wait_until(lambda: scale() == start_scale, 6, "animation scale restored")
