#!/usr/bin/env python3
"""Путь щелчка на столе: где он и что с ним стало (R15D).

Жалоба звучит так: тап по ярлыку перестаёт открывать программу, при этом курсор
приезжает ровно в значок, а панель на тот же тап отвечает. Разбирать такое «на
глаз» нельзя: по снимку экрана не видно ни того, какие кнопки X-сервер считает
нажатыми, ни того, какое окно на самом деле лежит под курсором. Поэтому здесь
щелчок смотрят с той стороны, где он заканчивается, — в самом X-сервере.

Этот файл кладётся ВНУТРЬ образа и работает там: снаружи, с телефона, до сокета
X-сервера не дотянуться. Готовых `xinput` и `xdotool` в образе нет (и класть их
ради проверки незачем), поэтому протокол X11 говорится напрямую по сокету
``/tmp/.X11-unix/X1`` — авторизация берётся из ``~/.Xauthority``.

Четыре режима:

``state``  — что сейчас: точка курсора, МАСКА НАЖАТЫХ КНОПОК, окно под курсором и
             свободен ли указатель. Маска не ноль означает, что у сервера осталась
             нажатой кнопка: пока это так, указатель захвачен тем окном, в котором
             её нажали, и любой следующий щелчок уедет туда, а не под курсор.
             Захвачен ли указатель, проверяется честной попыткой ``GrabPointer``.

``trap``   — ловушка щелчков: прозрачное окно поверх всего в заданном месте, которое
             печатает время прихода каждого нажатия и отпускания по часам сервера.
             Ею меряют, доходят ли оба тапа двойного тапа и укладываются ли они в
             окно двойного щелчка XFCE.

``dclick`` — двойной щелчок по RFB с заданными зазором и смещением второго щелчка.
             Им меряют, где проходит граница двойного щелчка на столе: пара, вышедшая
             за окно времени или за окно смещения, до ярлыка не доходит и остаётся
             двумя одиночными щелчками — значок только выделяется. Это и есть жалоба
             «тап не открывает ярлык, а панель на такой же тап отвечает»: панели хватает
             одного щелчка, ярлыку нужны два, засчитанные парой.

``wedge``  — заклинить нарочно: второй клиент RFB нажимает кнопку и не отпускает её,
             держа связь. Сервер остаётся с нажатой кнопкой — ровно то состояние, в
             котором ярлык на столе перестаёт открываться, а панель продолжает
             работать. Нужен, чтобы проверка умела краснеть на этом дефекте, а не
             ждала его по случаю. Связь рвётся — сервер отпускает кнопку сам; отсюда
             и «лечит только перезапуск приложения».

Выход — строки ``КЛЮЧ=значение`` для оболочки.
"""

import os
import socket
import struct
import sys
import time

DISPLAY = 1
RFB_PORT = 5901

BUTTON_NAMES = {1: "левая", 2: "средняя", 3: "правая", 4: "колесо вверх", 5: "колесо вниз"}
GRAB_WORDS = {
    0: "свободен",
    1: "ЗАХВАЧЕН другим клиентом",
    2: "InvalidTime",
    3: "окно не показано",
    4: "заморожен",
}


def die(message):
    print("ОШИБКА=%s" % message)
    sys.exit(3)


# ------------------------------------------------------------------ X11 по сокету


def cookie(display):
    """MIT-MAGIC-COOKIE-1 для этого дисплея из ~/.Xauthority."""
    path = os.path.expanduser("~/.Xauthority")
    if not os.path.exists(path):
        return b"", b""
    blob = open(path, "rb").read()
    pos = 0

    def chunk():
        nonlocal pos
        size, = struct.unpack_from(">H", blob, pos)
        pos += 2
        value = blob[pos:pos + size]
        pos += size
        return value

    while pos < len(blob):
        pos += 2                       # семейство адреса
        chunk()                        # адрес
        number = chunk()
        name = chunk()
        data = chunk()
        if name == b"MIT-MAGIC-COOKIE-1" and number in (display, b""):
            return name, data
    return b"", b""


class Server:
    """Столько протокола X11, сколько нужно этой проверке, и ни строкой больше."""

    def __init__(self, display=DISPLAY):
        path = "/tmp/.X11-unix/X%d" % display
        if not os.path.exists(path):
            die("сокета X-сервера %s нет — стол не поднят" % path)
        self.sock = socket.socket(socket.AF_UNIX)
        self.sock.connect(path)
        name, data = cookie(str(display).encode())
        pad = lambda blob: blob + b"\0" * ((4 - len(blob) % 4) % 4)
        self.sock.sendall(struct.pack("<BBHHHH", 0x6C, 0, 11, 0, len(name), len(data))
                          + b"\0\0" + pad(name) + pad(data))
        head = self.read(8)
        extra, = struct.unpack_from("<H", head, 6)
        body = self.read(extra * 4)
        if head[0] != 1:
            die("X-сервер отказал в подключении: %r" % body[:head[1]])
        vendor, formats = struct.unpack_from("<HxxxB", body, 16)
        self.root, = struct.unpack_from("<I", body, 32 + ((vendor + 3) // 4) * 4 + formats * 8)
        self.ids, = struct.unpack_from("<I", body, 4)

    def read(self, size):
        blob = b""
        while len(blob) < size:
            piece = self.sock.recv(size - len(blob))
            if not piece:
                die("X-сервер закрыл связь посреди ответа")
            blob += piece
        return blob

    def ask(self, request):
        self.sock.sendall(request)
        answer = self.read(32)
        if answer[0] == 0:
            die("X-сервер вернул ошибку %d" % answer[1])
        extra, = struct.unpack_from("<I", answer, 4)
        return answer + (self.read(extra * 4) if extra else b"")

    def tell(self, request):
        self.sock.sendall(request)

    def pointer(self):
        answer = self.ask(struct.pack("<BBHI", 38, 0, 2, self.root))
        _, child, x, y, _, _, mask = struct.unpack_from("<IIhhhhH", answer, 8)
        return child, x, y, mask

    def window_under(self, x, y):
        """Самое глубокое окно в точке: до него и должен доехать щелчок."""
        window = self.root
        for _ in range(16):
            answer = self.ask(struct.pack("<BBHIIhh", 40, 0, 4, self.root, window, x, y))
            child, = struct.unpack_from("<I", answer, 8)
            if not child:
                return window
            window = child
        return window

    def atom(self, name):
        """Номер свойства по имени: без него _NET_WM_PID не спросить."""
        blob = name.encode()
        pad = blob + b"\0" * ((4 - len(blob) % 4) % 4)
        answer = self.ask(struct.pack("<BBHHH", 16, 0, 2 + len(pad) // 4, len(blob), 0) + pad)
        return struct.unpack_from("<I", answer, 8)[0]

    def prop(self, window, atom):
        """Свойство окна как байты; пусто, если свойства нет."""
        answer = self.ask(struct.pack("<BBHIIIII", 20, 0, 6, window, atom, 0, 0, 1024))
        length, = struct.unpack_from("<I", answer, 16)
        fmt = answer[1]
        return answer[32:32 + length * (fmt // 8 if fmt else 0)]

    def children(self):
        return self.kids(self.root)

    def shown(self, window):
        """Показано ли окно и перехватывает ли оно оконного управляющего."""
        answer = self.ask(struct.pack("<BBHI", 3, 0, 2, window))
        return answer[26] == 2, answer[27] == 1

    def box(self, window):
        answer = self.ask(struct.pack("<BBHI", 14, 0, 2, window))
        x, y, width, height = struct.unpack_from("<hhHH", answer, 12)
        return x, y, width, height

    def who(self, window, pid_atom):
        """Чьё окно: класс и номер процесса. Оконный управляющий заворачивает окна
        в свои рамки, и на самой рамке ни класса, ни процесса нет — их приходится
        искать на окне внутри неё."""
        queue, seen = [(window, 0)], 0
        while queue and seen < 40:
            candidate, depth = queue.pop(0)
            seen += 1
            klass = self.prop(candidate, 67).decode("utf-8", "replace").replace("\0", ".")
            blob = self.prop(candidate, pid_atom)
            pid = struct.unpack_from("<I", blob)[0] if len(blob) >= 4 else 0
            if klass or pid:
                return klass or "нет", pid
            if depth < 3:
                queue.extend((kid, depth + 1) for kid in self.kids(candidate))
        return "нет", 0

    def kids(self, window):
        answer = self.ask(struct.pack("<BBHI", 15, 0, 2, window))
        count, = struct.unpack_from("<H", answer, 16)
        return list(struct.unpack_from("<%dI" % count, answer, 32))

    def grab_state(self):
        """0 — указатель свободен; 1 — его держит другой клиент (или нажатая кнопка)."""
        answer = self.ask(struct.pack("<BBHIHBBIII", 26, 0, 6, self.root, 0, 1, 1, 0, 0, 0))
        if answer[1] == 0:
            self.tell(struct.pack("<BBHI", 27, 0, 2, 0))
        return answer[1]


def buttons_of(mask):
    return [number for number in range(1, 6) if mask & (1 << (7 + number))]


# ------------------------------------------------------------------ режимы


def state(_argv):
    x11 = Server()
    child, x, y, mask = x11.pointer()
    held = buttons_of(mask)
    print("КУРСОР_X=%d" % x)
    print("КУРСОР_Y=%d" % y)
    print("МАСКА=0x%04x" % mask)
    print("НАЖАТЫ=%s" % (",".join(BUTTON_NAMES.get(b, str(b)) for b in held) if held else "нет"))
    print("ОКНО=0x%x" % x11.window_under(x, y))
    print("ОКНО_ВЕРХНЕЕ=0x%x" % child)
    grab = x11.grab_state()
    print("ЗАХВАТ=%d" % grab)
    print("ЗАХВАТ_СЛОВОМ=%s" % GRAB_WORDS.get(grab, "?"))
    return 0


def trap(argv):
    if len(argv) != 5:
        die("trap ждёт X Y ШИРИНУ ВЫСОТУ СЕКУНДЫ")
    x0, y0, width, height, seconds = (int(argv[0]), int(argv[1]), int(argv[2]),
                                      int(argv[3]), float(argv[4]))
    x11 = Server()
    window = x11.ids | 1
    # InputOnly-окно поверх всего: оно невидимо и ничего не рисует, но щелчки в своём
    # прямоугольнике забирает себе — так видно, что именно доехало до сервера.
    values = struct.pack("<II", 1, (1 << 2) | (1 << 3))          # override-redirect, кнопки
    x11.tell(struct.pack("<BBHIIhhHHHHII", 1, 0, 10, window, x11.root,
                         x0, y0, width, height, 0, 2, 0, 0x0200 | 0x0800) + values)
    x11.tell(struct.pack("<BBHI", 8, 0, 2, window))              # показать
    x11.tell(struct.pack("<BBHIHHI", 12, 0, 5, window, 0x40, 0, 0))   # поднять наверх
    print("ЛОВУШКА=0x%x" % window)
    sys.stdout.flush()
    x11.sock.settimeout(seconds)
    until = time.time() + seconds
    while time.time() < until:
        try:
            event = x11.read(32)
        except socket.timeout:
            break
        code = event[0] & 0x7F
        if code in (4, 5):
            stamp, = struct.unpack_from("<I", event, 4)
            print("%s ВРЕМЯ=%d КНОПКА=%d" % ("НАЖАТИЕ" if code == 4 else "ОТПУСКАНИЕ",
                                             stamp, event[1]))
            sys.stdout.flush()
    print("ЛОВУШКА_СНЯТА=да")
    return 0


def rfb_link():
    """Клиент RFB на петле: рукопожатие 3.8 без проверки личности."""
    link = socket.create_connection(("127.0.0.1", RFB_PORT), 15)

    def read(size):
        blob = b""
        while len(blob) < size:
            piece = link.recv(size - len(blob))
            if not piece:
                die("стол закрыл связь RFB посреди рукопожатия")
            blob += piece
        return blob

    banner = read(12)
    if not banner.startswith(b"RFB "):
        die("на порту %d не RFB, а %r" % (RFB_PORT, banner))
    link.sendall(b"RFB 003.008\n")
    kinds = read(read(1)[0])
    if 1 not in kinds:
        die("стол требует проверку личности, а на петле её не бывает")
    link.sendall(bytes([1]))
    if struct.unpack(">I", read(4))[0] != 0:
        die("стол отказал в подключении")
    link.sendall(bytes([1]))                       # ClientInit: связь общая
    read(4)
    read(16)
    read(struct.unpack(">I", read(4))[0])
    return link, read


def dclick(argv):
    """Двойной щелчок пальцем: зазор между щелчками и смещение второго задаются.

    Мышь щёлкает дважды в одну точку и быстро, палец — нет. Здесь обе разницы
    выставляются числом, поэтому граница двойного щелчка меряется, а не угадывается.
    """
    if len(argv) not in (3, 4):
        die("dclick ждёт X Y ЗАЗОР_МС [СМЕЩЕНИЕ]")
    x, y, gap = int(argv[0]), int(argv[1]), int(argv[2])
    away = int(argv[3]) if len(argv) == 4 else 0
    link, _ = rfb_link()

    def press(px, py, mask):
        link.sendall(struct.pack(">BBHH", 5, mask, px, py))

    press(x, y, 1)
    press(x, y, 0)
    time.sleep(gap / 1000.0)
    press(x + away, y + away, 1)
    press(x + away, y + away, 0)
    time.sleep(0.5)
    link.close()
    print("ЩЕЛЧОК=двойной")
    print("ЗАЗОР_МС=%d" % gap)
    print("СМЕЩЕНИЕ=%d" % away)
    return 0


def wedge(argv):
    """Второй клиент RFB: нажать кнопку и не отпускать, пока держится связь."""
    if len(argv) != 4:
        die("wedge ждёт X Y НОМЕР_КНОПКИ СЕКУНДЫ")
    x, y, button, seconds = int(argv[0]), int(argv[1]), int(argv[2]), float(argv[3])
    link, _ = rfb_link()
    link.sendall(struct.pack(">BBHH", 5, 1 << (button - 1), x, y))
    print("ЗАКЛИНЕНО=%s" % BUTTON_NAMES.get(button, str(button)))
    print("ТОЧКА=%d,%d" % (x, y))
    sys.stdout.flush()
    time.sleep(seconds)
    print("ЗАКЛИНИВАНИЕ_СНЯТО=да")
    return 0


def watch(argv):
    """Кто держит указатель: сторожит захват и в его миг называет окна поимённо.

    Захват в X принадлежит КЛИЕНТУ, и спросить сервер «чей он» протоколом нельзя.
    Зато клиент, который забирает указатель, почти всегда одновременно показывает
    окно — меню, подсказку, значок перетаскивания. Поэтому в миг захвата снимается
    весь верхний ряд окон: показанные, с их классом, номером процесса и
    прямоугольником. Окно, которого не было секунду назад, и есть захватчик.
    """
    seconds = float(argv[0]) if argv else 60.0
    stepms = float(argv[1]) if len(argv) > 1 else 25.0
    x11 = Server()
    pid_atom = x11.atom("_NET_WM_PID")

    def busy():
        """Чем заняты части стола: состояние, точка сна в ядре и потраченные такты.
        Замерший в ожидании xfdesktop держит указатель замороженным — по одному
        только «захвачен» этого не видно, а по состоянию видно."""
        rows = []
        for pid_dir in sorted(os.listdir("/proc")):
            if not pid_dir.isdigit():
                continue
            try:
                comm = open("/proc/%s/comm" % pid_dir).read().strip()
                if comm not in ("xfdesktop", "thunar", "Thunar", "xfce4-panel", "Xtigervnc"):
                    continue
                blob = open("/proc/%s/stat" % pid_dir).read()
                parts = blob[blob.rindex(")") + 2:].split()
                wchan = open("/proc/%s/wchan" % pid_dir).read().strip()
            except OSError:
                continue
            rows.append("%s pid=%s состояние=%s ждёт=%s такты=%s/%s"
                        % (comm, pid_dir, parts[0], wchan or "нет", parts[11], parts[12]))
        return rows

    def ring():
        rows = []
        for window in x11.children():
            try:
                mapped, hijack = x11.shown(window)
                if not mapped:
                    continue
                klass, pid = x11.who(window, pid_atom)
                x, y, width, height = x11.box(window)
            except SystemExit:
                continue
            rows.append("0x%x класс=%s pid=%s место=%dx%d+%d+%d%s"
                        % (window, klass or "нет", pid or "нет", width, height, x, y,
                           " ПЕРЕХВАТ" if hijack else ""))
        return rows

    was_grab, was_mask, checked = None, None, 0.0
    began = time.time()
    while time.time() - began < seconds:
        now = time.time()
        _, x, y, mask = x11.pointer()
        # Нажатия сторожатся часто: пара щелчков живёт десятки миллисекунд, и на
        # редкой выборке её просто не видно — а без неё не отличить «щелчок не
        # доехал» от «доехал, но парой не засчитан».
        if mask != was_mask:
            if was_mask is not None:
                print("КНОПКА=%.3f МАСКА=0x%04x КУРСОР=%d,%d" % (now, mask, x, y))
                sys.stdout.flush()
            was_mask = mask
        if now - checked >= 0.25:
            checked = now
            grab = x11.grab_state()
            if grab != was_grab:
                print("СМЕНА=%.2f ЗАХВАТ=%d СЛОВОМ=%s КУРСОР=%d,%d МАСКА=0x%04x ОКНО=0x%x"
                      % (now, grab, GRAB_WORDS.get(grab, "?"), x, y, mask,
                         x11.window_under(x, y)))
                for row in ring():
                    print("    ОКНО %s" % row)
                for row in busy():
                    print("    ЧАСТЬ %s" % row)
                sys.stdout.flush()
                was_grab = grab
        time.sleep(stepms / 1000.0)
    print("СТОРОЖ_КОНЧИЛ=%s" % time.time())
    return 0


MODES = {"state": state, "trap": trap, "dclick": dclick, "wedge": wedge, "watch": watch}


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in MODES:
        die("нужен режим: %s" % ", ".join(sorted(MODES)))
    sys.exit(MODES[sys.argv[1]](sys.argv[2:]))


main()
