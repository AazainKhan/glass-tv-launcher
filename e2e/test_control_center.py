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
        # Free Memory became Clean Up (cb668ee3: Sleep Timer, VPN, Restart and Clean Up controls).
        assert cc.tile(tree, "Clean Up"), "rooted, but no Clean Up control"
    assert tree.focused() and tree.focused().desc.startswith("Settings"), "focus should start on Settings"


def test_header_is_left_aligned_with_the_page_icons_on_the_right(tv, home, cc):
    """One line (tvOS 27, F03): the time without seconds and, when there is one, the weather beside it, left-aligned with the tiles;
    no date line. The page icons stay at the right of the same band, with a gap between the two."""
    tree = cc.open()
    clock = tree.find(rid="cc-clock")
    assert clock and clock_ok(clock.text, seconds=False), f"clock reads {clock and clock.text!r}"
    assert not tree.find(rid="cc-date"), "the header has no date line any more (F03)"
    weather = next((n for n in tree.nodes() if n.rid == "weather"), None)
    if weather:
        assert weather.bounds[0] >= clock.bounds[2] - 2, f"the weather should sit beside the time, not over it ({clock.bounds} vs {weather.bounds})"
        centres = [(n.bounds[1] + n.bounds[3]) / 2 for n in (clock, weather)]
        assert abs(centres[0] - centres[1]) <= 6, f"the time and the weather share one line (P196): centres {centres}"
    settings_tile = cc.tile(tree, "Settings, Fire TV")
    assert abs(clock.bounds[0] - settings_tile.bounds[0]) <= 24, f"the header should line up with the tiles' left edge ({clock.bounds} vs {settings_tile.bounds})"
    icons = cc.tile(tree, "Controls")
    if icons:
        assert icons.bounds[0] > (weather or clock).bounds[2] + 20, "the page icons sit at the right, apart from the time"


def test_control_center_looks_the_same_in_light_and_dark(tv, home, cc):
    """One material for Control Center, like the pill and tray: white glyphs on clear glass in either theme."""
    def brightest(img, bounds):
        """The 99th-percentile brightness: the tile's text and glyphs (white in tvOS's Control Center)."""
        hist = img.crop(bounds).histogram()
        total, seen = sum(hist), 0
        for level in range(255, -1, -1):
            seen += hist[level]
            if seen >= total * 0.01:
                return level
        return 0

    looks = {}
    for theme in ("Light", "Dark"):
        with _theme(tv, home, cc, theme):
            tree = cc.open()
            tv.press("wait:600")
            img = tv.screen_image()
            looks[theme] = {p: brightest(img, cc.tile(tree, p).bounds) for p in ("Wi-Fi", "Bluetooth", "Launcher Settings")}
            home.reset()
    for tile in looks["Dark"]:
        assert abs(looks["Light"][tile] - looks["Dark"][tile]) < 25, f"'{tile}' text differs between themes: {looks}"


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
    """Clean Up (cb668ee3, was Free Memory) shows what it freed on its own tile for a few seconds."""
    if not rooted:
        pytest.skip("needs root")
    import re
    cc.open()
    cc.press_tile("Clean Up")
    done = r"Clean Up, (\d+ MB|[\d.]+ GB|\d+ MB memory|Nothing to clean|Freed no memory)"
    tv.wait_for(lambda t: any(re.fullmatch(done, n.desc) for n in t.nodes()), 20, "Clean Up's result on its tile")


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
@pytest.mark.emulator_gap  # passes on the stick; the emulator differs (P22)
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
    """Focus is a lift, not a white fill (P173, P206 recipe E: the dock's clear glass, the same glass focused or not): the focused round
    button is larger than it is at rest, and the unfocused ones don't change look as focus moves. Both themes."""
    with _theme(tv, home, cc, theme):
        _check_round_focus(tv, cc)


def _check_round_focus(tv, cc):
    cc.open()
    seen, size_focused, size_rest = {}, {}, {}
    for name in ROUND:
        cc.focus_desc_prefix(name)
        tv.press("wait:300")
        tree, img = tv.tree(), tv.screen_image()
        f = tree.focused()
        size_focused[name] = f.bounds[2] - f.bounds[0]
        for other in ROUND:
            n = cc.tile(tree, other)
            if n and not n.focused:
                seen.setdefault(other, []).append(tv.brightness(img, n.bounds))
                size_rest.setdefault(other, []).append(n.bounds[2] - n.bounds[0])
    for name, vals in seen.items():
        assert max(vals) - min(vals) <= 0.08, f"'{name}' flips look while focus moves: {[round(v, 2) for v in vals]}"
    for name, focused in size_focused.items():
        rest = min(size_rest.get(name, [focused]))
        # tvOS's lift is an absolute 10 dp in total (CcSizes.focusGrowth), 20 px at 1080p.
        assert focused - rest >= 12, f"focused '{name}' is {focused}px wide, {rest}px at rest: no lift"


def test_every_control_has_a_label_when_focused(tv, home, cc):
    cc.open()
    for name, label in [("Game Controllers", "Game Controllers"), ("Theme, ", "Theme"),
                        ("Screen Saver", "Screen Saver"), ("App Switcher", "App Switcher")]:
        cc.focus_desc_prefix(name)
        tv.wait_for(lambda t: any(n.text.startswith(label) for n in t.nodes()), 3, f"the '{label}' label")


def test_control_center_shows_the_weather_reading(tv, home, cc):
    """The weather moved from the pill into Control Center's header: a condition and a temperature."""
    import re
    tree = cc.open()
    weather = next((n for n in tree.nodes() if n.rid == "weather"), None)
    if weather is None:
        pytest.skip("no weather city set")
    assert re.fullmatch(r"[A-Za-z ]+, -?\d+°", weather.label), f"weather reads {weather.label!r}"


def test_text_size_is_not_in_control_center(tv, home, cc):
    assert not cc.tile(cc.open(), "Text Size"), "Text Size moved to Settings › Display & Text"


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


ALEXA_TILES = ["Smart Home", "Ask Alexa", "Alexa Settings"]


@pytest.mark.stick  # Alexa only exists on Fire OS
def test_alexa_page_shows_its_shortcuts(tv, home, cc):
    """A row of page icons at the top of Control Center: Controls (the tiles) and Alexa (shortcuts)."""
    tree = cc.open()
    assert cc.tile(tree, "Controls") and cc.tile(tree, "Alexa"), "no page switcher"
    assert not any(cc.tile(tree, t) for t in ALEXA_TILES), "Control Center should open on Controls"
    cc.focus_desc("Alexa")
    tree = tv.wait_for(lambda t: all(cc.tile(t, n) for n in ALEXA_TILES) and t, 4, "the Alexa page")
    assert not cc.tile(tree, "Wi-Fi"), "the Controls tiles should give way"
    assert not cc.tile(tree, "Cameras"), "no cameras are set up: the tile is gone"
    cc.focus_desc("Controls")
    tv.wait_for(lambda t: cc.tile(t, "Wi-Fi"), 4, "back to Controls")


@pytest.mark.slow
@pytest.mark.stick  # Alexa only exists on Fire OS
@pytest.mark.skip(reason="Select on the Alexa page controls real devices: scripts/key refuses it by design (glass-guard). The page itself is covered by test_alexa_page_shows_its_shortcuts.")
def test_smart_home_opens_its_app(tv, home, cc):
    cc.open()
    cc.focus_desc("Alexa")
    tv.wait_for(lambda t: cc.tile(t, "Smart Home"), 4, "the Alexa page")
    cc.press_tile("Smart Home")
    try:
        tv.wait_until(lambda: tv.resumed_package() == "com.amazon.smarthomemapviewapp", 10, "the Smart Home dashboard")
    finally:
        tv.sh("am force-stop com.amazon.smarthomemapviewapp")


def _focused_window(tv) -> str:
    return tv.sh("dumpsys window | grep -m1 mCurrentFocus")


def _alexa_listening(tv) -> bool:
    """Alexa's voice overlay (its own window, not an activity such as its settings) has focus."""
    return "u0 com.amazon.vizzini}" in _focused_window(tv)


@pytest.mark.slow
@pytest.mark.root
@pytest.mark.skip(reason="Select on the Alexa page controls real devices: scripts/key refuses it by design (glass-guard). The page itself is covered by test_alexa_page_shows_its_shortcuts.")
def test_ask_alexa_brings_up_alexas_voice_ui(tv, home, cc, rooted):
    """Ask Alexa puts Alexa's own listening UI on screen (as holding the remote's mic button does)."""
    if not rooted:
        pytest.skip("needs root (a virtual mic-button press)")
    cc.open()
    cc.focus_desc("Alexa")
    tv.wait_for(lambda t: cc.tile(t, "Ask Alexa"), 4, "the Alexa page")
    cc.press_tile("Ask Alexa")
    tv.wait_until(lambda: _alexa_listening(tv), 6, "Alexa's voice UI in front")
    import time
    time.sleep(1.5)
    # Held there (listening), not a flash that closes at once (what an ASSIST intent alone did).
    assert _alexa_listening(tv), "Alexa's UI closed straight away"


@pytest.mark.slow
@pytest.mark.root
@pytest.mark.skip(reason="Select on the Alexa page controls real devices: scripts/key refuses it by design (glass-guard). The page itself is covered by test_alexa_page_shows_its_shortcuts.")
def test_alexa_settings_opens_alexas_settings(tv, home, cc, rooted):
    if not rooted:
        pytest.skip("Alexa's settings screen is permission-guarded; Glass opens it as root")
    cc.open()
    cc.focus_desc("Alexa")
    tv.wait_for(lambda t: cc.tile(t, "Alexa Settings"), 4, "the Alexa page")
    cc.press_tile("Alexa Settings")
    try:
        tv.wait_until(lambda: "AlexaSettingActivity" in tv.resumed(), 8, "Alexa's settings")
    finally:
        tv.sh("am force-stop com.amazon.vizzini")


@pytest.mark.stick  # Alexa only exists on Fire OS
def test_alexa_page_can_be_turned_off_in_settings(tv, home, cc, settings):
    settings.open_from_control_center()
    settings.open_page("Control Center")
    settings.focus_text("Alexa Page")
    assert settings.toggle_state("Alexa Page") is True
    settings.select()
    try:
        tv.wait_until(lambda: settings.toggle_state("Alexa Page") is False, 4, "the switch to turn off")
        home.reset()
        tree = cc.open()
        assert not cc.tile(tree, "Alexa"), "the page switcher should go with the Alexa page"
    finally:
        home.reset()
        settings.open_from_control_center()
        settings.open_page("Control Center")
        settings.focus_text("Alexa Page")
        if settings.toggle_state("Alexa Page") is False:
            settings.select()
        home.reset()


def test_tiles_keep_one_grid_when_focused(tv, home, cc):
    """The focused Settings tile still lines up with the Wi-Fi/Bluetooth stack beside it: focus lifts it by CcSizes.focusGrowth (10 dp in
    total, 5 dp a side: 10 px at 1080p), not past that."""
    cc.open()
    import time
    time.sleep(0.6)
    tree = tv.tree()
    settings, bluetooth = tree.find(desc_prefix="Settings, Fire TV"), tree.find(desc_prefix="Bluetooth")
    assert tree.focused() and tree.focused().desc.startswith("Settings, Fire TV"), "Settings should have focus"
    wifi = tree.find(desc_prefix="Wi-Fi")
    stack = (wifi.bounds[1], bluetooth.bounds[3])
    drawn = (settings.bounds[1], settings.bounds[3])
    lift = 10  # px a side, plus a little for rounding
    assert stack[0] - drawn[0] <= lift + 4 and drawn[1] - stack[1] <= lift + 4, \
        f"the focused Settings tile spills past the grid by more than its lift: drawn {drawn} vs the pills {stack}"
    assert drawn[0] <= stack[0] + 2 and drawn[1] >= stack[1] - 2, f"the focused Settings tile should span the pills it sits beside: drawn {drawn} vs {stack}"


def test_header_lines_are_evenly_spaced(tv, home, cc):
    """The header is one line now (F03, P196): the time and the weather beside it share a midline with air between them
    (it was three lines with equal gaps)."""
    cc.open()
    import time
    time.sleep(0.8)
    tree = tv.tree()
    clock, weather = tree.find(rid="cc-clock"), tree.find(rid="weather")
    if weather is None:
        pytest.skip("no weather location set")
    gap = weather.bounds[0] - clock.bounds[2]
    assert 4 <= gap <= 60, f"the time and the weather should sit side by side with a small gap: {gap}px"
    centres = [(n.bounds[1] + n.bounds[3]) / 2 for n in (clock, weather)]
    assert abs(centres[0] - centres[1]) <= 6, f"the time and the weather share one line: centres {centres}"


ROUND_NAMES = ("Game Controllers", "Theme", "Screen Saver", "App Switcher", "AirPlay", "Launcher Settings", "Remotes")
PILL_NAMES = ("Wi-Fi", "Bluetooth", "Sleep Timer", "Clean Up", "Restart", "Performance", "VPN")


def _rows(tiles):
    """Tiles grouped into rows by their top edge (within 6 px), each row left to right."""
    rows = []
    for t in sorted(tiles, key=lambda b: (b[1], b[0])):
        if rows and abs(rows[-1][0][1] - t[1]) <= 6:
            rows[-1].append(t)
        else:
            rows.append([t])
    return [sorted(r) for r in rows]


def test_one_gap_everywhere_and_smaller_round_buttons(tv, home, cc):
    """One grid (F01 resizable controls): the same gap between neighbouring tiles, side by side and row under row, and a round button is
    one cell, as tall as a pill. The big Settings tile is left out of the row gaps (it spans two rows)."""
    cc.open()
    tree = tv.tree()
    names = ROUND_NAMES + PILL_NAMES
    tiles = [n.bounds for n in tree.nodes() if n.desc.split(",")[0] in names]
    wifi, bt = tree.find(desc_prefix="Wi-Fi").bounds, tree.find(desc_prefix="Bluetooth").bounds
    base = bt[1] - wifi[3]
    rows = _rows(tiles)
    gaps = {}
    for i, row in enumerate(rows):
        for a, b in zip(row, row[1:]):
            gaps[f"row {i}: side by side"] = b[0] - a[2]
    for i, (upper, lower) in enumerate(zip(rows, rows[1:])):
        gaps[f"row {i}→{i + 1}"] = lower[0][1] - max(t[3] for t in upper)
    assert gaps, f"no neighbouring tiles found among {names}"
    assert all(abs(g - base) <= 4 for g in gaps.values()), f"uneven gaps (Wi-Fi→Bluetooth is {base}): {gaps}"
    rounds = [n.bounds for n in tree.nodes() if n.desc.split(",")[0] in ROUND_NAMES]
    round_size = rounds[0][3] - rounds[0][1]
    pill = bt[3] - bt[1]
    assert abs(round_size - pill) <= 2, f"round buttons ({round_size}px) should be one cell, as tall as the pills ({pill}px)"


def test_panel_is_one_square_grid(tv, home, cc):
    """tvOS 27's Control Center: square cells of one size u with one gap g. Circles are 1×1, pills 2×1 (as tall as a circle), the big
    tile 2×2 and square; a row of four circles is the panel's width, 4u + 3g, and the big tile and the pills reach its edges.
    (The default layout has no 4×1 row any more: Launcher Settings is a circle since F01.)"""
    cc.open()
    tv.press("right")  # off the big tile, so its focus lift doesn't change its size
    import time
    time.sleep(0.4)
    tree = tv.tree()
    b = lambda d: tree.find(desc_prefix=d).bounds
    rounds = sorted((n.bounds for n in tree.nodes() if n.desc.split(",")[0] in ROUND_NAMES), key=lambda r: (r[1], r[0]))
    u = rounds[0][2] - rounds[0][0]
    g = rounds[1][0] - rounds[0][2] if abs(rounds[1][1] - rounds[0][1]) <= 6 else rounds[2][0] - rounds[1][2]
    w = lambda r: r[2] - r[0]
    h = lambda r: r[3] - r[1]
    big, wifi, bt = b("Settings, Fire TV"), b("Wi-Fi"), b("Bluetooth")
    full_rows = [r for r in _rows(rounds) if len(r) == 4]
    checks = {
        "circle is square": (h(rounds[0]), u),
        # Bluetooth: Wi-Fi has focus here, and focus lifts a tile.
        "pill width = 2u+g": (w(bt), 2 * u + g),
        "pill height = u": (h(bt), u),
        "big tile width = 2u+g": (w(big), 2 * u + g),
        "big tile is square": (h(big), w(big)),
        "pills share the grid's right edge": (bt[2], full_rows[0][3][2]) if full_rows else (bt[2], bt[2]),
        "big tile shares its left edge": (big[0], full_rows[0][0][0]) if full_rows else (big[0], big[0]),
        "four circles span the panel": (full_rows[0][3][2] - full_rows[0][0][0], 4 * u + 3 * g) if full_rows else (0, 0),
    }
    bad = {k: v for k, v in checks.items() if abs(v[0] - v[1]) > 2}
    assert not bad, f"off the grid (u={u}, g={g}): {bad}"


@pytest.mark.emulator_gap  # passes on the stick; the emulator differs (P22)
def test_header_lines_share_one_opacity(tv, home, cc):
    """The time and the weather are drawn at the same opacity (they differed; the date line is gone since F03)."""
    cc.open()
    import time
    time.sleep(0.6)
    tree = tv.tree()
    weather_node = tree.find(rid="weather")
    if weather_node is None:
        pytest.skip("no weather location set")
    img = tv.screen_image()
    peak = lambda r: max(img.getpixel((x, y)) for x in range(r[0], min(r[2], r[0] + 200), 2) for y in range(r[1], r[3]))
    clock, weather = tree.find(rid="cc-clock").bounds, weather_node.bounds
    levels = [peak(clock), peak(weather)]
    assert max(levels) - min(levels) <= 14, f"header lines differ in brightness (time, weather): {levels}"
