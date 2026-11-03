#!/usr/bin/env python3
import json
import os

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
gi.require_version("Wnck", "3.0")
from gi.repository import Gdk, GLib, Gtk, Wnck

STORE = os.path.expanduser("~/.config/kovkod/windows.json")
MASK = (Wnck.WindowMoveResizeMask.X | Wnck.WindowMoveResizeMask.Y
        | Wnck.WindowMoveResizeMask.WIDTH | Wnck.WindowMoveResizeMask.HEIGHT)


def load():
    try:
        with open(STORE, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return {}


def workarea():
    display = Gdk.Display.get_default()
    return (display.get_primary_monitor() or display.get_monitor(0)).get_workarea()


def key_of(window):
    if window.get_window_type() != Wnck.WindowType.NORMAL or window.is_skip_tasklist():
        return None
    group = window.get_class_group_name() or window.get_class_instance_name()
    return group.lower() if group else None


class Memory:
    def __init__(self):
        self.places = load()
        self.save_pending = False
        self.restoring = set()
        screen = Wnck.Screen.get_default()
        screen.force_update()
        for window in screen.get_windows():
            self.watch(window)
        screen.connect("window-opened", lambda _s, w: self.opened(w))

    def watch(self, window):
        window.connect("geometry-changed", self.changed)
        window.connect("state-changed", lambda w, _c, _n: self.changed(w))

    def opened(self, window):
        self.watch(window)
        key = key_of(window)
        place = self.places.get(key) if key else None
        if place:
            self.restoring.add(window.get_xid())
            GLib.timeout_add(150, self.restore, window, place)

    def restore(self, window, place):
        if place.get("max"):
            window.maximize()
        else:
            work = workarea()
            w = min(place["w"], work.width)
            h = min(place["h"], work.height)
            x = max(work.x, min(place["x"], work.x + work.width - w))
            y = max(work.y, min(place["y"], work.y + work.height - h))
            window.set_geometry(Wnck.WindowGravity.STATIC, MASK, x, y, w, h)
        GLib.timeout_add(500, self.restoring.discard, window.get_xid())
        return False

    def changed(self, window):
        key = key_of(window)
        if not key or window.get_xid() in self.restoring or window.is_minimized():
            return
        x, y, w, h = window.get_geometry()
        maximized = window.is_maximized()
        place = dict(self.places.get(key, {}), max=maximized)
        if not maximized:
            place.update(x=x, y=y, w=w, h=h)
        if "w" not in place:
            return
        self.places[key] = place
        if not self.save_pending:
            self.save_pending = True
            GLib.timeout_add(1000, self.save)

    def save(self):
        self.save_pending = False
        os.makedirs(os.path.dirname(STORE), exist_ok=True)
        tmp = STORE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as handle:
            json.dump(self.places, handle, ensure_ascii=False, indent=1)
        os.replace(tmp, STORE)
        return False


def main():
    Memory()
    Gtk.main()


if __name__ == "__main__":
    main()
