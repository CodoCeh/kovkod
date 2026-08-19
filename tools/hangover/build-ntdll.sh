#!/usr/bin/env bash
# tools/hangover/build-ntdll.sh — своя сборка unix-части wine из Hangover (R04).
#
# Зачем. Android при targetSdk 29 и выше держит приложение в SELinux-домене
# untrusted_app, где запрещён execmod: страницу ОТОБРАЖЁННОГО ФАЙЛА, в которую
# писали, нельзя сделать исполняемой. Wine раскладывает PE-образ именно так —
# mmap секции из файла, правки, mprotect(+EXEC) — и падает:
#     err:virtual:map_image_into_view failed to set 60000020 protection ...
# Понижать targetSdk нельзя (Google Play с 31.08.2026 требует API 36).
#
# У самого wine есть готовый путь для носителей без права исполнения: при
# removable=TRUE функция map_file_into_view не делает mmap файла, а читает его
# через pread в БЕЗЫМЯННУЮ память вида. Безымянная исполняемая память приложению
# разрешена (execmem в appdomain). Правка ровно одна: в map_image_into_view
# removable ставится всегда. Цена названа в отчёте: страницы библиотек больше не
# делятся между процессами (память и время старта), секции RWX как не работали,
# так и не работают.
#
# Собирается ТОЛЬКО dlls/ntdll/ntdll.so — unix-часть, где и лежит загрузчик PE.
# Остальной стек берётся из релиза Hangover: перебирать весь wine с clang-mingw
# ради одного файла незачем, а ABI между ntdll.so и ntdll.dll держится тем, что
# исходник — тот же коммит, что у релиза.
#
# Где собирать: arm64-Linux с Debian 13 trixie (та же, что в образе). У нас это
# виртуалка lima «armdeb». Сборка долгая — пускать в nohup/screen.
#
#   limactl shell armdeb -- bash tools/hangover/build-ntdll.sh
#
# Результат: $OUT/ntdll.so (снятые отладочные символы) и ntdll.so.sha256.
set -euo pipefail

WINE_URL="${WINE_URL:-https://github.com/AndreRH/wine.git}"
WINE_REV="${WINE_REV:-2e8ad15d5b7b87ef0e1520bf9fec2ecfb31fd1b1}"   # подмодуль wine тега hangover-11.9
WORK="${WORK:-$HOME/hangover-build}"
OUT="${OUT:-$WORK/out}"
JOBS="${JOBS:-$(nproc)}"

say() { printf '\n==> %s\n' "$*"; }

mkdir -p "$WORK" "$OUT"

say "зависимости"
if [ ! -f "$WORK/.deps" ]; then
    sudo apt-get update -qq
    sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        build-essential flex bison git make pkg-config gettext \
        clang lld llvm python3 xz-utils \
        libfreetype-dev libfontconfig-dev libx11-dev libxext-dev \
        libunwind-dev libudev-dev libdbus-1-dev
    touch "$WORK/.deps"
fi

say "исходники wine ($WINE_REV)"
if [ ! -d "$WORK/wine/.git" ]; then
    git -C "$WORK" clone --filter=blob:none --no-checkout "$WINE_URL" wine
fi
git -C "$WORK/wine" fetch --filter=blob:none origin "$WINE_REV" 2>/dev/null || git -C "$WORK/wine" fetch origin
git -C "$WORK/wine" checkout -f "$WINE_REV"
git -C "$WORK/wine" checkout -- dlls/ntdll/unix/virtual.c

say "правка: PE-образы читаются в безымянную память"
python3 - "$WORK/wine/dlls/ntdll/unix/virtual.c" <<'PY'
import sys, re
path = sys.argv[1]
src = open(path, encoding='utf-8').read()
mark = 'const char codoceh_anon_pe_marker[]'
if mark in src:
    sys.exit(0)
# место: тело map_image_into_view, сразу перед отображением заголовка
anchor = '    /* map the header */'
i = src.index(anchor, src.index('map_image_into_view( struct file_view *view'))
patch = (
    '    /* CodoCeh: Android (untrusted_app, targetSdk >= 29) forbids execmod --- a page of a\n'
    '     * mapped FILE that has been written to cannot be made executable. Wine already has a\n'
    '     * path for media without exec permission: with removable=TRUE the image is pread into\n'
    '     * anonymous memory of the view, and anonymous exec memory is allowed to every app.\n'
    '     * Cost: library pages are no longer shared between processes. */\n'
    '    removable = TRUE;\n\n'
)
src = src[:i] + patch + src[i:]
src += '\n/* marker of our own build, tests/e2e/windows-apps.sh looks for it */\n'
src += 'const char codoceh_anon_pe_marker[] = "CodoCeh anon-PE loader";\n'
open(path, 'w', encoding='utf-8').write(src)
print('правка внесена')
PY
grep -n "removable = TRUE;" "$WORK/wine/dlls/ntdll/unix/virtual.c" | head -3

say "configure"
mkdir -p "$WORK/build"
if [ ! -f "$WORK/build/Makefile" ]; then
    (cd "$WORK/build" && ../wine/configure --prefix=/usr \
        --enable-archs=arm64ec,aarch64,i386 --with-mingw=clang \
        --disable-tests --without-x --without-freetype 2>&1 | tail -30)
fi

say "сборка ntdll.so (это надолго)"
make -C "$WORK/build" -j"$JOBS" dlls/ntdll/ntdll.so

say "готово"
cp "$WORK/build/dlls/ntdll/ntdll.so" "$OUT/ntdll.so"
strip --strip-debug "$OUT/ntdll.so" || true
strings -a "$OUT/ntdll.so" | grep -q "CodoCeh anon-PE loader" \
    || { echo "в собранном ntdll.so нет метки — это не наша сборка"; exit 1; }
sha256sum "$OUT/ntdll.so" | tee "$OUT/ntdll.so.sha256"
ls -l "$OUT/ntdll.so"
