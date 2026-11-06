#!/usr/bin/env python3
import os
import re
import secrets
import signal
import subprocess
import tkinter as tk

HOME = os.path.expanduser("~")
CONFIG = os.path.join(HOME, ".config", "rustdesk", "RustDesk.toml")
ID_FILE = os.path.join(HOME, ".config", "kovkod", "rustdesk-id")


def desk_id():
    if os.path.exists(ID_FILE):
        with open(ID_FILE, encoding="ascii") as handle:
            return handle.read().strip()
    value = str(secrets.randbelow(899999999) + 100000000)
    os.makedirs(os.path.dirname(ID_FILE), exist_ok=True)
    with open(ID_FILE, "w", encoding="ascii") as handle:
        handle.write(value)
    return value


def write_config(rd_id, password):
    os.makedirs(os.path.dirname(CONFIG), exist_ok=True)
    text = ""
    if os.path.exists(CONFIG):
        with open(CONFIG, encoding="utf-8") as handle:
            text = handle.read()
    text = re.sub(r"(?m)^(enc_id|id|password) *=.*\n", "", text)
    fd = os.open(CONFIG, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(f"id = '{rd_id}'\npassword = '{password}'\n{text}")


def stop_servers():
    subprocess.run(["pkill", "-f", "[r]ustdesk --server"], check=False)


def main():
    stop_servers()
    rd_id = desk_id()
    password = secrets.token_hex(4)
    write_config(rd_id, password)
    env = dict(os.environ, LD_PRELOAD="/opt/stol/kovkod-sysv.so", XDG_SESSION_TYPE="x11")
    server = subprocess.Popen(["rustdesk", "--server"], env=env, stdin=subprocess.DEVNULL,
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)

    root = tk.Tk(className="rustdesk")
    root.title("Удалённый доступ")
    tk.Label(root, text="Назовите эти данные тому, кто подключается", padx=20, pady=12).pack()
    for caption, value in (("Номер", rd_id), ("Пароль", password)):
        tk.Label(root, text=caption).pack()
        field = tk.Entry(root, justify="center", font=("Sans", 18), width=14)
        field.insert(0, value)
        field.configure(state="readonly")
        field.pack(pady=(0, 10), padx=20)
    tk.Label(root, text="Пока это окно открыто, к столу могут подключиться.", padx=20).pack()

    def close():
        try:
            os.killpg(server.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        stop_servers()
        root.destroy()

    tk.Button(root, text="Закрыть доступ", command=close).pack(pady=12)
    root.protocol("WM_DELETE_WINDOW", close)
    root.mainloop()


main()
