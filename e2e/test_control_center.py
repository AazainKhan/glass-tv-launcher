"""Control Center: opening, contents, clock and weather, toggles (each restored), closing."""

import time

import pytest

from pages import clock_ok, date_ok


def test_opens_with_every_control(tv, home, cc, rooted):
    tree = cc.open()
    for prefix in ["Settings, Fire TV", "Wi-Fi", "Bluetooth", "Launcher Settings",
                   "Game Controllers", "Theme, ", "Screen Saver", "App Switcher"]:
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
        cc.press_tile("Theme, ")
        tv.wait_for(lambda t: cc.appearance(t) != before, 8, "appearance to change")
    finally:
        now = cc.appearance()
        if now != before:
            cc.press_tile("Theme, ")
            tv.wait_for(lambda t: cc.appearance(t) == before, 8, "appearance to be restored")


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


# ── Round of fixes: state, labels, weather, customisation ─────────────────────────────────────────

TILE_PREFIXES = ["Settings, Fire TV", "Wi-Fi", "Bluetooth", "Launcher Settings", "Game Controllers",
                 "Theme, ", "Screen Saver", "App Switcher"]
ROUND = ["Game Controllers", "Theme, ", "Screen Saver", "App Switcher"]


def _tile_brightness(tv, cc, prefixes, skip_focused=True):
    tree = tv.tree()
    img = tv.screen_image()
    out = {}
    for p in prefixes:
        n = cc.tile(tree, p)
        if n and not (skip_focused and n.focused):
            out[p] = tv.brightness(img, n.bounds)
    return out


import contextlib


@contextlib.contextmanager
def _theme(tv, home, cc, want):
    """Runs the block in Light or Dark appearance, then puts the original back."""
    start = cc.appearance(cc.open())
    if start != want:
        cc.press_tile("Theme, ")
        tv.wait_for(lambda t: cc.appearance(t) == want, 8, f"{want} appearance")
    home.reset()
    try:
        yield
    finally:
        home.reset()
        if cc.appearance(cc.open()) != start:
            cc.press_tile("Theme, ")
            tv.wait_for(lambda t: cc.appearance(t) == start, 8, "appearance restored")
        home.reset()


@pytest.mark.parametrize("theme", ["Light", "Dark"])
def test_tiles_keep_their_look_after_opening_settings_from_control_center(tv, home, cc, theme):
    """Regression: after Launcher Settings opened from Control Center, Bluetooth etc. went see-through."""
    with _theme(tv, home, cc, theme):
        _check_tiles_after_settings(tv, home, cc)


def _check_tiles_after_settings(tv, home, cc):
    cc.open()
    tv.press("wait:600")
    before = _tile_brightness(tv, cc, TILE_PREFIXES)
    cc.press_tile("Launcher Settings")
    tv.wait_for(lambda t: t.top_overlay() == "Settings", 6, "Launcher Settings")
    tv.press("back")
    tv.wait_for(lambda t: not t.overlays(), 6, "back on Home")
    cc.open()
    tv.press("wait:600")
    after = _tile_brightness(tv, cc, TILE_PREFIXES)
    for k in before:
        if k in after:
            assert abs(after[k] - before[k]) <= 0.08, f"'{k}' changed look: {before[k]:.2f} → {after[k]:.2f}"


@pytest.mark.parametrize("theme", ["Light", "Dark"])
def test_focus_is_a_white_capsule_and_others_stay_put(tv, home, cc, theme):
    """The focused round button is a white capsule (tvOS) in both themes; unfocused ones don't change as focus moves."""
    with _theme(tv, home, cc, theme):
        _check_round_focus(tv, cc)


def _check_round_focus(tv, cc):
    cc.open()
    seen = {}
    for name in ROUND:
        cc.focus_desc_prefix(name)
        tv.press("wait:300")
        tree, img = tv.tree(), tv.screen_image()
        f = tree.focused()
        assert tv.brightness(img, f.bounds) > 0.62, f"focused '{f.desc}' isn't a white capsule"
        for other in ROUND:
            n = cc.tile(tree, other)
            if n and not n.focused:
                seen.setdefault(other, []).append(tv.brightness(img, n.bounds))
    for name, vals in seen.items():
        assert max(vals) - min(vals) <= 0.08, f"'{name}' flips look while focus moves: {[round(v, 2) for v in vals]}"


def test_every_control_has_a_label_when_focused(tv, home, cc):
    cc.open()
    for name, label in [("Game Controllers", "Game Controllers"), ("Theme, ", "Theme"),
                        ("Screen Saver", "Screen Saver"), ("App Switcher", "App Switcher")]:
        cc.focus_desc_prefix(name)
        tv.wait_for(lambda t: any(n.text.startswith(label) for n in t.nodes()), 3, f"the '{label}' label")


def test_pill_and_control_center_show_the_same_weather(tv, home, cc):
    pill = tv.tree().find(rid="weather")
    if not pill:
        pytest.skip("no weather city set")
    tree = cc.open()
    weathers = [n.label for n in tree.nodes() if n.rid == "weather"]
    assert pill.label and pill.label in weathers, f"pill says {pill.label!r}, Control Center says {weathers}"


def test_text_size_is_not_in_control_center(tv, home, cc):
    assert not cc.tile(cc.open(), "Text Size"), "Text Size moved to Settings › Display & Text Size"


def test_control_center_tiles_can_be_turned_off_in_settings(tv, home, cc, settings):
    settings.open_from_control_center()
    settings.open_page("Control Center")
    settings.focus_text("Game Controllers")
    assert settings.toggle_state("Game Controllers") is True
    settings.select()
    try:
        tv.wait_until(lambda: settings.toggle_state("Game Controllers") is False, 4, "the toggle to turn off")
        home.reset()
        assert not cc.tile(cc.open(), "Game Controllers"), "a turned-off tile still shows"
    finally:
        home.reset()
        settings.open_from_control_center()
        settings.open_page("Control Center")
        settings.focus_text("Game Controllers")
        if settings.toggle_state("Game Controllers") is False:
            settings.select()
        home.reset()
