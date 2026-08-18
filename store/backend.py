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
"""

import json
import os
import re
import select
import shlex
import subprocess
import sys
import time

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
        f"{indent}awk '$2 == \"reinstreq\" {{ print $4 }}'"
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
            return run_install(package, on_progress=report, log_path=log_path)
        if action == "remove":
            return run_remove(package, on_progress=report, log_path=log_path)
        if action == "installed":
            return 0 if is_installed(package) else 1
        sys.stderr.write("неизвестное действие: %s\n" % action)
        return 2
    except UnsafePackageName as err:
        sys.stderr.write("%s\n" % err)
        return 3
    finally:
        if stream:
            stream.close()


if __name__ == "__main__":
    sys.exit(main(sys.argv))
