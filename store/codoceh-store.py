#!/usr/bin/env python3
"""
store/codoceh-store.py — современный графический интерфейс Магазина приложений (R17).
"""

import os
import queue
import sys
import threading
import time
import tkinter as tk
from tkinter import messagebox, ttk

import backend

LOG_PATH = "/tmp/codoceh-store.log"
PUMP_MS = 100

# Цветовая палитра: современная тёмная тема (Catppuccin Mocha / Clean Slate)
THEME = {
    "bg": "#1e1e2e",
    "surface": "#181825",
    "card_bg": "#252739",
    "card_border": "#3b3e52",
    "card_hover": "#454862",
    "text": "#cdd6f4",
    "subtext": "#9399b2",
    "dim": "#6c7086",
    "accent": "#89b4fa",
    "accent_hover": "#b4befe",
    "btn_install_bg": "#2d5a3f",
    "btn_install_fg": "#a6e3a1",
    "btn_remove_bg": "#582d3b",
    "btn_remove_fg": "#f38ba8",
    "chip_active_bg": "#89b4fa",
    "chip_active_fg": "#11111b",
    "chip_inactive_bg": "#313244",
    "chip_inactive_fg": "#cdd6f4",
    "badge_bg": "#313244",
    "badge_fg": "#b4befe",
    "status_bar": "#11111b",
}


class StoreApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Магазин КодоЦех")
        self.root.geometry("740x580")
        self.root.minsize(560, 420)
        self.root.configure(bg=THEME["bg"])

        self.catalog = backend.load_catalog()
        self.categories = backend.categories_of(self.catalog)
        self.selected_category = tk.StringVar(value="Все")
        self.search_query = tk.StringVar(value="")
        self.busy = False
        self.action_buttons = []
        self.category_buttons = []
        self.events = queue.SimpleQueue()

        self.setup_styles()
        self.setup_ui()
        self.refresh_list()
        self.root.after(PUMP_MS, self.pump_events)

    def setup_styles(self):
        self.style = ttk.Style(self.root)
        try:
            self.style.theme_use("clam")
        except Exception:
            pass

        # Основные цвета ttk
        self.style.configure(".", background=THEME["bg"], foreground=THEME["text"], font=("sans-serif", 10))
        self.style.configure("TFrame", background=THEME["bg"])
        self.style.configure("Surface.TFrame", background=THEME["surface"])
        self.style.configure("Card.TFrame", background=THEME["card_bg"], relief="flat")
        
        # Заголовки и текст
        self.style.configure("HeaderTitle.TLabel", background=THEME["surface"], foreground=THEME["text"], font=("sans-serif", 14, "bold"))
        self.style.configure("HeaderSub.TLabel", background=THEME["surface"], foreground=THEME["subtext"], font=("sans-serif", 9))
        self.style.configure("ItemTitle.TLabel", background=THEME["card_bg"], foreground=THEME["text"], font=("sans-serif", 11, "bold"))
        self.style.configure("ItemDesc.TLabel", background=THEME["card_bg"], foreground=THEME["subtext"], font=("sans-serif", 9))
        self.style.configure("Badge.TLabel", background=THEME["badge_bg"], foreground=THEME["badge_fg"], font=("sans-serif", 8, "bold"), padding=(6, 2))
        self.style.configure("Installed.TLabel", background=THEME["card_bg"], foreground="#a6e3a1", font=("sans-serif", 9, "bold"))

        # Кнопки
        self.style.configure("TButton", font=("sans-serif", 10, "bold"), padding=(12, 6))
        self.style.configure("Install.TButton", background=THEME["accent"], foreground=THEME["chip_active_fg"], borderwidth=0)
        self.style.map("Install.TButton",
                       background=[("active", THEME["accent_hover"]), ("disabled", THEME["dim"])],
                       foreground=[("disabled", "#45475a")])

        self.style.configure("Remove.TButton", background=THEME["btn_remove_bg"], foreground=THEME["btn_remove_fg"], borderwidth=0)
        self.style.map("Remove.TButton",
                       background=[("active", "#753b4e"), ("disabled", THEME["dim"])],
                       foreground=[("disabled", "#45475a")])

        # Поле поиска
        self.style.configure("Search.TEntry", fieldbackground=THEME["card_bg"], foreground=THEME["text"], insertcolor=THEME["text"], padding=6)

        # Статусная панель и прогресс-бар
        self.style.configure("Status.TLabel", background=THEME["status_bar"], foreground=THEME["subtext"], font=("sans-serif", 9), padding=(10, 6))
        self.style.configure("Modern.Horizontal.TProgressbar", background=THEME["accent"], troughcolor=THEME["surface"], bordercolor=THEME["surface"], lightcolor=THEME["accent"], darkcolor=THEME["accent"])

    def setup_ui(self):
        # 1. Шапка (Header & Search)
        header_frame = ttk.Frame(self.root, style="Surface.TFrame", padding="14 12 14 10")
        header_frame.pack(fill=tk.X)

        title_box = ttk.Frame(header_frame, style="Surface.TFrame")
        title_box.pack(side=tk.LEFT, fill=tk.Y)

        title_lbl = ttk.Label(title_box, text="⚡ Магазин КодоЦех", style="HeaderTitle.TLabel")
        title_lbl.pack(anchor="w")
        sub_lbl = ttk.Label(title_box, text="Установка нативных приложений, инструментов разработки и игр", style="HeaderSub.TLabel")
        sub_lbl.pack(anchor="w", pady=(2, 0))

        # Поиск справа
        search_box = ttk.Frame(header_frame, style="Surface.TFrame")
        search_box.pack(side=tk.RIGHT, fill=tk.Y, pady=2)

        search_icon = ttk.Label(search_box, text="🔍", style="HeaderSub.TLabel")
        search_icon.pack(side=tk.LEFT, padx=(0, 4))

        search_entry = ttk.Entry(
            search_box,
            textvariable=self.search_query,
            style="Search.TEntry",
            width=22,
        )
        search_entry.pack(side=tk.LEFT)
        search_entry.bind("<KeyRelease>", lambda e: self.refresh_list())

        # 2. Фильтр категорий (Category Chips)
        chips_frame = ttk.Frame(self.root, style="TFrame", padding="12 8 12 6")
        chips_frame.pack(fill=tk.X)

        for cat in self.categories:
            btn = tk.Button(
                chips_frame,
                text=cat,
                command=lambda c=cat: self.select_category(c),
                font=("sans-serif", 9, "bold"),
                relief=tk.FLAT,
                padx=12,
                pady=4,
                cursor="hand2",
                bd=0,
                activebackground=THEME["accent_hover"],
            )
            btn.pack(side=tk.LEFT, padx=3)
            self.category_buttons.append((cat, btn))

        self.update_category_buttons()

        # 3. Список программ (Scrollable Cards)
        list_container = ttk.Frame(self.root, style="TFrame", padding="12 2 12 6")
        list_container.pack(fill=tk.BOTH, expand=True)

        self.canvas = tk.Canvas(list_container, bg=THEME["bg"], borderwidth=0, highlightthickness=0)
        self.scrollbar = ttk.Scrollbar(list_container, orient="vertical", command=self.canvas.yview)
        self.scrollable_frame = ttk.Frame(self.canvas, style="TFrame")

        self.scrollable_frame.bind(
            "<Configure>",
            lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")),
        )

        self.canvas_window = self.canvas.create_window((0, 0), window=self.scrollable_frame, anchor="nw")
        self.canvas.configure(yscrollcommand=self.scrollbar.set)

        # Динамическая ширина карточек при изменении размера окна
        self.canvas.bind(
            "<Configure>",
            lambda e: self.canvas.itemconfig(self.canvas_window, width=e.width),
        )

        # Поддержка колеса мыши
        self.canvas.bind_all("<MouseWheel>", self._on_mousewheel)
        self.canvas.bind_all("<Button-4>", lambda e: self.canvas.yview_scroll(-1, "units"))
        self.canvas.bind_all("<Button-5>", lambda e: self.canvas.yview_scroll(1, "units"))

        self.canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        self.scrollbar.pack(side=tk.RIGHT, fill=tk.Y)

        # 4. Нижняя панель (Status Bar & Progress)
        bottom_frame = ttk.Frame(self.root, style="Surface.TFrame")
        bottom_frame.pack(fill=tk.X, side=tk.BOTTOM)

        self.progress_bar = ttk.Progressbar(
            bottom_frame,
            style="Modern.Horizontal.TProgressbar",
            mode="indeterminate",
            length=100,
        )
        self.progress_bar.pack(fill=tk.X)

        self.status_var = tk.StringVar(value="Готово")
        status_bar = ttk.Label(
            bottom_frame,
            textvariable=self.status_var,
            style="Status.TLabel",
            anchor="w",
        )
        status_bar.pack(fill=tk.X)

    def _on_mousewheel(self, event):
        if event.delta:
            self.canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")

    def select_category(self, category):
        self.selected_category.set(category)
        self.update_category_buttons()
        self.refresh_list()

    def update_category_buttons(self):
        cur = self.selected_category.get()
        for cat, btn in self.category_buttons:
            if cat == cur:
                btn.configure(
                    bg=THEME["chip_active_bg"],
                    fg=THEME["chip_active_fg"],
                    activebackground=THEME["chip_active_bg"],
                    activeforeground=THEME["chip_active_fg"],
                )
            else:
                btn.configure(
                    bg=THEME["chip_inactive_bg"],
                    fg=THEME["chip_inactive_fg"],
                    activebackground=THEME["card_hover"],
                    activeforeground=THEME["text"],
                )

    def refresh_list(self):
        for widget in self.scrollable_frame.winfo_children():
            widget.destroy()
        self.action_buttons = []

        items = backend.filter_catalog(
            self.catalog,
            query=self.search_query.get(),
            category=self.selected_category.get(),
        )

        if not items:
            empty_box = ttk.Frame(self.scrollable_frame, style="TFrame", padding="40")
            empty_box.pack(fill=tk.BOTH, expand=True)
            empty_lbl = ttk.Label(
                empty_box,
                text="Ничего не найдено",
                style="HeaderSub.TLabel",
                font=("sans-serif", 12),
            )
            empty_lbl.pack()
            return

        for item in items:
            self.create_item_card(item)

    def create_item_card(self, item):
        # Внешний контейнер карточки
        card = tk.Frame(
            self.scrollable_frame,
            bg=THEME["card_bg"],
            highlightbackground=THEME["card_border"],
            highlightcolor=THEME["accent"],
            highlightthickness=1,
            padx=12,
            pady=10,
        )
        card.pack(fill=tk.X, expand=True, pady=4, padx=2)

        # Левая часть — информация
        info_frame = tk.Frame(card, bg=THEME["card_bg"])
        info_frame.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        # Строка заголовка с бейджем категории
        header_row = tk.Frame(info_frame, bg=THEME["card_bg"])
        header_row.pack(anchor="w", fill=tk.X)

        title = tk.Label(
            header_row,
            text=item["name"],
            bg=THEME["card_bg"],
            fg=THEME["text"],
            font=("sans-serif", 11, "bold"),
        )
        title.pack(side=tk.LEFT)

        cat_badge = tk.Label(
            header_row,
            text=f" {item['category']} ",
            bg=THEME["badge_bg"],
            fg=THEME["badge_fg"],
            font=("sans-serif", 8, "bold"),
            padx=4,
            pady=1,
        )
        cat_badge.pack(side=tk.LEFT, padx=(8, 0))

        # Описание
        summary_text = item.get("summary", "")
        summary = tk.Label(
            info_frame,
            text=summary_text,
            bg=THEME["card_bg"],
            fg=THEME["subtext"],
            font=("sans-serif", 9),
            wraplength=480,
            justify=tk.LEFT,
        )
        summary.pack(anchor="w", pady=(4, 0))

        # Правая часть — статус и действия
        btn_frame = tk.Frame(card, bg=THEME["card_bg"])
        btn_frame.pack(side=tk.RIGHT, padx=(10, 0), anchor="center")

        installed = backend.is_item_installed(item)
        if installed:
            status_lbl = tk.Label(
                btn_frame,
                text="✔ Установлено",
                bg=THEME["card_bg"],
                fg="#a6e3a1",
                font=("sans-serif", 9, "bold"),
            )
            status_lbl.pack(side=tk.TOP, pady=(0, 4))

            source = backend.source_of(item)
            if source and source.get("kind") == "deb-tar":
                # Стек windows-программ разложен по системным путям без записи в
                # dpkg — авторазбор не предусмотрен (см. run_remove_item), поэтому
                # для него кнопки «Убрать» нет вовсе, а не сломанная кнопка.
                return

            btn = ttk.Button(
                btn_frame,
                text="Убрать",
                style="Remove.TButton",
                command=lambda i=item: self.remove_item(i),
            )
        else:
            btn = ttk.Button(
                btn_frame,
                text="Поставить",
                style="Install.TButton",
                command=lambda i=item: self.install_item(i),
            )

        btn.pack(side=tk.TOP)
        if self.busy:
            btn.state(["disabled"])
        self.action_buttons.append(btn)

    def set_busy(self, busy):
        self.busy = busy
        if busy:
            self.progress_bar.start(10)
        else:
            self.progress_bar.stop()

        for btn in self.action_buttons:
            try:
                btn.state(["disabled"] if busy else ["!disabled"])
            except tk.TclError:
                pass
        self.root.config(cursor="watch" if busy else "")

    def show_progress(self, text):
        self.events.put(("Движение", text))

    def pump_events(self):
        while True:
            try:
                kind, payload = self.events.get_nowait()
            except queue.Empty:
                break
            if kind == "Движение":
                self.status_var.set(payload)
            elif kind == "Готово":
                self.on_task_done(*payload)
            elif kind == "Отказ":
                self.on_unsafe(*payload)
        self.root.after(PUMP_MS, self.pump_events)

    def start_job(self, package_name, work, verb):
        if self.busy:
            return False
        self.set_busy(True)
        self.status_var.set(f"{verb} {package_name} — 0%")

        def task():
            try:
                code = work()
            except Exception as err:
                self.events.put(("Отказ", (package_name, str(err))))
                return
            self.events.put(("Готово", (package_name, code, verb)))

        threading.Thread(target=task, daemon=True).start()
        return True

    def install_item(self, item):
        return self.start_job(
            item["package"],
            lambda: backend.run_install_item(item, on_progress=self.show_progress, log_path=LOG_PATH),
            "Установка",
        )

    def remove_item(self, item):
        return self.start_job(
            item["package"],
            lambda: backend.run_remove_item(item, on_progress=self.show_progress, log_path=LOG_PATH),
            "Удаление",
        )

    def on_unsafe(self, package_name, reason):
        self.set_busy(False)
        self.status_var.set(f"Отказано: {reason}")
        messagebox.showerror("Отказано", f"Магазин не станет выполнять это имя пакета.\n{reason}")
        self.refresh_list()

    def on_task_done(self, package_name, code, verb):
        self.set_busy(False)
        if code == 0:
            self.status_var.set(f"Готово: {verb.lower()} {package_name} завершена")
            if verb == "Установка":
                item = next((it for it in self.catalog if it["package"] == package_name), None)
                guide = (item.get("source") or {}).get("guide") if item else None
                if guide:
                    messagebox.showinfo(f"{item['name']} — что дальше", guide)
        else:
            self.status_var.set(f"{verb} {package_name} не удалась (код {code})")
            tail = backend.log_tail(LOG_PATH)
            messagebox.showerror(
                "Ошибка",
                f"{verb} {package_name} не удалась, код {code}.\n\n"
                + (f"Последние строки apt:\n{tail}" if tail else f"Подробности: {LOG_PATH}"),
            )
        self.refresh_list()


def main():
    root = tk.Tk()
    app = StoreApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
