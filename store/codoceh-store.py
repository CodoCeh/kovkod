#!/usr/bin/env python3
"""
store/codoceh-store.py — графический интерфейс Магазина приложений и игр (R17).
"""

import os
import queue
import sys
import threading
import tkinter as tk
from tkinter import messagebox, ttk

import backend

LOG_PATH = "/tmp/codoceh-store.log"
# Как часто окно забирает вести из рабочего потока. Требование — движение не реже
# раза в две секунды, так что запас здесь десятикратный.
PUMP_MS = 150


class StoreApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Магазин КодоЦех")
        self.root.geometry("640x520")
        self.root.minsize(500, 400)

        self.catalog = backend.load_catalog()
        # Разделы — из каталога: каталог это данные, и захардкоженный список
        # прятал бы от человека целый раздел, пока кто-нибудь не вспомнит про него.
        self.categories = backend.categories_of(self.catalog)
        self.selected_category = tk.StringVar(value="Все")
        self.search_query = tk.StringVar(value="")
        # Пока идёт установка, вторая кнопка нажиматься не должна: второй
        # apt-get упирается в блокировку dpkg и человек получает ложный
        # диагноз про сеть вместо честного «идёт установка».
        self.busy = False
        self.action_buttons = []
        self.events = queue.SimpleQueue()

        self.setup_ui()
        self.refresh_list()
        self.root.after(PUMP_MS, self.pump_events)

    def setup_ui(self):
        top_frame = ttk.Frame(self.root, padding="8")
        top_frame.pack(fill=tk.X)

        # Категории
        cat_label = ttk.Label(top_frame, text="Раздел:")
        cat_label.pack(side=tk.LEFT, padx=(0, 4))
        cat_combo = ttk.Combobox(
            top_frame,
            textvariable=self.selected_category,
            values=self.categories,
            state="readonly",
            width=14,
        )
        cat_combo.pack(side=tk.LEFT, padx=(0, 12))
        cat_combo.bind("<<ComboboxSelected>>", lambda e: self.refresh_list())

        # Поиск
        search_label = ttk.Label(top_frame, text="Поиск:")
        search_label.pack(side=tk.LEFT, padx=(0, 4))
        search_entry = ttk.Entry(top_frame, textvariable=self.search_query)
        search_entry.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 4))
        search_entry.bind("<KeyRelease>", lambda e: self.refresh_list())

        # Список программ
        list_frame = ttk.Frame(self.root, padding="8")
        list_frame.pack(fill=tk.BOTH, expand=True)

        self.canvas = tk.Canvas(list_frame, borderwidth=0, highlightthickness=0)
        self.scrollbar = ttk.Scrollbar(list_frame, orient="vertical", command=self.canvas.yview)
        self.scrollable_frame = ttk.Frame(self.canvas)

        self.scrollable_frame.bind(
            "<Configure>",
            lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")),
        )

        self.canvas.create_window((0, 0), window=self.scrollable_frame, anchor="nw")
        self.canvas.configure(yscrollcommand=self.scrollbar.set)

        self.canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        self.scrollbar.pack(side=tk.RIGHT, fill=tk.Y)

        # Статусная строка
        self.status_var = tk.StringVar(value="Готово")
        status_bar = ttk.Label(self.root, textvariable=self.status_var, relief=tk.SUNKEN, anchor="w", padding="4")
        status_bar.pack(fill=tk.X, side=tk.BOTTOM)

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
            empty_lbl = ttk.Label(self.scrollable_frame, text="Ничего не найдено", padding="20")
            empty_lbl.pack()
            return

        for item in items:
            self.create_item_row(item)

    def create_item_row(self, item):
        row = ttk.Frame(self.scrollable_frame, padding="6", relief=tk.GROOVE)
        row.pack(fill=tk.X, expand=True, pady=3, padx=2)

        info_frame = ttk.Frame(row)
        info_frame.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        title = ttk.Label(info_frame, text=f"{item['name']} ({item['category']})", font=("", 11, "bold"))
        title.pack(anchor="w")

        summary = ttk.Label(info_frame, text=item.get("summary", ""), wraplength=420)
        summary.pack(anchor="w", pady=(2, 0))

        btn_frame = ttk.Frame(row)
        btn_frame.pack(side=tk.RIGHT, padx=6)

        installed = backend.is_item_installed(item)
        if installed:
            status_lbl = ttk.Label(btn_frame, text="Установлено", foreground="green")
            status_lbl.pack(side=tk.TOP, pady=(0, 4))
            # Позицию со своим источником убирает не apt: dpkg о ней не знает.
            # Пока убирать нечем — кнопку не рисуем, чтобы она не обманывала.
            if backend.source_of(item):
                return
            btn = ttk.Button(btn_frame, text="Убрать", command=lambda p=item["package"]: self.remove_pkg(p))
        else:
            btn = ttk.Button(btn_frame, text="Поставить", command=lambda i=item: self.install_item(i))
        btn.pack(side=tk.TOP)
        if self.busy:
            btn.state(["disabled"])
        self.action_buttons.append(btn)

    def set_busy(self, busy):
        self.busy = busy
        for btn in self.action_buttons:
            try:
                btn.state(["disabled"] if busy else ["!disabled"])
            except tk.TclError:
                pass
        self.root.config(cursor="watch" if busy else "")

    def show_progress(self, text):
        # Из рабочего потока в окно ходим только через очередь. Прямой вызов
        # tkinter из чужого потока роняет установку целиком: «main thread is not
        # in main loop» приходит вместо строки состояния.
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
            # Второй клик по занятому магазину. Кнопки уже заблокированы —
            # это последний рубеж на случай клавиатуры и повторного события.
            return False
        self.set_busy(True)
        self.status_var.set(f"{verb} {package_name} — 0%")

        def task():
            try:
                code = work()
            except Exception as err:
                # Установка из своего источника падает не только на имени пакета:
                # кончилось место, оборвалась сеть, не сошлась сумма. Молчащий
                # рабочий поток оставил бы окно занятым навсегда.
                self.events.put(("Отказ", (package_name, str(err))))
                return
            self.events.put(("Готово", (package_name, code, verb)))

        threading.Thread(target=task, daemon=True).start()
        return True

    def install_item(self, item):
        # Позиция со своим источником ставится тем же нажатием, что и обычная:
        # разницу знает backend, а человек видит одну кнопку «Поставить».
        return self.start_job(
            item["package"],
            lambda: backend.run_install_item(item, on_progress=self.show_progress, log_path=LOG_PATH),
            "Установка",
        )

    def remove_pkg(self, package_name):
        return self.start_job(
            package_name,
            lambda: backend.run_remove(package_name, on_progress=self.show_progress, log_path=LOG_PATH),
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
        else:
            self.status_var.set(f"{verb} {package_name} не удалась (код {code})")
            # Причину показываем настоящую — последние строки apt. Прежний текст
            # про сеть был диагнозом наугад: чаще всего сеть была ни при чём.
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
