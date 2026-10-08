"""Page objects: what a person sees and does on each Glass surface, in remote presses."""

from __future__ import annotations

import re
import time
from typing import Callable

from tv import PKG, Node, Tree, TV

CONTROL_CENTER = f"{PKG}.action.CONTROL_CENTER"
APP_SWITCHER = f"{PKG}.action.APP_SWITCHER"
TV_SETTINGS = f"{PKG}.action.TV_SETTINGS"


class Screen:
    def __init__(self, tv: TV):
        self.tv = tv

    def focused(self) -> Node | None:
        return self.tv.tree().focused()

    def focus(self, match: Callable[[Node], bool], what: str, max_steps: int = 30) -> Node:
        """Moves focus to the first focusable node `match` accepts, steering by on-screen position
        (Glass's focus search is spatial), one press at a time, checking where focus landed."""
        stuck = 0
        scrolled = 0
        last = None
        for _ in range(max_steps + 25):
            tree = self.tv.tree()
            here = tree.focused()
            if here and match(here):
                return here
            target = next((n for n in tree.nodes() if n.focusable and match(n)), None)
            if target is None:
                # Further down a scrolling list (rows off screen aren't drawn): bring it into view.
                if scrolled < 25 and here is not None:
                    before = here
                    self.tv.press("down")
                    scrolled += 1
                    now = self.tv.tree().focused()
                    if now and now.label == before.label and now.bounds == before.bounds:
                        raise AssertionError(f"{what} isn't on screen (reached the end); focus is on {here}")
                    continue
                raise AssertionError(f"{what} isn't on screen; focus is on {here}")
            if here is None:
                self.tv.press("down")
                continue
            (hx, hy), (tx, ty) = here.center, target.center
            dx, dy = tx - hx, ty - hy
            # Rows first when the target is clearly on another row, else along the row.
            key = ("down" if dy > 0 else "up") if abs(dy) > 40 and (abs(dy) >= abs(dx) * 0.5 or stuck) else ("right" if dx > 0 else "left")
            self.tv.press(key)
            now = self.tv.tree().focused()
            stuck = stuck + 1 if (now and here and now.label == here.label and now.bounds == here.bounds) else 0
            if stuck >= 3:
                raise AssertionError(f"focus stuck on {here} while moving to {what}")
            last = now
        raise AssertionError(f"couldn't reach {what}; focus is on {last}")

    def focus_desc(self, desc: str) -> Node:
        return self.focus(lambda n: n.desc == desc, f"'{desc}'")

    def focus_desc_prefix(self, prefix: str) -> Node:
        return self.focus(lambda n: n.desc.startswith(prefix), f"'{prefix}…'")

    def focus_text(self, text: str) -> Node:
        return self.focus(lambda n: text in n.texts or n.desc == text, f"row '{text}'")

    def select(self) -> None:
        self.tv.press("select")

    def back(self) -> None:
        self.tv.press("back")


class Home(Screen):
    def reset(self) -> None:
        """Home, scrolled to the top, nothing open, focus on the first top-row app."""
        if self.tv.resumed_package() != PKG:
            # Coming back from an app keeps focus where it was (by design); the second Home below,
            # pressed on Home, is the one that goes to the top.
            self.tv.home_intent()
            self.tv.wait_until(lambda: self.tv.resumed_package() == PKG, 10, "Glass in front")
            self.tv.wait_for(lambda t: t.focused() is not None, 6, "Home to take focus")
        # A test that failed in move mode leaves tiles wiggling, and the tree can't be read then: Back
        # ends move mode (harmless otherwise).
        try:
            self.tv.tree()
        except RuntimeError:
            self.tv.press("back")
        self.tv.home_intent()  # Home on Home: closes overlays, scrolls to the top
        if not self.in_dock(self.tv.tree(), self.tv.tree().focused()):
            self.tv.home_intent()
        self.tv.wait_for(lambda t: not t.overlays() and self.in_dock(t, t.focused()), 10,
                         "Home at the top with focus in the top row")

    @staticmethod
    def in_dock(tree: Tree, node: Node | None) -> bool:
        dock = tree.find(rid="dock")
        if not dock or not node:
            return False
        l, t, r, b = dock.bounds
        x, y = node.center
        return l <= x <= r and t <= y <= b

    def dock_apps(self) -> list[Node]:
        tree = self.tv.tree()
        return [n for n in tree.all("app:") if self.in_dock(tree, n)]

    def grid_items(self) -> list[Node]:
        tree = self.tv.tree()
        return [n for n in tree.nodes() if (n.rid.startswith("app:") or n.rid.startswith("folder:")) and not self.in_dock(tree, n)]

    def focus_app(self, pkg: str) -> Node:
        return self.focus(lambda n: n.rid == f"app:{pkg}", f"app {pkg}")

    def focus_folder(self) -> Node:
        return self.focus(lambda n: n.rid.startswith("folder:"), "a folder")

    def open_app(self, pkg: str) -> None:
        self.focus_app(pkg)
        self.select()
        self.tv.wait_until(lambda: self.tv.resumed_package() == pkg, 15, f"{pkg} in front")


class ControlCenter(Screen):
    TILES = ["Settings, Fire TV", "Launcher Settings", "Game Controllers", "Screen Saver", "App Switcher"]

    def open(self) -> Tree:
        self.tv.intent(CONTROL_CENTER)
        return self.tv.wait_for(lambda t: t.top_overlay() == "ControlCenter" and t.find(rid="control-center")
                                and (t.focused() or None) and t, 8, "Control Center open")

    def tile(self, tree: Tree, prefix: str) -> Node | None:
        return tree.find(desc_prefix=prefix)

    def appearance(self, tree: Tree | None = None) -> str:
        n = (tree or self.tv.tree()).find(desc_prefix="Appearance, ")
        assert n, "Appearance button missing"
        return n.desc.split(", ", 1)[1]

    def press_tile(self, prefix: str) -> None:
        self.focus_desc_prefix(prefix)
        self.select()


class Settings(Screen):
    def open_from_control_center(self) -> None:
        cc = ControlCenter(self.tv)
        cc.open()
        cc.press_tile("Launcher Settings")
        self.tv.wait_for(lambda t: t.top_overlay() == "Settings" and t.has_text("Appearance"), 8, "Launcher Settings open")

    def open_page(self, row: str, title: str | None = None) -> None:
        self.focus_text(row)
        self.select()
        self.tv.wait_for(lambda t: t.has_text(title or row), 6, f"page '{title or row}'")

    def all_rows(self, max_presses: int = 30) -> set[str]:
        """Every row title on the current page, scrolling down through it (rows off screen aren't
        drawn, so they aren't in the tree until focus brings them in)."""
        seen: set[str] = set()
        last = None
        for _ in range(max_presses):
            tree = self.tv.tree()
            seen.update(t for n in tree.nodes() if n.focusable for t in n.texts[:1])
            here = tree.focused()
            if here and last and here.label == last:
                break
            last = here.label if here else None
            self.tv.press("down")
        return seen

    def toggle_state(self, row: str) -> bool | None:
        """A switch row's state from the accessibility tree (checkable/checked), None if it isn't a switch."""
        out = self.tv.sh("uiautomator dump /data/local/tmp/e2e.xml >/dev/null 2>&1; cat /data/local/tmp/e2e.xml")
        import re as _re
        for m in _re.finditer(r'<node [^>]*>', out):
            node = m.group(0)
            if f'content-desc="{row}' in node or f'text="{row}"' in node:
                if 'checkable="true"' in node:
                    return 'checked="true"' in node
        return None

    def value_of(self, row: str) -> str:
        """The value shown at the right of a row (e.g. 'Appearance' → 'Dark')."""
        tree = self.tv.tree()
        for n in tree.nodes():
            if n.focusable and row in n.texts:
                rest = [t for t in n.texts if t != row and t not in ("›",)]
                return rest[0] if rest else ""
        raise AssertionError(f"row '{row}' not found")


class AppMenu(Screen):
    ROWS = ["Edit Home Screen", "Move to…", "Change Icon", "Hide", "App Info", "Uninstall"]

    def open_for(self, home: Home, pkg: str) -> Tree:
        home.focus_app(pkg)
        self.tv.press("menu")
        return self.tv.wait_for(lambda t: t.top_overlay() == "AppMenu" and t, 6, "app menu open")


def clock_ok(text: str, seconds: bool) -> bool:
    """h:mm AM/PM (or 24-hour HH:mm), with :ss when [seconds]."""
    sec = r":\d{2}" if seconds else ""
    return bool(re.fullmatch(rf"\d{{1,2}}:\d{{2}}{sec} (AM|PM)", text) or re.fullmatch(rf"\d{{2}}:\d{{2}}{sec}", text))


def date_ok(text: str) -> bool:
    """'Wed, Oct 7'."""
    return bool(re.fullmatch(r"(Mon|Tue|Wed|Thu|Fri|Sat|Sun), (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d{1,2}", text))


def eventually(fn: Callable[[], bool], timeout: float = 8, interval: float = 0.4) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if fn():
            return True
        time.sleep(interval)
    return fn()
