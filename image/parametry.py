#!/usr/bin/env python3
import os
import platform
import re
import shutil
import subprocess

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Gdk, GLib, Gtk, Pango

WALLPAPER = "/usr/share/backgrounds/codoceh/wallpaper-codoceh.png"
VERSION_FILE = "/opt/stol/VERSION"
STORE = "/opt/stol/store/codoceh-store.py"
NARROW = 820

PALETTES = {
    "dark": dict(bg="#202020", hover="#2d2d2d", text="#ffffff", muted="#9a9a9a", card="#2b2b2b",
                 stroke="#333333", control="#373737", control_hover="#404040", control_stroke="#454545"),
    "light": dict(bg="#f3f3f3", hover="#e9e9e9", text="#1b1b1b", muted="#5f5f5f", card="#fbfbfb",
                  stroke="#e5e5e5", control="#fdfdfd", control_hover="#f5f5f5", control_stroke="#d1d1d1"),
}

CSS = """
window, .page {{ background-color: {bg}; }}
.nav {{ background-color: {bg}; padding: 8px; }}
.nav row {{ border-radius: 6px; padding: 8px 10px; margin: 1px 0; background: transparent; }}
.nav row:hover {{ background-color: {hover}; }}
.nav row:selected {{ background-color: {hover}; }}
.nav row:selected .pill {{ background-color: #5fd6a6; }}
.pill {{ min-width: 3px; border-radius: 2px; margin: 6px 8px 6px 0; background-color: transparent; }}
.nav-label {{ color: {text}; font-size: 11pt; }}
.user {{ padding: 12px 10px 16px 10px; }}
.user-name {{ font-size: 12pt; font-weight: 600; color: {text}; }}
.muted {{ color: {muted}; font-size: 9.5pt; }}
.title {{ font-size: 22pt; font-weight: 600; color: {text}; }}
.group {{ font-size: 10.5pt; font-weight: 600; color: {text}; margin-top: 14px; }}
.card {{ background-color: {card}; border-radius: 8px; padding: 14px 16px; border: 1px solid {stroke}; }}
.card-title {{ font-size: 11pt; color: {text}; }}
.accent {{ background-image: none; background-color: #5fd6a6; color: #0e1a14; border-radius: 6px; border: none; padding: 6px 16px; }}
.accent:hover {{ background-color: #74e0b5; }}
.plain {{ background-image: none; background-color: {control}; color: {text}; border-radius: 6px; border: 1px solid {control_stroke}; padding: 6px 16px; }}
.plain:hover {{ background-color: {control_hover}; }}
.back {{ background: transparent; border: none; color: {text}; font-size: 14pt; padding: 0 8px; }}
combobox button {{ border-radius: 6px; background-image: none; background-color: {control}; color: {text}; border: 1px solid {control_stroke}; }}
progressbar trough {{ min-height: 6px; border-radius: 3px; background-color: {control_stroke}; }}
progressbar progress {{ min-height: 6px; border-radius: 3px; background-color: #5fd6a6; }}
"""


def run(*argv):
    try:
        return subprocess.run(argv, capture_output=True, text=True, timeout=15).stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        return ""


def xfconf_get(channel, prop):
    return run("xfconf-query", "-c", channel, "-p", prop)


def xfconf_set(channel, prop, kind, value):
    subprocess.run(["xfconf-query", "-c", channel, "-p", prop, "-n", "-t", kind, "-s", str(value)],
                   check=False)


def label(text, css=None, wrap=False, xalign=0.0):
    widget = Gtk.Label(label=text, xalign=xalign)
    if wrap:
        widget.set_line_wrap(True)
        widget.set_line_wrap_mode(Pango.WrapMode.WORD_CHAR)
        widget.set_width_chars(10)
        widget.set_max_width_chars(48)
    if css:
        widget.get_style_context().add_class(css)
    return widget


def card_row(title, hint, control, icon=None):
    card = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=14)
    card.get_style_context().add_class("card")
    if icon:
        card.pack_start(Gtk.Image.new_from_icon_name(icon, Gtk.IconSize.LARGE_TOOLBAR), False, False, 0)
    texts = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
    texts.set_valign(Gtk.Align.CENTER)
    texts.pack_start(label(title, "card-title", wrap=True), False, False, 0)
    if hint:
        texts.pack_start(label(hint, "muted", wrap=True), False, False, 0)
    card.pack_start(texts, True, True, 0)
    if control is not None:
        control.set_valign(Gtk.Align.CENTER)
        card.pack_end(control, False, False, 0)
    return card


def combo(options, current, on_pick):
    box = Gtk.ComboBoxText()
    for value, text in options:
        box.append(str(value), text)
    if current is not None:
        box.set_active_id(str(current))
    box.connect("changed", lambda widget: widget.get_active_id() is not None and on_pick(widget.get_active_id()))
    return box


def switch(active, on_toggle):
    widget = Gtk.Switch()
    widget.set_active(active)
    widget.connect("notify::active", lambda w, _p: on_toggle(w.get_active()))
    return widget


def button(text, accent, on_click):
    widget = Gtk.Button(label=text)
    widget.get_style_context().add_class("accent" if accent else "plain")
    widget.connect("clicked", lambda _w: on_click())
    return widget


def launch(*argv):
    try:
        subprocess.Popen(argv, start_new_session=True)
    except OSError:
        pass


def page_system():
    items = []
    modes, current = [], None
    for line in run("xrandr").splitlines():
        match = re.match(r"\s+(\d+x\d+)\s", line)
        if match:
            modes.append((match.group(1), match.group(1).replace("x", " × ")))
            if "*" in line:
                current = match.group(1)
    items.append(("Экран", None))
    items.append(card_row("Разрешение экрана", "Меняется сразу. После перезапуска стола вернётся размер из настроек приложения КовКод.",
                          combo(modes, current, lambda mode: run("xrandr", "-s", mode)), "video-display"))
    dpi = xfconf_get("xsettings", "/Xft/DPI") or "96"
    items.append(card_row("Масштаб текста", "Размер шрифтов во всех программах.",
                          combo([(96, "100 %"), (120, "125 %"), (144, "150 %"), (192, "200 %")], dpi,
                                lambda v: xfconf_set("xsettings", "/Xft/DPI", "int", v)), "preferences-desktop-font"))

    items.append(("Память", None))
    usage = shutil.disk_usage("/")
    bar = Gtk.ProgressBar()
    bar.set_fraction(usage.used / usage.total if usage.total else 0)
    bar.set_size_request(160, -1)
    items.append(card_row("Хранилище",
                          "Занято %.1f ГБ из %.1f ГБ" % (usage.used / 2 ** 30, usage.total / 2 ** 30),
                          bar, "drive-harddisk"))
    return "Система", items


def page_personalization():
    items = []
    theme = xfconf_get("xsettings", "/Net/ThemeName") or "Fluent-Dark"

    def set_mode(mode):
        dark = mode == "dark"
        xfconf_set("xsettings", "/Net/ThemeName", "string", "Fluent-Dark" if dark else "Fluent-Light")
        xfconf_set("xsettings", "/Net/IconThemeName", "string", "Fluent-dark" if dark else "Fluent")
        xfconf_set("xfwm4", "/general/theme", "string", "Fluent-Dark" if dark else "Fluent-Light")

    items.append(("Цвета", None))
    items.append(card_row("Режим", "Оформление окон, меню и панели.",
                          combo([("dark", "Тёмный"), ("light", "Светлый")],
                                "light" if "Light" in theme else "dark", set_mode),
                          "preferences-desktop-theme"))

    items.append(("Фон", None))

    def pick_wallpaper():
        dialog = Gtk.FileChooserDialog(title="Выберите картинку", action=Gtk.FileChooserAction.OPEN)
        dialog.add_buttons("Отмена", Gtk.ResponseType.CANCEL, "Выбрать", Gtk.ResponseType.OK)
        images = Gtk.FileFilter()
        images.set_name("Картинки")
        for pattern in ("*.png", "*.jpg", "*.jpeg", "*.webp", "*.bmp"):
            images.add_pattern(pattern)
        dialog.add_filter(images)
        if dialog.run() == Gtk.ResponseType.OK:
            set_wallpaper(dialog.get_filename())
        dialog.destroy()

    def set_wallpaper(path):
        for prop in run("xfconf-query", "-c", "xfce4-desktop", "-l").splitlines():
            if prop.endswith("/last-image"):
                xfconf_set("xfce4-desktop", prop, "string", path)

    box = Gtk.Box(spacing=8)
    box.pack_start(button("Обзор…", True, pick_wallpaper), False, False, 0)
    box.pack_start(button("Фон КодоЦеха", False, lambda: set_wallpaper(WALLPAPER)), False, False, 0)
    items.append(card_row("Фон рабочего стола", "Картинка из ваших файлов.", box, "preferences-desktop-wallpaper"))

    items.append(("Панель задач", None))
    size = xfconf_get("xfce4-panel", "/panels/panel-1/size") or "48"
    items.append(card_row("Высота панели", None,
                          combo([(40, "Компактная"), (48, "Обычная"), (56, "Крупная")], size,
                                lambda v: xfconf_set("xfce4-panel", "/panels/panel-1/size", "uint", v)),
                          "preferences-desktop"))
    return "Персонализация", items


def page_apps():
    items = [("Программы", None)]
    items.append(card_row("Магазин программ", "Установка и удаление программ.",
                          button("Открыть", True, lambda: launch("python3", STORE)), "system-software-install"))
    items.append(card_row("Программы по умолчанию", "Чем открывать ссылки, документы и картинки.",
                          button("Настроить", False, lambda: launch("xfce4-mime-settings")),
                          "preferences-desktop-default-applications"))
    items.append(card_row("Автозапуск", "Программы, которые открываются вместе со столом.",
                          button("Настроить", False, lambda: launch("xfce4-session-settings")),
                          "preferences-system-session"))
    return "Приложения", items


ZONES = [
    ("Europe/Kaliningrad", "Калининград (UTC+2)"), ("Europe/Moscow", "Москва (UTC+3)"),
    ("Europe/Samara", "Самара (UTC+4)"), ("Asia/Yekaterinburg", "Екатеринбург (UTC+5)"),
    ("Asia/Omsk", "Омск (UTC+6)"), ("Asia/Novosibirsk", "Новосибирск (UTC+7)"),
    ("Asia/Krasnoyarsk", "Красноярск (UTC+7)"), ("Asia/Irkutsk", "Иркутск (UTC+8)"),
    ("Asia/Yakutsk", "Якутск (UTC+9)"), ("Asia/Vladivostok", "Владивосток (UTC+10)"),
    ("Asia/Magadan", "Магадан (UTC+11)"), ("Asia/Kamchatka", "Камчатка (UTC+12)"),
    ("Europe/Minsk", "Минск (UTC+3)"), ("Asia/Almaty", "Алматы (UTC+5)"),
    ("Asia/Tashkent", "Ташкент (UTC+5)"), ("Asia/Tbilisi", "Тбилиси (UTC+4)"),
    ("Asia/Yerevan", "Ереван (UTC+4)"), ("Europe/Istanbul", "Стамбул (UTC+3)"),
    ("Asia/Dubai", "Дубай (UTC+4)"), ("Europe/Berlin", "Берлин (UTC+1)"),
    ("Europe/London", "Лондон (UTC+0)"), ("Etc/UTC", "UTC"),
]


def current_zone():
    try:
        target = os.path.realpath("/etc/localtime")
    except OSError:
        return "Etc/UTC"
    marker = "/zoneinfo/"
    return target.split(marker, 1)[1] if marker in target else "Etc/UTC"


def set_zone(zone):
    path = "/usr/share/zoneinfo/" + zone
    if not os.path.exists(path):
        return
    try:
        if os.path.lexists("/etc/localtime"):
            os.remove("/etc/localtime")
        os.symlink(path, "/etc/localtime")
        with open("/etc/timezone", "w", encoding="utf-8") as handle:
            handle.write(zone + "\n")
    except OSError:
        return
    for prop in run("xfconf-query", "-c", "xfce4-panel", "-l").splitlines():
        if re.match(r"/plugins/plugin-\d+$", prop) and xfconf_get("xfce4-panel", prop) == "clock":
            xfconf_set("xfce4-panel", prop + "/timezone", "string", zone)


def page_time():
    items = [("Дата и время", None)]
    zone = current_zone()
    options = list(ZONES)
    if zone not in dict(options):
        options.insert(0, (zone, zone))
    items.append(card_row("Часовой пояс", "Часы на панели переходят на него сразу.",
                          combo(options, zone, set_zone), "preferences-system-time"))
    items.append(("Язык", None))
    items.append(card_row("Язык интерфейса", "Русский", None, "preferences-desktop-locale"))
    return "Время и язык", items


def page_accessibility():
    items = [("Зрение", None)]
    cursor = xfconf_get("xsettings", "/Gtk/CursorThemeSize") or "0"
    items.append(card_row("Крупный указатель мыши", None,
                          switch(int(cursor or 0) >= 48,
                                 lambda on: xfconf_set("xsettings", "/Gtk/CursorThemeSize", "int", 48 if on else 24)),
                          "input-mouse"))
    animations = xfconf_get("xsettings", "/Gtk/EnableAnimations") or "true"
    items.append(card_row("Эффекты анимации", "Плавное появление меню и окон.",
                          switch(animations == "true",
                                 lambda on: xfconf_set("xsettings", "/Gtk/EnableAnimations", "bool",
                                                       "true" if on else "false")),
                          "preferences-desktop-accessibility"))
    return "Специальные возможности", items


def page_about():
    items = [("Характеристики", None)]
    version = "—"
    try:
        with open(VERSION_FILE, encoding="utf-8") as handle:
            version = handle.read().strip() or "—"
    except OSError:
        pass
    memory = 0
    try:
        with open("/proc/meminfo", encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("MemTotal:"):
                    memory = int(line.split()[1]) / 2 ** 20
    except OSError:
        pass
    rows = [
        ("Система", "КовКод · Debian 13 · %s" % platform.machine()),
        ("Версия стола", version),
        ("Процессор", "%d ядер" % (os.cpu_count() or 0)),
        ("Память", "%.1f ГБ" % memory if memory else "—"),
    ]
    for title, value in rows:
        items.append(card_row(title, None, label(value, "muted", xalign=1.0)))
    items.append(("КодоЦех", None))
    items.append(card_row("Выковано в КодоЦех", "codoceh.ru · Telegram @CodoCeh", None, "help-about"))
    return "О системе", items


SECTIONS = [
    ("system", "Система", "computer", page_system),
    ("personal", "Персонализация", "preferences-desktop-theme", page_personalization),
    ("apps", "Приложения", "system-software-install", page_apps),
    ("time", "Время и язык", "preferences-system-time", page_time),
    ("access", "Специальные возможности", "preferences-desktop-accessibility", page_accessibility),
    ("about", "О системе", "help-about", page_about),
]


class Parametry(Gtk.Window):
    def __init__(self):
        super().__init__(title="Параметры")
        area = Gdk.Display.get_default().get_primary_monitor() or Gdk.Display.get_default().get_monitor(0)
        work = area.get_workarea()
        self.set_default_size(min(1000, work.width - 24), min(720, work.height - 24))
        self.set_icon_name("preferences-system")
        self.narrow = None

        self.nav = Gtk.ListBox()
        self.nav.get_style_context().add_class("nav")
        user = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        user.get_style_context().add_class("user")
        user.pack_start(label("КовКод", "user-name"), False, False, 0)
        user.pack_start(label("Рабочий стол на Android", "muted"), False, False, 0)
        for key, title, icon, _builder in SECTIONS:
            row = Gtk.ListBoxRow()
            row.key = key
            box = Gtk.Box(spacing=12)
            pill = Gtk.Box()
            pill.get_style_context().add_class("pill")
            box.pack_start(pill, False, False, 0)
            box.pack_start(Gtk.Image.new_from_icon_name(icon, Gtk.IconSize.LARGE_TOOLBAR), False, False, 0)
            box.pack_start(label(title, "nav-label"), False, False, 0)
            row.add(box)
            self.nav.add(row)
        self.nav.connect("row-activated", lambda _l, row: self.show_section(row.key))

        self.sidebar = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.sidebar.get_style_context().add_class("nav")
        self.sidebar.set_size_request(280, -1)
        self.sidebar.pack_start(user, False, False, 0)
        self.sidebar.pack_start(self.nav, True, True, 0)

        self.stack = Gtk.Stack()
        self.stack.set_transition_type(Gtk.StackTransitionType.CROSSFADE)
        for key, title, _icon, builder in SECTIONS:
            self.stack.add_named(self.build_page(builder), key)

        self.back = Gtk.Button(label="‹")
        self.back.get_style_context().add_class("back")
        self.back.connect("clicked", lambda _w: self.show_home())

        root = Gtk.Box()
        root.pack_start(self.sidebar, False, True, 0)
        right = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        right.pack_start(self.stack, True, True, 0)
        root.pack_start(right, True, True, 0)
        outer = Gtk.ScrolledWindow()
        outer.set_policy(Gtk.PolicyType.EXTERNAL, Gtk.PolicyType.NEVER)
        outer.add(root)
        self.add(outer)
        self.connect("size-allocate", self.on_size)
        self.nav.select_row(self.nav.get_row_at_index(0))
        self.show_section("system")

    def build_page(self, builder):
        title, items = builder()
        column = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        column.get_style_context().add_class("page")
        column.set_margin_start(24)
        column.set_margin_end(24)
        column.set_margin_top(16)
        column.set_margin_bottom(24)
        head = Gtk.Box(spacing=4)
        head.back_slot = True
        head.pack_start(label(title, "title"), False, False, 0)
        column.pack_start(head, False, False, 8)
        for item in items:
            if isinstance(item, tuple):
                column.pack_start(label(item[0], "group"), False, False, 4)
            else:
                column.pack_start(item, False, False, 0)
        scroll = Gtk.ScrolledWindow()
        scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        scroll.add(column)
        scroll.head = head
        return scroll

    def on_size(self, _widget, allocation):
        narrow = allocation.width < NARROW
        if narrow == self.narrow:
            return
        self.narrow = narrow
        GLib.idle_add(self.apply_layout)

    def apply_layout(self):
        if self.narrow:
            self.sidebar.set_size_request(-1, -1)
            self.show_home()
        else:
            self.sidebar.set_size_request(280, -1)
            self.sidebar.set_hexpand(False)
            self.sidebar.show()
            self.stack.show()
            self.detach_back()
        return False

    def attach_back(self):
        head = self.stack.get_visible_child().head
        self.detach_back()
        head.pack_start(self.back, False, False, 0)
        head.reorder_child(self.back, 0)
        self.back.show()

    def detach_back(self):
        parent = self.back.get_parent()
        if parent:
            parent.remove(self.back)

    def show_section(self, key):
        self.stack.set_visible_child_name(key)
        if self.narrow:
            self.sidebar.hide()
            self.sidebar.set_hexpand(False)
            self.stack.show()
            self.attach_back()

    def show_home(self):
        self.detach_back()
        self.stack.hide()
        self.sidebar.show()
        self.sidebar.set_hexpand(True)


def apply_palette(settings, provider):
    mode = "light" if "Light" in (settings.get_property("gtk-theme-name") or "") else "dark"
    provider.load_from_data(CSS.format(**PALETTES[mode]).encode())


def main():
    GLib.set_prgname("kovkod-parametry")
    settings = Gtk.Settings.get_default()
    provider = Gtk.CssProvider()
    apply_palette(settings, provider)
    settings.connect("notify::gtk-theme-name", lambda s, _p: apply_palette(s, provider))
    Gtk.StyleContext.add_provider_for_screen(Gdk.Screen.get_default(), provider,
                                             Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
    window = Parametry()
    window.connect("destroy", Gtk.main_quit)
    window.show_all()
    window.apply_layout()
    Gtk.main()


if __name__ == "__main__":
    main()
