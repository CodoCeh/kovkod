#!/usr/bin/env python3
"""
store/codoceh-store.py — графический интерфейс Магазина приложений и игр (R17).
"""

import os
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import messagebox, ttk

import backend


class StoreApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Магазин КодоЦех")
        self.root.geometry("640x520")
        self.root.minsize(500, 400)

        self.catalog = backend.load_catalog()
        self.categories = ["Все", "Разработка", "Офис", "Графика", "Игры", "Утилиты"]
        self.selected_category = tk.StringVar(value="Все")
        self.search_query = tk.StringVar(value="")

        self.setup_ui()
        self.refresh_list()

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

        installed = backend.is_installed(item["package"])
        if installed:
            status_lbl = ttk.Label(btn_frame, text="Установлено", foreground="green")
            status_lbl.pack(side=tk.TOP, pady=(0, 4))
            del_btn = ttk.Button(btn_frame, text="Убрать", command=lambda p=item["package"]: self.remove_pkg(p))
            del_btn.pack(side=tk.TOP)
        else:
            inst_btn = ttk.Button(btn_frame, text="Поставить", command=lambda p=item["package"]: self.install_pkg(p))
            inst_btn.pack(side=tk.TOP)

    def install_pkg(self, package_name):
        self.status_var.set(f"Установка {package_name}...")
        self.root.config(cursor="watch")

        def task():
            cmd = backend.install_command(package_name)
            res = subprocess.run(cmd, shell=True)
            self.root.after(0, lambda: self.on_task_done(package_name, res.returncode, "установлен"))

        threading.Thread(target=task, daemon=True).start()

    def remove_pkg(self, package_name):
        self.status_var.set(f"Удаление {package_name}...")
        self.root.config(cursor="watch")

        def task():
            cmd = backend.remove_command(package_name)
            res = subprocess.run(cmd, shell=True)
            self.root.after(0, lambda: self.on_task_done(package_name, res.returncode, "удалён"))

        threading.Thread(target=task, daemon=True).start()

    def on_task_done(self, package_name, code, verb):
        self.root.config(cursor="")
        if code == 0:
            self.status_var.set(f"Пакет {package_name} успешно {verb}")
        else:
            self.status_var.set(f"Не удалось выполнить действие над {package_name}")
            messagebox.showerror(
                "Ошибка",
                f"Не удалось установить/удалить {package_name}.\nПроверьте подключение к сети и место на диске.",
            )
        self.refresh_list()


def main():
    root = tk.Tk()
    app = StoreApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
