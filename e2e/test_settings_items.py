"""Every Launcher Settings item, end to end (Updates aside: it would install a release).

Each test changes something through the UI, checks the effect, and puts it back; the config-drift check in
conftest fails any test that leaves a setting changed.
"""

import re
import time

import pytest

EARLY_ACCESS_LABEL = "Fire TV Early Access"
BACKUP = "/sdcard/Download/glass-launcher-backup.json"


def test_set_up_from_phone_shows_a_link(tv, home, settings):
    settings.open_from_control_center()
    settings.focus_text("Set Up from Phone")
    settings.select()
    tree = tv.wait_for(lambda t: t.top_overlay() == "PhoneSetup" and t, 8, "the phone setup panel")
    assert any(re.search(r"https?://\d+\.\d+\.\d+\.\d+:\d+", n.text) for n in tree.nodes() if n.text), \
        "the panel should show the address to open on a phone"
    home.back()
    tv.wait_for(lambda t: t.top_overlay() == "Settings", 4, "back to Settings")


def test_featured_row_page_explains_its_sources(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Featured Row")
    for row in ["Focused App", "One Source", "Off"]:
        assert tv.tree().has_text(row) or any(row in n.label for n in tv.tree().nodes()), f"Featured Row has no '{row}'"


def test_hidden_apps_show_and_hide_again(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Hidden Apps")
    if not any(n.text == EARLY_ACCESS_LABEL for n in tv.tree().nodes()):
        pytest.skip("Fire TV Early Access isn't installed or isn't hidden")
    settings.focus_text(EARLY_ACCESS_LABEL)
    settings.select()
    tv.wait_for(lambda t: not t.has_text(EARLY_ACCESS_LABEL), 4, "Early Access shown again")
    settings.open_page("Hide Apps…", "Hide Apps")
    settings.focus_text(EARLY_ACCESS_LABEL)
    settings.select()
    tv.wait_for(lambda t: not t.has_text(EARLY_ACCESS_LABEL), 4, "Early Access hidden again")
    home.back()
    tv.wait_for(lambda t: t.has_text(EARLY_ACCESS_LABEL), 4, "Early Access back in Hidden Apps")


def test_icon_pack_page_explains_and_links_to_packs(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Icon Pack")
    tree = tv.tree()
    assert tree.has_text("None") and tree.has_text("Search the Appstore")
    assert any("ADW or Nova" in n.text for n in tree.nodes()), "the page should say which packs work"


def test_screensaver_choice_switches_and_back(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Screensaver")
    settings.focus_text("Fire TV Screensaver")
    settings.select()
    tv.wait_for(lambda t: t.find(desc="Fire TV Screensaver, ✓") or t.has_text("Fire TV Screensaver Settings"), 4, "Fire TV's screensaver chosen")
    assert not tv.tree().has_text("Preview Aerials"), "Aerials options belong to the Aerials choice"
    settings.focus_text("Aerials")
    settings.select()
    tv.wait_for(lambda t: t.has_text("Preview Aerials"), 4, "Aerials chosen again")


def test_24_hour_time_changes_the_clock(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Widgets")
    before = settings.toggle_state("24-Hour Time")
    settings.focus_text("24-Hour Time")
    settings.select()
    tv.wait_until(lambda: settings.toggle_state("24-Hour Time") == (not before), 4, "the switch to flip")
    home.reset()
    clock = tv.tree().find(rid="clock").label
    assert ("M" not in clock) == (not before), f"24-hour time {'on' if not before else 'off'}, but the clock reads {clock!r}"
    settings.open_from_control_center()
    settings.open_page("Widgets")
    settings.focus_text("24-Hour Time")
    settings.select()
    tv.wait_until(lambda: settings.toggle_state("24-Hour Time") == before, 4, "the switch back")


def test_accessibility_navigation_sounds_switch(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Accessibility")
    assert settings.value_of("Reduce Motion"), "Reduce Motion shows its mode"
    before = settings.toggle_state("Navigation Sounds")
    assert before is not None, "Navigation Sounds should be a switch"
    for want in (not before, before):
        settings.focus_text("Navigation Sounds")
        settings.select()
        tv.wait_until(lambda: settings.toggle_state("Navigation Sounds") == want, 4, "the switch to flip")


def test_home_button_page_reports_glass_as_home(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("Home Button")
    assert settings.value_of("Default Home App") == "Glass Launcher"
    assert settings.toggle_state("Home Button Takeover") is not None, "Home Button Takeover should be a switch"


def test_save_backup_writes_a_file(tv, home, settings):
    existed = "No such file" not in tv.sh(f"ls {BACKUP} 2>&1")
    before = tv.sh(f"stat -c %Y {BACKUP} 2>/dev/null").strip()
    try:
        settings.open_from_control_center()
        settings.open_page("Backup & Restore")
        settings.focus_text("Save Backup")
        settings.select()
        tv.wait_until(lambda: tv.sh(f"stat -c %Y {BACKUP} 2>/dev/null").strip() not in ("", before), 8, "the backup file")
        assert '"dock"' in tv.sh(f"head -c 4000 {BACKUP}") or "{" in tv.sh(f"head -c 10 {BACKUP}"), "the backup isn't JSON"
    finally:
        if not existed:
            tv.sh(f"rm -f {BACKUP}")


def test_about_shows_the_installed_version(tv, home, settings):
    settings.open_from_control_center()
    settings.open_page("About", "Glass Launcher")
    installed = re.search(r"versionName=(\S+)", tv.sh("dumpsys package dev.glasslauncher | grep -m1 versionName")).group(1)
    assert installed in settings.value_of("Version"), f"About should show {installed}"
