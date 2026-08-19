#!/usr/bin/env python3
"""
store/backend.py — логика управления пакетами для Магазина КодоЦех (R17).

Установка идёт списком аргументов, без оболочки (R10i.3), а движение берётся из
собственного потока состояния apt (R10i.1): apt-get умеет писать в отдельный
файловый дескриптор строки вида «pmstatus:пакет:37.5:Распаковывается пакет».
Разбирается только число — оно всегда с точкой и не зависит от языка образа;
текст переведён, и опираться на него нельзя: русский образ печатает «Кандидат»
там, где английский печатал «Candidate».

Модуль работает и как командная строка — тем же кодом, что и окно:
    python3 backend.py install <пакет> [--progress ФАЙЛ] [--log ФАЙЛ]
    python3 backend.py remove  <пакет> [--log ФАЙЛ]
    python3 backend.py installed <пакет>
    python3 backend.py install-item <id> [--progress ФАЙЛ] [--log ФАЙЛ]
    python3 backend.py installed-item <id>

Позиция каталога со своим источником (поле «source») ставится не из пакетной базы
Debian, а из названного в каталоге архива — так ставится стек windows-программ
(R04.1). Причина не в лени: этих пакетов в Debian нет вовсе, а положить их в образ
нельзя — стек весит два с лишним гигабайта при запасе APK в 38 МБ (R04.5, R08i).
"""

import hashlib
import io
import json
import lzma
import os
import re
import select
import shlex
import subprocess
import sys
import tarfile
import time
import urllib.request

# Имя пакета Debian: строчная латиница, цифры, «+», «-», «.»; начинается с буквы
# или цифры. Всё остальное — не имя пакета, а попытка дописать свою команду.
# Пока имена приходят из своего каталога, проверка ничего не меняет; она нужна в
# тот день, когда в магазине появится поиск по apt и имя придёт от человека.
PACKAGE_RE = re.compile(r"^[a-z0-9][a-z0-9+.-]*$")


class UnsafePackageName(ValueError):
    """Имя пакета не прошло проверку — ни одна команда не запускается."""


def check_package_name(name):
    if not isinstance(name, str) or not PACKAGE_RE.match(name):
        raise UnsafePackageName("недопустимое имя пакета: %r" % (name,))
    return name


def load_catalog(path=None):
    if path is None:
        path = os.path.join(os.path.dirname(__file__), "catalog.json")
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def item_by_id(item_id, path=None):
    for item in load_catalog(path):
        if item.get("id") == item_id:
            return item
    return None


def categories_of(catalog):
    """Разделы берутся из каталога. Захардкоженный список был бы дефектом: новая
    строка каталога добавляла бы программу, которую в окне не найти по разделу."""
    seen = []
    for item in catalog:
        name = item.get("category", "")
        if name and name not in seen:
            seen.append(name)
    return ["Все"] + seen


def filter_catalog(catalog, query="", category="Все"):
    q = query.strip().lower()
    results = []
    for item in catalog:
        if category != "Все" and item.get("category") != category:
            continue
        if q:
            name = item.get("name", "").lower()
            pkg = item.get("package", "").lower()
            summary = item.get("summary", "").lower()
            if q not in name and q not in pkg and q not in summary:
                continue
        results.append(item)
    return results


# ------------------------------------------------------------------ команды


def update_argv():
    return ["apt-get", "update", "-y"]


def install_argv(package_name):
    return ["apt-get", "install", "-y", "--no-install-recommends", check_package_name(package_name)]


def remove_argv(package_name):
    return ["apt-get", "remove", "-y", check_package_name(package_name)]


def query_argv(package_name):
    return ["dpkg-query", "-W", "-f=${Status}", check_package_name(package_name)]


def is_installed(package_name, runner=None):
    """Проверяет установку. Аргументы списком: имя пакета в оболочку не попадает."""
    argv = query_argv(package_name)
    if runner is None:
        def runner(cmd):
            res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            return res.returncode, res.stdout.decode("utf-8", "replace")
    code, out = runner(argv)
    return "install ok installed" in out


def install_command(package_name):
    """Та же установка текстом — для проверок, которым нужна строка для оболочки.

    Окно магазина этим не пользуется: оно зовёт run_install со списком аргументов.
    Имя пакета здесь проходит ту же проверку и экранируется, поэтому строка
    остаётся безопасной, но новых мест с оболочкой на пути установки не заводим.
    """
    check_package_name(package_name)
    return (
        "set -e\n"
        "export DEBIAN_FRONTEND=noninteractive\n"
        "%s || true\n"
        "%s || (\n"
        "%s"
        "  %s\n"
        ")\n"
        % (
            shlex.join(update_argv()),
            shlex.join(install_argv(package_name)),
            repair_command(),
            shlex.join(install_argv(package_name)),
        )
    )


def repair_command(indent="  "):
    """Разбор последствий обрыва установки.

    Только «apt-get --fix-broken install» тут не помогает: после убитого на
    полпути dpkg apt отказывается работать вовсе и требует «dpkg --configure -a».
    Порядок обязателен — сначала доводим начатое, потом чиним зависимости.

    Убитый на распаковке dpkg оставляет пакет в состоянии reinstreq: настраивать
    такой он отказывается наотрез («пакет абсолютно неработоспособен»), а
    «--fix-broken install» только пробует настроить и уходит ни с чем. Из этого
    состояния выводит единственное — переустановка поимённо, после которой пакет
    остаётся распакованным и его добивает второй «dpkg --configure -a».

    Переустанавливается не только reinstreq. Обрыв оставляет пакет в любом из
    состояний half-installed, unpacked, half-configured, и флаг при этом бывает
    обычный «ok»: прогон из трёх краснел ровно на «install ok half-installed» —
    его прежний отбор по reinstreq не видел, а «--fix-broken install» такой пакет
    не доводит. Поэтому берём всё, что помечено к установке, но до состояния
    «installed» не дошло.

    Первые шаги намеренно не роняют вызывающего: команда установки зовёт
    восстановление под «set -e», и падение на полпути отняло бы у человека
    повторную попытку. Судить о результате — по состоянию системы, а не по коду
    промежуточного шага.

    Имён пакетов здесь нет вовсе: текст неизменный, снаружи в него ничего не
    подставляется, поэтому запускать его через оболочку безопасно.
    """
    return (
        f"{indent}export DEBIAN_FRONTEND=noninteractive\n"
        f"{indent}dpkg --configure -a || true\n"
        f"{indent}apt-get -y --fix-broken install || true\n"
        f"{indent}dpkg-query -W -f='${{Status}} ${{Package}}\\n' > /tmp/stol-repair-status.txt 2>/dev/null || true\n"
        f"{indent}awk '$1 == \"install\" && $3 != \"installed\" {{ print $4 }}'"
        f" /tmp/stol-repair-status.txt > /tmp/stol-repair-broken.txt\n"
        f"{indent}[ -s /tmp/stol-repair-broken.txt ]"
        f" && xargs -r apt-get install -y --reinstall < /tmp/stol-repair-broken.txt || true\n"
        f"{indent}dpkg --configure -a\n"
    )


def remove_command(package_name):
    check_package_name(package_name)
    return (
        "set -e\n"
        "export DEBIAN_FRONTEND=noninteractive\n"
        "%s\n" % shlex.join(remove_argv(package_name))
    )


def check_health_command():
    return "apt-get check\n"


# ------------------------------------------------------------------ движение


def parse_status_line(line):
    """Строка потока состояния apt → (доля в процентах, что делается).

    Формат: «pmstatus:geany:37.5:Распаковывается geany». Берём только число:
    оно одинаково в любой локали, а текст переведён.
    """
    parts = line.split(":", 3)
    if len(parts) < 4 or parts[0] not in ("pmstatus", "dlstatus"):
        return None, None
    try:
        percent = float(parts[2])
    except ValueError:
        return None, None
    return percent, parts[3].strip()


def format_progress(label, percent, action, elapsed):
    """Строка для человека. Число в ней — настоящее: доля от apt и прожитые секунды."""
    text = "%s — %.0f%%" % (label, percent)
    if action:
        text += " · " + (action[:60] + "…" if len(action) > 60 else action)
    return text + " · %d с" % int(elapsed)


def _apt_env():
    env = dict(os.environ)
    env["DEBIAN_FRONTEND"] = "noninteractive"
    return env


def _run_apt(argv, label, on_progress, log):
    """Гонит apt-get списком аргументов и переводит его поток состояния в строку.

    Строка обновляется не реже чем раз в секунду даже там, где apt молчит:
    прожитые секунды — такое же измеренное число, как и доля, и рисовать
    несуществующее движение не приходится.
    """
    rfd, wfd = os.pipe()
    proc = subprocess.Popen(
        argv + ["-o", "APT::Status-Fd=%d" % wfd],
        pass_fds=(wfd,),
        stdout=log,
        stderr=subprocess.STDOUT,
        env=_apt_env(),
    )
    os.close(wfd)

    started = time.monotonic()
    percent, action, buf = 0.0, "", b""
    last_shown = -1.0
    exited_at = None
    while True:
        ready = select.select([rfd], [], [], 0.3)[0]
        if ready:
            chunk = os.read(rfd, 65536)
            if chunk:
                buf += chunk
                while b"\n" in buf:
                    raw, buf = buf.split(b"\n", 1)
                    got, what = parse_status_line(raw.decode("utf-8", "replace"))
                    if got is not None:
                        percent = got
                        action = what or action
            else:
                break  # apt закрыл поток — работа кончилась
        now = time.monotonic()
        if on_progress and now - last_shown >= 0.8:
            on_progress(format_progress(label, percent, action, now - started))
            last_shown = now
        if proc.poll() is not None:
            # Дескриптор мог достаться потомку, пережившему apt: ждём его недолго.
            exited_at = exited_at or now
            if now - exited_at > 2.0:
                break

    os.close(rfd)
    code = proc.wait()
    if on_progress:
        on_progress(format_progress(
            label, 100.0 if code == 0 else percent, action, time.monotonic() - started))
    return code


def run_repair(on_progress=None, log=None):
    """Восстановление после обрыва. Текст неизменный, имён снаружи в нём нет."""
    proc = subprocess.Popen(
        ["bash", "-c", repair_command("")],
        stdout=log, stderr=subprocess.STDOUT, env=_apt_env(),
    )
    started = time.monotonic()
    while proc.poll() is None:
        time.sleep(0.5)
        if on_progress:
            on_progress("Разбор последствий обрыва · %d с" % int(time.monotonic() - started))
    return proc.returncode


def run_install(package_name, on_progress=None, log_path=None):
    """Ставит пакет так же, как это делает окно магазина: списком аргументов.

    on_progress зовётся не реже раза в секунду — это и есть признак жизни в окне.
    """
    check_package_name(package_name)
    log = open(log_path, "ab", buffering=0) if log_path else open(os.devnull, "wb")
    try:
        _run_apt(update_argv(), "Обновление списков", on_progress, log)
        label = "Установка %s" % package_name
        code = _run_apt(install_argv(package_name), label, on_progress, log)
        if code != 0:
            run_repair(on_progress, log)
            code = _run_apt(install_argv(package_name), label, on_progress, log)
        return code
    finally:
        log.close()


def run_remove(package_name, on_progress=None, log_path=None):
    check_package_name(package_name)
    log = open(log_path, "ab", buffering=0) if log_path else open(os.devnull, "wb")
    try:
        return _run_apt(remove_argv(package_name), "Удаление %s" % package_name, on_progress, log)
    finally:
        log.close()


def log_tail(path, lines=5):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            return "".join(f.readlines()[-lines:]).strip()
    except OSError:
        return ""


# ---------------------------------------------------- позиция со своим источником
#
# Стек windows-программ (Hangover) ставится по требованию и не из Debian. Здесь
# три вещи, каждая измерена живым прогоном, а не выбрана из вкуса:
#
# 1. Файлы .deb распаковываются своим кодом, а не «apt-get install ./файл.deb».
#    Под proot внутренний распаковщик dpkg падает на большом data.tar.xz с
#    «malloc(): corrupted top size» после полутора гигабайт, а тот же поток через
#    lzma питона проходит целиком (2,39 ГБ за 24 с). Мы не чиним dpkg — мы его
#    обходим ровно там, где он под proot ломается.
# 2. Зависимости ставятся списком из каталога, и libsane1 в нём нет намеренно:
#    он тянет systemd, а postinst systemd роняет сам proot
#    («compare_paths2: Assertion `length2 > 0'»), после чего в образе не
#    настраивается уже ни один пакет. Ценой отказа — сканеры в wine, их у
#    телефона нет.
# 3. Место проверяется до скачивания: стек занимает больше двух гигабайт, и
#    честный отказ «нужно N МБ, свободно M» лучше, чем полуразвёрнутый стек.

WINE_PREFIX = "/root/.wine"
WINDOWS_DIR = "/opt/stol/windows"
WINDOWS_APPS = "/root/Windows"
WINDOWS_DESKTOP = "/root/Desktop/windows-programs.desktop"

# Своя unix-часть wine. Готовый Hangover на Android запускаться не может: Android
# при targetSdk 29 и выше держит приложение в домене untrusted_app, где запрещён
# execmod — страницу ОТОБРАЖЁННОГО ФАЙЛА, в которую писали, нельзя сделать
# исполняемой. Замер в домене самого приложения (u:r:untrusted_app:s0):
#     отображение файла, страницу не трогали → mprotect(R|X) — получилось;
#     отображение файла, в страницу записали → mprotect(R|X) — ОТКАЗ EACCES;
#     безымянная память, записали            → mprotect(R|X) — получилось.
# Wine раскладывает PE-образ ровно первым способом и падает на
# «map_image_into_view failed to set 60000020 protection ... noexec filesystem?».
# Понижать targetSdk нельзя: Google Play с 31.08.2026 требует API 36.
# Лечение: своя сборка dlls/ntdll/ntdll.so из тех же исходников, что у релиза
# (tools/hangover/build-ntdll.sh), где загрузка PE идёт штатным путём wine для
# носителей без права исполнения — pread в безымянную память вида. Файл кладётся
# поверх того, что принёс .deb, и его наличие обязательно: без него игры не идут.
WINE_UNIX_NTDLL = "/usr/lib/wine/aarch64-unix/ntdll.so"
OUR_NTDLL = "/opt/stol/store/hangover-ntdll.so"
OUR_NTDLL_MARK = b"CodoCeh anon-PE loader"

# Окно-точка входа. Лежит строкой здесь, потому что в образ попадают ровно три
# файла магазина (tools/mkimage.sh) — четвёртый до образа не доедет, а ярлык на
# столе нужен сразу после установки (R04.1a).
LAUNCHER_PY = r'''#!/usr/bin/env python3
"""Окно «Windows-программы»: точка входа стека со стола.

Человек кладёт .exe в /root/Windows (или выбирает файл кнопкой) и запускает его
одним нажатием. Ровно то, чем PortProton служит на настольном Linux, и ровно то,
чего требует R04.1a: со стола, а не из терминала.
"""

import os
import subprocess
import tkinter as tk
from tkinter import filedialog, ttk

APPS = "/root/Windows"
WINE = "/usr/bin/wine"
PREFIX = "/root/.wine"


def found_exes():
    out = []
    for root, dirs, files in os.walk(APPS):
        if root.count(os.sep) - APPS.count(os.sep) > 2:
            dirs[:] = []
            continue
        for name in sorted(files):
            if name.lower().endswith(".exe"):
                out.append(os.path.join(root, name))
    return out


class Launcher:
    def __init__(self, root):
        self.root = root
        root.title("Windows-программы")
        root.geometry("640x420")
        ttk.Label(root, text="Программы Windows из %s" % APPS, padding=8).pack(anchor="w")
        self.list = tk.Listbox(root, activestyle="dotbox")
        self.list.pack(fill=tk.BOTH, expand=True, padx=8)
        self.list.bind("<Double-Button-1>", lambda e: self.run())
        bar = ttk.Frame(root, padding=8)
        bar.pack(fill=tk.X)
        ttk.Button(bar, text="Запустить", command=self.run).pack(side=tk.LEFT)
        ttk.Button(bar, text="Открыть .exe…", command=self.pick).pack(side=tk.LEFT, padx=6)
        ttk.Button(bar, text="Обновить список", command=self.refresh).pack(side=tk.LEFT)
        self.status = tk.StringVar(value="Готово")
        ttk.Label(root, textvariable=self.status, relief=tk.SUNKEN, anchor="w",
                  padding=4).pack(fill=tk.X, side=tk.BOTTOM)
        self.refresh()

    def refresh(self):
        self.items = found_exes()
        self.list.delete(0, tk.END)
        for path in self.items:
            self.list.insert(tk.END, os.path.relpath(path, APPS))
        if not self.items:
            self.status.set("В %s нет ни одной программы — положите .exe туда" % APPS)
        else:
            self.status.set("Программ найдено: %d" % len(self.items))

    def start(self, path):
        env = dict(os.environ)
        env["WINEPREFIX"] = PREFIX
        env.setdefault("WINEDEBUG", "-all")
        subprocess.Popen([WINE, path], cwd=os.path.dirname(path) or "/root", env=env,
                         stdout=open("/tmp/stol-windows-run.log", "ab", buffering=0),
                         stderr=subprocess.STDOUT)
        self.status.set("Запущено: %s" % os.path.basename(path))

    def run(self):
        picked = self.list.curselection()
        if not picked:
            self.status.set("Сначала выберите программу в списке")
            return
        self.start(self.items[picked[0]])

    def pick(self):
        path = filedialog.askopenfilename(initialdir=APPS, title="Выберите программу Windows",
                                          filetypes=[("Программы Windows", "*.exe"), ("Все файлы", "*")])
        if path:
            self.start(path)


if __name__ == "__main__":
    os.makedirs(APPS, exist_ok=True)
    window = tk.Tk()
    Launcher(window)
    window.mainloop()
'''

LAUNCHER_DESKTOP = """[Desktop Entry]
Version=1.0
Type=Application
Name=Windows-программы
Comment=Запуск программ и игр Windows через стек Hangover
Exec=python3 /opt/stol/windows/launcher.py
Icon=applications-other
Categories=System;Emulator;
Terminal=false
StartupNotify=true
"""


class NotEnoughSpace(RuntimeError):
    """Места под стек не хватает — не начинаем то, что не сможем закончить."""


class BadDownload(RuntimeError):
    """Скачанное не совпало с тем, что названо в каталоге."""


class MissingOurNtdll(RuntimeError):
    """Нашей unix-части wine нет — на Android такой стек мёртв, ставить нечего."""


def source_of(item):
    """Источник позиции каталога или None, если позиция обычная, из Debian."""
    return item.get("source")


def is_item_installed(item):
    """Установлена ли позиция. У позиции со своим источником dpkg не спрашивают:
    он о ней не знает и знать не может — признаком служит названный в каталоге файл."""
    source = source_of(item)
    if source:
        if not os.path.exists(source["marker"]):
            return False
        # Стек без нашей unix-части wine на Android мёртв: ярлык есть, а любая
        # windows-программа падает на execmod. Считать такой стек установленным
        # значит врать человеку, поэтому спрашиваем и про неё.
        return not source.get("our_ntdll") or has_our_ntdll()
    return is_installed(item["package"])


def free_mb(path="/"):
    stat = os.statvfs(path)
    return int(stat.f_bavail * stat.f_frsize / (1024 * 1024))


def _download(url, dest, want_sha, want_bytes, on_progress, label):
    """Качает архив и сверяет его с каталогом. Движение — прожитые байты и секунды."""
    digest = hashlib.sha256()
    got = 0
    started = time.monotonic()
    last = -1.0
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (X11; Linux aarch64)"})
    with urllib.request.urlopen(req, timeout=60) as answer, open(dest, "wb") as out:
        while True:
            chunk = answer.read(1 << 20)
            if not chunk:
                break
            out.write(chunk)
            digest.update(chunk)
            got += len(chunk)
            now = time.monotonic()
            if on_progress and now - last >= 0.8:
                share = 100.0 * got / want_bytes if want_bytes else 0.0
                on_progress(format_progress(
                    label, min(share, 100.0), "скачано %d МБ" % (got >> 20), now - started))
                last = now
    if want_bytes and got != want_bytes:
        raise BadDownload("скачано %d байт вместо %d — источник отдал не то" % (got, want_bytes))
    if want_sha and digest.hexdigest() != want_sha:
        raise BadDownload("сумма sha256 не сошлась: %s вместо %s" % (digest.hexdigest(), want_sha))
    return got


class _XzMember(io.RawIOBase):
    """Поток data.tar.xz из .deb: распаковывается по кускам, целиком в память не берётся."""

    def __init__(self, handle, size):
        self.handle, self.left = handle, size
        self.engine, self.buffer = lzma.LZMADecompressor(), b""

    def readable(self):
        return True

    def readinto(self, target):
        while not self.buffer and self.left > 0:
            chunk = self.handle.read(min(1 << 20, self.left))
            if not chunk:
                break
            self.left -= len(chunk)
            self.buffer = self.engine.decompress(chunk)
        size = min(len(target), len(self.buffer))
        target[:size] = self.buffer[:size]
        self.buffer = self.buffer[size:]
        return size


def _deb_data_member(handle):
    """Смещение и длина data.tar.xz внутри .deb (это архив ar, разбирается заголовками)."""
    if handle.read(8) != b"!<arch>\n":
        raise BadDownload("это не пакет Debian: нет заголовка ar")
    while True:
        head = handle.read(60)
        if len(head) < 60:
            raise BadDownload("в пакете нет data.tar.xz")
        name = head[0:16].decode("ascii", "replace").strip().rstrip("/")
        size = int(head[48:58].decode("ascii", "replace").strip())
        if name == "data.tar.xz":
            return size
        handle.seek(size + size % 2, 1)


def _unpack_deb(archive, deb_name, on_progress, label, started):
    """Раскладывает один .deb по корню образа, не распаковывая его на диск."""
    files = 0
    last = -1.0
    with archive.extractfile(deb_name) as deb:
        size = _deb_data_member(deb)
        stream = io.BufferedReader(_XzMember(deb, size))
        with tarfile.open(fileobj=stream, mode="r|") as data:
            for member in data:
                data.extract(member, "/", set_attrs=True, filter="tar")
                files += 1
                now = time.monotonic()
                if on_progress and now - last >= 0.8:
                    on_progress(format_progress(
                        label, 0.0, "%s: файлов %d" % (deb_name.split("_")[0], files), now - started))
                    last = now
    return files


def _write_entry_point(log):
    """Ярлык на столе и окно за ним. Без этого стек есть, а нажать на столе нечего."""
    os.makedirs(WINDOWS_DIR, exist_ok=True)
    os.makedirs(WINDOWS_APPS, exist_ok=True)
    launcher = os.path.join(WINDOWS_DIR, "launcher.py")
    with open(launcher, "w", encoding="utf-8") as handle:
        handle.write(LAUNCHER_PY)
    os.chmod(launcher, 0o755)
    os.makedirs(os.path.dirname(WINDOWS_DESKTOP), exist_ok=True)
    with open(WINDOWS_DESKTOP, "w", encoding="utf-8") as handle:
        handle.write(LAUNCHER_DESKTOP)
    os.chmod(WINDOWS_DESKTOP, 0o755)
    log.write(b"entry point: %s + %s\n" % (launcher.encode(), WINDOWS_DESKTOP.encode()))


def has_our_ntdll(path=WINE_UNIX_NTDLL):
    """Наша ли unix-часть wine лежит в стеке. Метка вшита в сборку (см. OUR_NTDLL_MARK)."""
    try:
        with open(path, "rb") as handle:
            return OUR_NTDLL_MARK in handle.read()
    except OSError:
        return False


def _swap_ntdll(log):
    """Кладёт свою unix-часть wine поверх принесённой .deb-ом.

    Отказ здесь — отказ всей установки: стек без этой замены на Android ставится,
    ярлык рисует, а на любой windows-программе падает на execmod. Лучше честная
    ошибка на установке, чем мёртвый ярлык (R04.6)."""
    if not os.path.exists(OUR_NTDLL):
        raise MissingOurNtdll("в образе нет %s — стек собран не нами" % OUR_NTDLL)
    with open(OUR_NTDLL, "rb") as handle:
        blob = handle.read()
    if OUR_NTDLL_MARK not in blob:
        raise MissingOurNtdll("в %s нет метки нашей сборки" % OUR_NTDLL)
    if not os.path.exists(WINE_UNIX_NTDLL):
        raise MissingOurNtdll("стек не принёс %s — менять нечего" % WINE_UNIX_NTDLL)
    stock = WINE_UNIX_NTDLL + ".stock"
    if not os.path.exists(stock):
        os.replace(WINE_UNIX_NTDLL, stock)
    tmp = WINE_UNIX_NTDLL + ".new"
    with open(tmp, "wb") as handle:
        handle.write(blob)
    os.chmod(tmp, 0o755)
    os.replace(tmp, WINE_UNIX_NTDLL)
    log.write(b"ntdll.so: %d bytes from %s\n" % (len(blob), OUR_NTDLL.encode()))
    return len(blob)


def _wineboot(on_progress, log):
    """Первый запуск wine: он создаёт бутыль. Делаем это на установке, а не при
    первом запуске игры, — иначе человек нажимает на игру и минуты смотрит в пустоту."""
    env = dict(os.environ)
    env.update({"WINEPREFIX": WINE_PREFIX, "WINEDEBUG": "-all", "DISPLAY": env.get("DISPLAY", ":1")})
    proc = subprocess.Popen(["/usr/bin/wine", "wineboot", "-u"],
                            stdout=log, stderr=subprocess.STDOUT, env=env)
    started = time.monotonic()
    while proc.poll() is None:
        time.sleep(0.5)
        if on_progress:
            on_progress("Готовим бутыль wine · %d с" % int(time.monotonic() - started))
    return proc.returncode


def run_install_item(item, on_progress=None, log_path=None):
    """Ставит позицию каталога: обычную — через apt, со своим источником — из архива."""
    source = source_of(item)
    if not source:
        return run_install(item["package"], on_progress=on_progress, log_path=log_path)

    log = open(log_path, "ab", buffering=0) if log_path else open(os.devnull, "wb")
    started = time.monotonic()
    try:
        need = int(source.get("free_mb", 0))
        have = free_mb("/")
        if need and have < need:
            raise NotEnoughSpace("нужно %d МБ свободного места, свободно %d МБ" % (need, have))

        deps = [check_package_name(name) for name in source.get("apt", [])]
        if deps:
            _run_apt(update_argv(), "Обновление списков", on_progress, log)
            code = _run_apt(["apt-get", "install", "-y", "--no-install-recommends"] + deps,
                            "Зависимости стека", on_progress, log)
            if code != 0:
                return code

        kind = source.get("kind", "deb-tar")
        if kind == "deb-tar":
            work = "/tmp/stol-windows"
            os.makedirs(work, exist_ok=True)
            archive_path = os.path.join(work, os.path.basename(source["url"]))
            _download(source["url"], archive_path, source.get("sha256"), int(source.get("bytes", 0)),
                      on_progress, "Скачивание стека")

            with tarfile.open(archive_path, "r:") as archive:
                for deb_name in source.get("debs", []):
                    _unpack_deb(archive, deb_name, on_progress, "Распаковка стека", started)
            os.remove(archive_path)

            subprocess.run(["/sbin/ldconfig"], stdout=log, stderr=subprocess.STDOUT, check=False)
            if not os.path.exists(source["marker"]):
                log.write(b"marker missing after unpack\n")
                return 8
            if source.get("our_ntdll"):
                if on_progress:
                    on_progress("Своя часть wine · загрузка PE в безымянную память")
                _swap_ntdll(log)
                _write_entry_point(log)
                code = _wineboot(on_progress, log)
            else:
                code = 0
            if on_progress:
                on_progress(format_progress("Установка стека", 100.0 if code == 0 else 0.0,
                                            "готово" if code == 0 else "ошибка установки",
                                            time.monotonic() - started))
            return code
        elif kind == "deb-url":
            work = "/tmp/stol-debs"
            os.makedirs(work, exist_ok=True)
            deb_path = os.path.join(work, "app.deb")
            _download(source["url"], deb_path, source.get("sha256"), int(source.get("bytes", 0)),
                      on_progress, "Скачивание пакета")
            code = _run_apt(["apt-get", "-y", "--no-install-recommends", "install", os.path.abspath(deb_path)],
                            "Установка пакета", on_progress, log)
            if os.path.exists(deb_path):
                os.remove(deb_path)
            if on_progress:
                on_progress(format_progress("Установка", 100.0 if code == 0 else 0.0,
                                            "готово" if code == 0 else "ошибка установки",
                                            time.monotonic() - started))
            return code
        elif kind == "apt-repo":
            if "key_url" in source and "key_path" in source:
                os.makedirs(os.path.dirname(source["key_path"]), exist_ok=True)
                _download(source["key_url"], source["key_path"], None, 0, on_progress, "Ключ репозитория")
            if "list_content" in source and "list_path" in source:
                os.makedirs(os.path.dirname(source["list_path"]), exist_ok=True)
                with open(source["list_path"], "w", encoding="utf-8") as f:
                    f.write(source["list_content"])
            _run_apt(update_argv(), "Обновление списков", on_progress, log)
            code = _run_apt(["apt-get", "install", "-y", "--no-install-recommends", source.get("package", item["package"])],
                            "Установка пакета", on_progress, log)
            if on_progress:
                on_progress(format_progress("Установка", 100.0 if code == 0 else 0.0,
                                            "готово" if code == 0 else "ошибка apt",
                                            time.monotonic() - started))
            return code
        elif kind == "appimage-extract":
            _run_apt(["apt-get", "install", "-y", "--no-install-recommends", "zlib1g"],
                     "Проверка зависимостей", on_progress, log)
            work = "/tmp/stol-appimage"
            os.makedirs(work, exist_ok=True)
            appimage_path = os.path.join(work, "app.AppImage")
            _download(source["url"], appimage_path, source.get("sha256"), int(source.get("bytes", 0)),
                      on_progress, "Скачивание AppImage")
            target = source.get("target")
            os.makedirs(target, exist_ok=True)
            os.chmod(appimage_path, 0o755)
            proc = subprocess.run([appimage_path, "--appimage-extract"], cwd=work,
                                  stdout=log, stderr=subprocess.STDOUT, check=False)
            squashfs_root = os.path.join(work, "squashfs-root")
            if os.path.exists(squashfs_root):
                import shutil
                for root_dir, dirs, files in os.walk(squashfs_root):
                    rel = os.path.relpath(root_dir, squashfs_root)
                    dest_dir = os.path.join(target, rel) if rel != "." else target
                    os.makedirs(dest_dir, exist_ok=True)
                    for f in files:
                        src_f = os.path.join(root_dir, f)
                        dst_f = os.path.join(dest_dir, f)
                        shutil.copy2(src_f, dst_f)
                shutil.rmtree(squashfs_root, ignore_errors=True)

                # Установка значков в систему
                item_id = item.get("id", "")
                for candidate in (f"{item_id}.png", "icon.png", "resources/icon.png", f"{item.get('package', '')}.png"):
                    src_icon = os.path.join(target, candidate)
                    if os.path.exists(src_icon):
                        try:
                            os.makedirs("/usr/share/pixmaps", exist_ok=True)
                            os.makedirs("/usr/share/icons/hicolor/48x48/apps", exist_ok=True)
                            shutil.copy2(src_icon, f"/usr/share/pixmaps/{item_id}.png")
                            shutil.copy2(src_icon, f"/usr/share/icons/hicolor/48x48/apps/{item_id}.png")
                        except Exception:
                            pass
                        break

            if os.path.exists(appimage_path):
                os.remove(appimage_path)
            if on_progress:
                on_progress(format_progress("Установка", 100.0 if proc.returncode == 0 else 0.0,
                                            "готово" if proc.returncode == 0 else "ошибка распаковки",
                                            time.monotonic() - started))
            return proc.returncode
        else:
            raise BadDownload("неизвестный тип источника: %s" % kind)
    finally:
        log.close()


# ------------------------------------------------------------------ командная строка


def main(argv):
    if len(argv) < 3:
        sys.stderr.write(__doc__)
        return 2
    action, package = argv[1], argv[2]
    progress_path = log_path = None
    rest = argv[3:]
    while rest:
        key, rest = rest[0], rest[1:]
        if key == "--progress" and rest:
            progress_path, rest = rest[0], rest[1:]
        elif key == "--log" and rest:
            log_path, rest = rest[0], rest[1:]
        else:
            sys.stderr.write("непонятный ключ: %s\n" % key)
            return 2

    stream = open(progress_path, "w", encoding="utf-8") if progress_path else None

    def report(text):
        # Метка времени нужна проверке: без неё не доказать, что строка меняется
        # не реже раза в две секунды.
        line = "%.3f\t%s" % (time.time(), text)
        print(line, flush=True)
        if stream:
            stream.write(line + "\n")
            stream.flush()

    try:
        if action == "install":
            item = item_by_id(package)
            if item and source_of(item):
                return run_install_item(item, on_progress=report, log_path=log_path)
            return run_install(package, on_progress=report, log_path=log_path)
        if action == "remove":
            return run_remove(package, on_progress=report, log_path=log_path)
        if action == "installed":
            item = item_by_id(package)
            if item and source_of(item):
                return 0 if is_item_installed(item) else 1
            return 0 if is_installed(package) else 1
        if action in ("install-item", "installed-item"):
            item = item_by_id(package)
            if item is None:
                sys.stderr.write("в каталоге нет позиции %s\n" % package)
                return 4
            if action == "installed-item":
                return 0 if is_item_installed(item) else 1
            return run_install_item(item, on_progress=report, log_path=log_path)
        sys.stderr.write("неизвестное действие: %s\n" % action)
        return 2
    except UnsafePackageName as err:
        sys.stderr.write("%s\n" % err)
        return 3
    except (NotEnoughSpace, BadDownload, MissingOurNtdll) as err:
        report("Отказано: %s" % err)
        sys.stderr.write("%s\n" % err)
        return 5
    finally:
        if stream:
            stream.close()


if __name__ == "__main__":
    sys.exit(main(sys.argv))
