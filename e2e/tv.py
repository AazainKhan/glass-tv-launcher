"""Driver for the real Fire TV stick: remote presses, the accessibility tree, and system state.

Keys go through scripts/key, which sends real remote events (the Bluetooth remote's input node, or a
temporary virtual remote when it sleeps). UiAutomator/Espresso-style injected keys can't be used: an
injected Select doesn't click Glass's tiles on Fire OS. State is read from `uiautomator dump`, where
Glass's Compose test tags appear as resource-ids (testTagsAsResourceId) and labels as content-desc.
"""

from __future__ import annotations

import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterator, TypeVar

PKG = "dev.glasslauncher"
ROOT = Path(__file__).resolve().parent.parent
T = TypeVar("T")


@dataclass
class Node:
    rid: str
    desc: str
    text: str
    focused: bool
    focusable: bool
    bounds: tuple[int, int, int, int]
    children: list["Node"] = field(default_factory=list)

    def walk(self) -> Iterator["Node"]:
        yield self
        for c in self.children:
            yield from c.walk()

    @property
    def texts(self) -> list[str]:
        """All visible text in this node and below, in order."""
        return [n.text for n in self.walk() if n.text]

    @property
    def label(self) -> str:
        return self.desc or " ".join(self.texts)

    @property
    def center(self) -> tuple[int, int]:
        l, t, r, b = self.bounds
        return (l + r) // 2, (t + b) // 2

    def __repr__(self) -> str:
        return f"Node(rid={self.rid!r}, label={self.label!r}, focused={self.focused})"


class Tree:
    def __init__(self, root: Node):
        self.root = root

    def nodes(self) -> Iterator[Node]:
        return self.root.walk()

    def find(self, rid: str | None = None, desc: str | None = None, text: str | None = None,
             rid_prefix: str | None = None, desc_prefix: str | None = None) -> Node | None:
        for n in self.nodes():
            if rid is not None and n.rid != rid:
                continue
            if rid_prefix is not None and not n.rid.startswith(rid_prefix):
                continue
            if desc is not None and n.desc != desc:
                continue
            if desc_prefix is not None and not n.desc.startswith(desc_prefix):
                continue
            if text is not None and n.text != text:
                continue
            return n
        return None

    def all(self, rid_prefix: str) -> list[Node]:
        return [n for n in self.nodes() if n.rid.startswith(rid_prefix)]

    def focused(self) -> Node | None:
        hits = [n for n in self.nodes() if n.focused]
        return hits[-1] if hits else None

    def has_text(self, text: str) -> bool:
        return any(n.text == text for n in self.nodes())

    def top_overlay(self) -> str | None:
        n = self.find(rid_prefix="overlay-top:")
        return n.rid.split(":", 1)[1] if n else None

    def overlays(self) -> list[str]:
        return [n.rid.split(":", 1)[1] for n in self.nodes() if re.match(r"overlay(-top)?:", n.rid)]


def _parse(el: ET.Element) -> Node:
    b = [int(x) for x in re.findall(r"\d+", el.get("bounds", "[0,0][0,0]"))] or [0, 0, 0, 0]
    return Node(
        rid=el.get("resource-id", ""),
        desc=el.get("content-desc", ""),
        text=el.get("text", ""),
        focused=el.get("focused") == "true",
        focusable=el.get("focusable") == "true",
        bounds=tuple(b[:4]),  # type: ignore[arg-type]
        children=[_parse(c) for c in el.findall("node")],
    )


class TV:
    def __init__(self, serial: str, owner: str):
        self.serial = serial
        self.env = dict(os.environ, ANDROID_SERIAL=serial, TV_SERIAL=serial, TV_OWNER=owner)

    # ── shell ────────────────────────────────────────────────────────────────────────────────────
    def adb(self, *args: str, timeout: float = 60, check: bool = False) -> str:
        r = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout)
        if check and r.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr or r.stdout}")
        return (r.stdout or "").replace("\r", "")

    def sh(self, cmd: str, timeout: float = 60) -> str:
        return self.adb("shell", cmd, timeout=timeout)

    def su(self, cmd: str, timeout: float = 60) -> str:
        return self.adb("shell", "su", "-c", cmd, timeout=timeout)

    def script(self, name: str, *args: str, timeout: float = 600) -> str:
        r = subprocess.run([str(ROOT / "scripts" / name), *args], capture_output=True, text=True,
                           env=self.env, timeout=timeout, cwd=ROOT)
        if r.returncode != 0:
            raise RuntimeError(f"scripts/{name} {' '.join(args)} failed ({r.returncode}): {r.stderr or r.stdout}")
        return r.stdout

    # ── input ────────────────────────────────────────────────────────────────────────────────────
    def press(self, *keys: str) -> None:
        """Remote presses (see scripts/key: up down left right select back home menu wait:<ms> ...)."""
        self.script("key", *keys, timeout=120)

    # ── state ────────────────────────────────────────────────────────────────────────────────────
    def tree(self) -> Tree:
        """The accessibility tree. uiautomator refuses while a window never goes idle; retry briefly."""
        last = ""
        for _ in range(4):
            out = self.sh("uiautomator dump /data/local/tmp/e2e.xml >/dev/null 2>&1; cat /data/local/tmp/e2e.xml")
            start = out.find("<?xml")
            if start >= 0 and "</hierarchy>" in out:
                root = ET.fromstring(out[start:out.rindex("</hierarchy>") + len("</hierarchy>")])
                return Tree(Node("", "", "", False, False, (0, 0, 0, 0), [_parse(n) for n in root.findall("node")]))
            last = out[-200:]
            time.sleep(0.5)
        raise RuntimeError(f"uiautomator dump failed: {last}")

    def wait_for(self, fn: Callable[[Tree], T], timeout: float = 8, what: str = "condition") -> T:
        """Polls the tree until fn returns something truthy; returns it. No fixed sleeps in tests."""
        deadline = time.time() + timeout
        last_err: Exception | None = None
        while True:
            try:
                result = fn(self.tree())
                if result:
                    return result
            except Exception as e:  # a half-drawn tree; try again
                last_err = e
            if time.time() > deadline:
                raise AssertionError(f"timed out after {timeout}s waiting for {what}" + (f" ({last_err})" if last_err else ""))
            time.sleep(0.25)

    def wait_until(self, fn: Callable[[], T], timeout: float = 8, what: str = "condition") -> T:
        deadline = time.time() + timeout
        while True:
            result = fn()
            if result:
                return result
            if time.time() > deadline:
                raise AssertionError(f"timed out after {timeout}s waiting for {what}")
            time.sleep(0.3)

    def resumed(self) -> str:
        """The resumed activity, e.g. 'dev.glasslauncher/.MainActivity'."""
        out = self.sh("dumpsys activity activities | grep -m1 mResumedActivity")
        m = re.search(r"u0 (\S+/\S+)", out)
        return m.group(1) if m else ""

    def resumed_package(self) -> str:
        return self.resumed().split("/")[0]

    def launch(self, pkg: str) -> None:
        """Opens an app the way its launcher entry does (TV apps often have only a Leanback one)."""
        for cat in ("LEANBACK_LAUNCHER", "LAUNCHER"):
            comp = self.sh(f"cmd package resolve-activity --brief -c android.intent.category.{cat} {pkg}").strip().splitlines()[-1:]
            if comp and "/" in comp[0]:
                self.sh(f"am start -n {comp[0].strip()}")
                return
        raise AssertionError(f"{pkg} has no launcher entry")

    def home_intent(self) -> None:
        self.sh(f"am start -a android.intent.action.MAIN -c android.intent.category.HOME -n {PKG}/.MainActivity")

    def intent(self, action: str) -> None:
        self.sh(f"am start -n {PKG}/.MainActivity -a {action}")

    def setting(self, namespace: str, key: str) -> str:
        return self.sh(f"settings get {namespace} {key}").strip()

    def screenshot(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "wb") as f:
            subprocess.run(["adb", "-s", self.serial, "exec-out", "screencap", "-p"], stdout=f, timeout=30)

    def screen_image(self):
        """The screen as a greyscale PIL image."""
        import io
        from PIL import Image
        png = subprocess.run(["adb", "-s", self.serial, "exec-out", "screencap", "-p"], capture_output=True, timeout=30).stdout
        return Image.open(io.BytesIO(png)).convert("L")

    @staticmethod
    def brightness(img, bounds) -> float:
        """Mean brightness (0..1) of a node's bounds in a screen image."""
        l, t, r, b = bounds
        px = list(img.crop((l, t, r, b)).getdata())
        return sum(px) / max(1, len(px)) / 255

    def region_brightness(self, left: float, top: float, right: float, bottom: float) -> float:
        """Mean brightness (0..1) of a region of the screen (fractions). For states the accessibility
        tree can't report: uiautomator won't dump while something animates (move mode's wiggle)."""
        import io
        from PIL import Image
        png = subprocess.run(["adb", "-s", self.serial, "exec-out", "screencap", "-p"], capture_output=True, timeout=30).stdout
        img = Image.open(io.BytesIO(png)).convert("L")
        w, h = img.size
        crop = img.crop((int(left * w), int(top * h), int(right * w), int(bottom * h)))
        px = list(crop.getdata())
        return sum(px) / len(px) / 255

    def frames_reset(self) -> None:
        self.sh(f"dumpsys gfxinfo {PKG} reset")

    def frames(self) -> dict:
        """Glass's frame stats since frames_reset(): {'frames', 'janky_pct', 'p90', 'p99'}. Close any screen
        recorder first (tv-live), or the numbers are meaningless."""
        out = self.sh(f"dumpsys gfxinfo {PKG}")
        num = lambda pat: float((re.search(pat, out) or [0, 0])[1])
        return {
            "frames": int(num(r"Total frames rendered: (\d+)")),
            "janky_pct": num(r"Janky frames: \d+ \(([\d.]+)%\)"),
            "p90": num(r"90th percentile: (\d+)ms"),
            "p99": num(r"99th percentile: (\d+)ms"),
        }

    def glass_pid(self) -> str:
        return self.sh(f"pidof {PKG}").strip()

    def crashes_since(self, marker: str) -> list[str]:
        """Crash and ANR lines for Glass logged after `marker` (a logcat time, 'MM-DD HH:MM:SS.mmm')."""
        out = self.adb("logcat", "-b", "crash", "-b", "events", "-d", "-T", marker, timeout=30)
        bad = []
        for line in out.splitlines():
            if PKG in line and ("FATAL" in line or "am_crash" in line or "am_anr" in line):
                bad.append(line)
        return bad

    def now_marker(self) -> str:
        return self.sh("date +'%m-%d %H:%M:%S.000'").strip()
