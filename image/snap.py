#!/usr/bin/env python3
import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
gi.require_version("Wnck", "3.0")
from gi.repository import Gdk, GLib, Gtk, Wnck

LAYOUTS = [
    [(0, 0, .5, 1), (.5, 0, .5, 1)],
    [(0, 0, 2 / 3, 1), (2 / 3, 0, 1 / 3, 1)],
    [(0, 0, 1 / 3, 1), (1 / 3, 0, 1 / 3, 1), (2 / 3, 0, 1 / 3, 1)],
    [(0, 0, .5, .5), (.5, 0, .5, .5), (0, .5, .5, .5), (.5, .5, .5, .5)],
    [(0, 0, 1, .5), (0, .5, 1, .5)],
]

PALETTES = {
    "dark": dict(bg="#2b2b2b", stroke="#3f3f3f", zone="#454545", text="#ffffff"),
    "light": dict(bg="#f9f9f9", stroke="#d9d9d9", zone="#e3e3e3", text="#1b1b1b"),
}

CSS = """
.flyout {{ background-color: {bg}; border: 1px solid {stroke}; border-radius: 10px; padding: 12px; }}
.hint {{ color: {text}; font-size: 9.5pt; }}
.zone {{ background-image: none; background-color: {zone}; border: none; border-radius: 4px;
         min-width: 0; min-height: 0; padding: 0; box-shadow: none; }}
.zone:hover {{ background-color: #5fd6a6; }}
"""

TILE_W, TILE_H, GAP = 96, 64, 3


class Tile(Gtk.Fixed):
    def __init__(self, zones, on_pick):
        super().__init__()
        self.set_size_request(TILE_W, TILE_H)
        for zone in zones:
            x, y, w, h = zone
            button = Gtk.Button()
            button.get_style_context().add_class("zone")
            button.set_size_request(int(w * TILE_W) - GAP, int(h * TILE_H) - GAP)
            button.connect("clicked", lambda _b, z=zone: on_pick(z))
            self.put(button, int(x * TILE_W), int(y * TILE_H))


class Snap(Gtk.Window):
    def __init__(self, target):
        super().__init__(type_hint=Gdk.WindowTypeHint.DIALOG)
        self.target = target
        self.set_decorated(False)
        self.set_keep_above(True)
        self.set_skip_taskbar_hint(True)
        self.set_skip_pager_hint(True)
        self.set_resizable(False)
        self.connect("key-press-event", lambda _w, e: e.keyval == Gdk.KEY_Escape and self.destroy())
        self.connect("focus-out-event", lambda _w, _e: GLib.timeout_add(150, self.destroy))

        display = Gdk.Display.get_default()
        self.work = (display.get_primary_monitor() or display.get_monitor(0)).get_workarea()

        card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        card.get_style_context().add_class("flyout")
        grid = Gtk.Box(spacing=10)
        for zones in LAYOUTS:
            grid.pack_start(Tile(zones, self.place), False, False, 0)
        card.pack_start(grid, False, False, 0)
        hint = Gtk.Label(label="Куда поставить окно «%s»" % target.get_name(), xalign=0)
        hint.set_ellipsize(3)
        hint.set_max_width_chars(48)
        hint.get_style_context().add_class("hint")
        card.pack_start(hint, False, False, 0)
        self.add(card)
        card.show_all()
        width = card.get_preferred_width()[1]
        self.move(self.work.x + max(0, (self.work.width - width) // 2), self.work.y + 12)

    def place(self, zone):
        x, y, w, h = zone
        work = self.work
        self.target.unmaximize()
        mask = (Wnck.WindowMoveResizeMask.X | Wnck.WindowMoveResizeMask.Y
                | Wnck.WindowMoveResizeMask.WIDTH | Wnck.WindowMoveResizeMask.HEIGHT)
        self.target.set_geometry(Wnck.WindowGravity.STATIC, mask,
                                 int(work.x + x * work.width), int(work.y + y * work.height),
                                 int(w * work.width), int(h * work.height))
        self.target.activate(Gtk.get_current_event_time())
        self.destroy()


def main():
    screen = Wnck.Screen.get_default()
    screen.force_update()
    target = screen.get_active_window()
    if target is None or target.get_window_type() != Wnck.WindowType.NORMAL:
        return
    settings = Gtk.Settings.get_default()
    mode = "light" if "Light" in (settings.get_property("gtk-theme-name") or "") else "dark"
    provider = Gtk.CssProvider()
    provider.load_from_data(CSS.format(**PALETTES[mode]).encode())
    Gtk.StyleContext.add_provider_for_screen(Gdk.Screen.get_default(), provider,
                                             Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
    window = Snap(target)
    window.connect("destroy", Gtk.main_quit)
    window.show_all()
    window.present()
    Gtk.main()


if __name__ == "__main__":
    main()
