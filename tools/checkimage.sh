#!/usr/bin/env bash
#
# tools/checkimage.sh — проверяет собранный образ КодоЦех Стол.
#
# Проверка идёт по БЕЛОМУ списку. Рядом с образом лежит manifest.txt — перечень
# ожидаемых путей в /root и /home; всё, чего в перечне нет, — повод упасть. Чёрный
# список ловит только то, что кто-то заранее вспомнил; белый ловит и то, чего никто
# не ждал.
#
# Поверх белого списка — шесть правил, каждое падает само по себе:
#   2) непустые истории команд и журналы;
#   3) /etc/hosts и /etc/resolv.conf, отличные от эталонных рядом с образом;
#   4) любые каталоги .git и следы git;
#   5) файлы шести проприетарных программ (VS Code, Obsidian, Antigravity,
#      Antigravity 2.0, Claude Desktop, ZCode);
#   6) секреты и сессии по форме — по виду имени, каталога и содержимого, а не по
#      знанию имён конкретных программ;
#   7) мусор чужой файловой системы — файлы вида ._* и .DS_Store, которые натаскивает
#      сборка, проехавшая через macOS: пакетный менеджер спотыкается о них в своих
#      каталогах и сорит ими в выводе;
#   8) офис, которого нет, и значки ярлыков, которых не найти ни в одной теме
#      образа — снос набора значков Yaru ломает именно это и молча.
#
# Это доказательство по R05 (без личных данных), R06.1 (программы не залогинены),
# R12i.1 (проприетарного в образе нет, пакеты — из Debian) и R01.4 (офис на месте). Найденное значение не печатается никогда:
# называется файл и род находки.
#
# Использование:
#   bash tools/checkimage.sh [КАТАЛОГ]
#
# КАТАЛОГ — каталог образа от tools/mkimage.sh: manifest.txt, etc-hosts.expected,
# etc-resolv.conf.expected и rootfs.tar.zst (или уже развёрнутое дерево rootfs/).
# По умолчанию build/image, а если его нет — build/image-smoke.
#
# Коды возврата: 0 — чисто, 1 — есть находки, 2 — проверку провести не удалось.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
ok()   { printf '    \033[32mOK\033[0m  %s\n' "$*"; }
skip() { printf '    --  %s\n' "$*"; }
die()  { printf '\033[31mcheckimage: %s\033[0m\n' "$*" >&2; exit 2; }

usage() {
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
    exit 0
}

FOUND=0
# Род находки и путь. Значение не печатаем: если это ключ, он уедет в лог сборки.
found() { printf '    \033[31mНАЙДЕНО\033[0m  %s: %s\n' "$1" "$2"; FOUND=$((FOUND + 1)); }
allow() { printf '    \033[33mдопуск\033[0m  %s: %s\n' "$1" "$2"; }

DIR=""
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) usage ;;
        -*) die "неизвестный ключ: $1" ;;
        *)  DIR="$1" ;;
    esac
    shift
done

if [ -z "$DIR" ]; then
    if   [ -d "$REPO/build/image" ];       then DIR="$REPO/build/image"
    elif [ -d "$REPO/build/image-smoke" ]; then DIR="$REPO/build/image-smoke"
    else die "нет каталога образа — соберите bash tools/mkimage.sh или укажите каталог"
    fi
fi
[ -d "$DIR" ] || die "каталога образа нет: $DIR"
DIR="$(cd "$DIR" && pwd)"

for f in manifest.txt etc-hosts.expected etc-resolv.conf.expected; do
    [ -f "$DIR/$f" ] || die "в каталоге образа нет $f — без него белый список не проверить"
done

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
    else shasum -a 256 "$1" | cut -d' ' -f1; fi
}
md5_of() {
    if command -v md5sum >/dev/null 2>&1; then md5sum "$1" | cut -d' ' -f1
    else md5 -q "$1"; fi
}

TMP="$(mktemp -d "${TMPDIR:-/tmp}/checkimage.XXXXXX")"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

rel() { printf '%s' "${1#"$ROOT"}"; }

# ---------------------------------------------------------------- дерево образа

log "образ: $DIR"

if [ -d "$DIR/rootfs" ]; then
    ROOT="$DIR/rootfs"
    skip "дерево развёрнуто заранее: $ROOT"
else
    [ -f "$DIR/rootfs.tar.zst" ] || die "нет ни rootfs.tar.zst, ни развёрнутого rootfs/"
    command -v zstd >/dev/null || die "нет zstd — архив образа не распаковать"
    if [ -f "$DIR/rootfs.tar.zst.sha256" ]; then
        want="$(cut -d' ' -f1 < "$DIR/rootfs.tar.zst.sha256")"
        got="$(sha256_of "$DIR/rootfs.tar.zst")"
        [ "$want" = "$got" ] || die "архив не совпал со своей суммой: $got != $want"
        ok "архив совпал с rootfs.tar.zst.sha256"
    else
        skip "рядом нет rootfs.tar.zst.sha256 — целостность архива не проверена"
    fi
    ROOT="$TMP/rootfs"
    mkdir -p "$ROOT"
    # tar из libarchive (он же /usr/bin/tar на macOS) по дороге склеивает записи ._*
    # обратно в расширенные атрибуты файлов — и мусор чужой файловой системы
    # исчезает из распакованного дерева, будто его не было. Гасим это чтение, иначе
    # правило 7 на macOS никогда не покраснеет. GNU tar ключа --options не знает, но
    # он ничего и не склеивает.
    if tar --version 2>/dev/null | grep -qi bsdtar; then
        zstd -dc "$DIR/rootfs.tar.zst" \
            | tar --options '!mac-ext' -C "$ROOT" -xf - --no-same-owner \
            || die "архив образа не распаковался"
    else
        zstd -dc "$DIR/rootfs.tar.zst" | tar -C "$ROOT" -xf - --no-same-owner \
            || die "архив образа не распаковался"
    fi
    ok "распаковано в $ROOT"
fi

# Пути манифеста: без комментариев и пустых строк.
MANIFEST="$TMP/manifest-paths.txt"
# grep вернёт 1, если в манифесте одни комментарии: без || true pipefail оборвал бы
# проверку кодом 1, будто нашлись находки. Пустой манифест — это код 2, ниже.
{ grep -v -e '^[[:space:]]*#' -e '^[[:space:]]*$' "$DIR/manifest.txt" || true; } \
    | LC_ALL=C sort > "$MANIFEST"
[ -s "$MANIFEST" ] || die "manifest.txt пуст — белому списку не с чем сверять"

manifest_has() { LC_ALL=C grep -Fxq -- "$1" "$MANIFEST"; }

# Каталоги, где ищем секреты по содержимому: всё, кроме дистрибутивных /usr/share,
# /usr/lib и /usr/bin — там миллион файлов Debian и ни одного нашего.
scan_dirs() {
    local d out=""
    for d in root home etc opt srv usr/local var run tmp mnt media; do
        [ -d "$ROOT/$d" ] && out="$out $d"
    done
    printf '%s' "$out"
}

# Записанные dpkg conffiles: путь и md5 из /var/lib/dpkg/status. Неизменённый файл
# пакета Debian — не чей-то секрет; см. is_pkg_conffile.
CONFFILES="$TMP/conffiles.txt"
: > "$CONFFILES"
if [ -f "$ROOT/var/lib/dpkg/status" ]; then
    awk '/^Conffiles:/ { inb = 1; next }
         inb && /^ / { print $1 " " $2; next }
         { inb = 0 }' "$ROOT/var/lib/dpkg/status" > "$CONFFILES"
fi

# Файл записан пакетом как conffile и с тех пор не менялся. Допуск действует только
# для правила 6: chromium из Debian приносит /etc/chromium.d/apikeys с ключами,
# выданными Google Debian-у, и они шумят на любом поиске ключей по форме. Если файл
# тронут — md5 разойдётся и допуск не сработает.
is_pkg_conffile() {
    local want got
    want="$(awk -v p="$1" '$1 == p { print $2; exit }' "$CONFFILES")"
    [ -n "$want" ] || return 1
    got="$(md5_of "$ROOT$1")"
    [ "$want" = "$got" ]
}

# Обычные файлы пакетов записаны в /var/lib/dpkg/info/*.md5sums. Список склеиваем
# лениво: он нужен, только если что-то совпало с именем проприетарной программы.
MD5SUMS=""
ensure_md5sums() {
    [ -z "$MD5SUMS" ] || return 0
    MD5SUMS="$TMP/md5sums.txt"
    : > "$MD5SUMS"
    cat "$ROOT"/var/lib/dpkg/info/*.md5sums >> "$MD5SUMS" 2>/dev/null || true
}

# Файл записан пакетом Debian и с тех пор не менялся. Путь в md5sums — без ведущей
# косой черты. Не нашли записи или md5 разошёлся — допуска нет, это находка.
is_pkg_file() {
    local want got
    ensure_md5sums
    want="$(LC_ALL=C awk -v p="${1#/}" '$2 == p { print $1; exit }' "$MD5SUMS")"
    [ -n "$want" ] || return 1
    got="$(md5_of "$ROOT$1")"
    [ "$want" = "$got" ]
}

# Четыре байта с little-endian числом, начиная со смещения $2. Считаем по байтам,
# чтобы не зависеть от порядка байтов машины, на которой идёт проверка. NR == 1
# обязательно: od на macOS дописывает пустую строку, и без него в ответ уедут две.
le32_at() {
    od -A n -t u1 -j "$2" -N 4 "$1" 2>/dev/null \
        | awk 'NR == 1 { print $1 + $2 * 256 + $3 * 65536 + $4 * 16777216 }'
}

# Байткод в md5sums не записан: Debian компилирует его уже после установки пакета,
# сверить с dpkg нечего. Поэтому кэш допускается только привязкой к исходнику:
# рядом лежит .py, прошедший сверку с dpkg; файл начинается магией байткода CPython
# (два байта версии и CR LF); помечен как timestamp-based (флаги 0), иначе в
# заголовке не размер, а хэш; и записанный в заголовке размер исходника совпадает с
# фактическим размером проверенного .py. Не сошлось — находка.
is_cache_of_pkg_source() {
    local pyc="$ROOT$1" dir src crlf flags size actual
    dir="${1%/__pycache__/*}"
    src="$dir/$2"
    [ -f "$ROOT$src" ] || return 1
    is_pkg_file "$src" || return 1
    crlf="$(od -A n -t u1 -N 4 "$pyc" 2>/dev/null | awk 'NR == 1 { print $3 " " $4 }')"
    [ "$crlf" = "13 10" ] || return 1
    flags="$(le32_at "$pyc" 4)"
    [ "$flags" = "0" ] || return 1
    size="$(le32_at "$pyc" 12)"
    actual="$(wc -c < "$ROOT$src" | tr -d ' ')"
    [ "$size" = "$actual" ]
}

# ---------------------------------------------------------------- 1: белый список

tree_paths() {
    ( cd "$ROOT" && find root home -print 2>/dev/null | while IFS= read -r p; do
        if   [ -L "$p" ]; then printf '/%s\n' "$p"
        elif [ -d "$p" ]; then printf '/%s/\n' "$p"
        else printf '/%s\n' "$p"; fi
    done ) | LC_ALL=C sort
}

rule_whitelist() {
    log "1/8 белый список путей в /root и /home (R05.2)"
    local actual="$TMP/tree-paths.txt" before="$FOUND" p
    tree_paths > "$actual"
    while IFS= read -r p; do
        [ -n "$p" ] || continue
        found "лишний путь вне манифеста" "$p"
    done < <(LC_ALL=C comm -13 "$MANIFEST" "$actual")
    while IFS= read -r p; do
        [ -n "$p" ] || continue
        found "путь из манифеста в образе не найден" "$p"
    done < <(LC_ALL=C comm -23 "$MANIFEST" "$actual")
    [ "$FOUND" -gt "$before" ] \
        || ok "путей в /root и /home: $(wc -l < "$actual" | tr -d ' '), все из манифеста"
}

# ---------------------------------------------------------------- 2: истории и журналы

rule_history() {
    log "2/8 истории команд и журналы (R05.2)"
    local before="$FOUND" f
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        found "непустая история" "$(rel "$f")"
    done < <(find "$ROOT" -type f -size +0c \
        \( -name '.bash_history' -o -name '.sh_history' -o -name '.history' \
           -o -name '*_history' -o -name '.lesshst' -o -name '.viminfo' \
           -o -name '.wget-hsts' \) 2>/dev/null)
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        found "непустой журнал" "$(rel "$f")"
    done < <(find "$ROOT" -type f -size +0c \
        \( -path "$ROOT/var/log/*" -o -name '*.journal' \) 2>/dev/null)
    [ "$FOUND" -gt "$before" ] || ok "истории пусты, журналы пусты"
}

# ---------------------------------------------------------------- 3: hosts и resolv.conf

rule_etc() {
    log "3/8 /etc/hosts и /etc/resolv.conf против эталонов (R05.2)"
    local before="$FOUND" pair name exp
    for pair in "hosts:etc-hosts.expected" "resolv.conf:etc-resolv.conf.expected"; do
        name="${pair%%:*}"; exp="${pair##*:}"
        if [ ! -f "$ROOT/etc/$name" ]; then
            found "файла нет, а эталон есть" "/etc/$name"
        elif ! cmp -s "$ROOT/etc/$name" "$DIR/$exp"; then
            found "отличается от эталона $exp" "/etc/$name"
        fi
    done
    [ "$FOUND" -gt "$before" ] || ok "hosts и resolv.conf совпали с эталонами побайтово"
}

# ---------------------------------------------------------------- 4: следы git

rule_git() {
    log "4/8 каталоги .git и следы git (R05.2)"
    local before="$FOUND" f
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        if [ -d "$f" ]; then found "каталог git" "$(rel "$f")"
        else found "след git" "$(rel "$f")"; fi
    done < <(find "$ROOT" \( -name '.git' -o -name '.gitconfig' -o -name '.git-credentials' \) 2>/dev/null)
    [ "$FOUND" -gt "$before" ] || ok "репозиториев и настроек git в образе нет"
}

# ---------------------------------------------------------------- 5: проприетарное

prop_label() {
    case "$(printf '%s' "$1" | tr 'A-Z' 'a-z')" in
        *antigravity2*|*"antigravity 2"*)   printf 'Antigravity 2.0' ;;
        *antigravity*)                      printf 'Antigravity' ;;
        *obsidian*)                         printf 'Obsidian' ;;
        *zcode*)                            printf 'ZCode' ;;
        *claude*)                           printf 'Claude Desktop' ;;
        *)                                  printf 'VS Code' ;;
    esac
}

# Единственные законные совпадения, и ни одно не прощается просто по пути.
# Ярлыки стола — это .desktop, объявленные в манифесте: они указывают на программы,
# которые поставит мастер, а самих программ в образе нет.
# antigravity.py — пасхалка стандартной библиотеки CPython (открывает xkcd 353),
# приходит с libpython3-stdlib и к Antigravity отношения не имеет. Допуск даётся
# только по совпадению с записью dpkg: подложенный по тому же пути чужой файл
# обязан стать находкой.
prop_allowed() {
    case "$1" in
        */Desktop/*.desktop)
            manifest_has "$1" && return 0 ;;
        /usr/lib/python3*/antigravity.py)
            is_pkg_file "$1" && return 0 ;;
        /usr/lib/python3*/__pycache__/antigravity.cpython-*.pyc)
            is_cache_of_pkg_source "$1" antigravity.py && return 0 ;;
    esac
    return 1
}

rule_proprietary() {
    log "5/8 файлы шести проприетарных программ (R12i.1)"
    local before="$FOUND" launchers=0 f p line args=()
    local pats=( '*antigravity*' '*obsidian*' '*zcode*' '*claude*' '*vscode*' \
                 '*visual-studio-code*' '*code-insiders*' 'code.desktop' \
                 'code_*.deb' 'code-*.deb' )
    for p in "${pats[@]}"; do args+=( -o -iname "$p" ); done

    while IFS= read -r f; do
        [ -n "$f" ] || continue
        p="$(rel "$f")"
        if prop_allowed "$p"; then
            case "$p" in
                */Desktop/*.desktop) launchers=$((launchers + 1)) ;;
                *.pyc) allow "кэш байткода проверенной пасхалки stdlib" "$p" ;;
                *) allow "пасхалка stdlib Python, md5 сошёлся с записью dpkg" "$p" ;;
            esac
            continue
        fi
        found "$(prop_label "$p")" "$p"
    done < <(find "$ROOT" \( "${args[@]:1}" \) 2>/dev/null | LC_ALL=C sort)

    # Пути, по которым эти программы ставятся, — на случай переименованных файлов.
    while IFS= read -r p; do
        [ -n "$p" ] || continue
        [ -e "$ROOT$p" ] || continue
        found "$(prop_label "$p")" "$p"
    done <<'EOF'
/usr/share/code
/usr/lib/code
/usr/bin/code
/usr/local/bin/code
/usr/share/applications/code.desktop
/opt/Antigravity IDE
/opt/Antigravity-arm64
/opt/obsidian-app
/opt/zcode-app
/usr/bin/claude-desktop
/opt/claude-desktop
EOF

    # Пакеты образа приходят из apt, а apt ходит туда, куда указывает sources.list.
    # Значит «всё из Debian» доказывается источниками, а не перечислением 800 пакетов:
    # чужая строка в списке — единственный способ втащить в образ чужое по праву.
    if [ ! -f "$ROOT/etc/apt/sources.list" ]; then
        found "списка источников apt нет — откуда пакеты, доказать нечем" "/etc/apt/sources.list"
    else
        while IFS= read -r line; do
            case "$line" in
                ''|'#'*|*deb.debian.org*) ;;
                *) found "источник пакетов не из Debian" "/etc/apt/sources.list: ${line%% *} …" ;;
            esac
        done < "$ROOT/etc/apt/sources.list"
    fi
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        found "посторонний список источников apt" "$(rel "$f")"
    done < <(find "$ROOT/etc/apt/sources.list.d" -type f 2>/dev/null)

    [ "$FOUND" -gt "$before" ] \
        || ok "проприетарных программ нет, пакеты только из Debian; ярлыков мастера: $launchers из 6"
}

# ---------------------------------------------------------------- 6: секреты и сессии

# Форма, а не имена программ: так ловятся и те, о которых никто не подумал.
SECRET_PATTERNS=(
    '-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----'
    'sk-ant-[A-Za-z0-9_-]{16}'
    'sk-[A-Za-z0-9]{32}'
    'gh[pousr]_[A-Za-z0-9]{20}'
    'github_pat_[A-Za-z0-9_]{20}'
    'glpat-[A-Za-z0-9_-]{20}'
    'AKIA[0-9A-Z]{16}'
    'xox[abprs]-[A-Za-z0-9-]{10}'
    'AIza[0-9A-Za-z_-]{35}'
    'eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}'
    '[0-9]{8,10}:AA[A-Za-z0-9_-]{33}'
    $'(api[_-]?key|secret|token|password|passwd)[^A-Za-z0-9]{0,3}[:=][ \t]*[\'"][A-Za-z0-9/_+=.-]{16,}[\'"]'
)

secret_kind() {
    case "$1" in
        *'PRIVATE KEY'*)  printf 'закрытый ключ в файле' ;;
        *eyJ*)            printf 'токен вида JWT' ;;
        *':AA'*)          printf 'токен телеграм-бота' ;;
        *'api'*)          printf 'пара «имя = значение» с секретом' ;;
        *)                printf 'ключ провайдера' ;;
    esac
}

secret_found() { # $1 род, $2 путь внутри образа
    if is_pkg_conffile "$2"; then
        allow "неизменённый conffile пакета Debian, не наш секрет" "$2"
        return 0
    fi
    found "$1" "$2"
}

rule_secrets() {
    log "6/8 секреты и сессии по форме (R06.1)"
    local before="$FOUND" f p pat dirs

    while IFS= read -r f; do
        [ -n "$f" ] || continue
        secret_found "каталог учётных данных" "$(rel "$f")"
    done < <(find "$ROOT" -type d \
        \( -name '.ssh' -o -name '.gnupg' -o -name '.aws' -o -name '.docker' \
           -o -name '.kube' -o -name '.password-store' -o -name 'Local Storage' \
           -o -name 'Session Storage' -o -name 'IndexedDB' \) 2>/dev/null)

    while IFS= read -r f; do
        [ -n "$f" ] || continue
        secret_found "файл ключа, учётки или сессии" "$(rel "$f")"
    done < <(find "$ROOT" -type f \
        \( -name 'id_rsa*' -o -name 'id_dsa*' -o -name 'id_ecdsa*' -o -name 'id_ed25519*' \
           -o -name '*.p12' -o -name '*.pfx' -o -name '*.jks' -o -name '*.keystore' \
           -o -name '*.kdbx' -o -name '*.ovpn' -o -name '.netrc' -o -name '_netrc' \
           -o -name '.npmrc' -o -name '.pypirc' -o -name '.git-credentials' \
           -o -name '.htpasswd' -o -name '.rclone.conf' -o -name '.env' -o -name '.env.*' \
           -o -name '.Xauthority' \
           -o -name 'authorized_keys' -o -name 'known_hosts' -o -name 'credentials' \
           -o -name 'credentials.json' -o -name 'auth.json' -o -name 'token' \
           -o -name 'token.json' -o -name 'tokens.json' -o -name '*.token' \
           -o -name 'access_token*' -o -name 'refresh_token*' -o -name 'session.json' \
           -o -name 'sessions.json' -o -name '*.session' -o -name 'Cookies' \
           -o -name 'cookies.sqlite' -o -name 'Login Data' \) 2>/dev/null)

    dirs="$(scan_dirs)"
    if [ -n "$dirs" ]; then
        local grep_args=()
        for pat in "${SECRET_PATTERNS[@]}"; do grep_args+=( -e "$pat" ); done
        while IFS= read -r p; do
            [ -n "$p" ] || continue
            local kind='ключ провайдера'
            for pat in "${SECRET_PATTERNS[@]}"; do
                if LC_ALL=C grep -qIE -e "$pat" "$ROOT/$p" 2>/dev/null; then
                    kind="$(secret_kind "$pat")"; break
                fi
            done
            secret_found "$kind" "/$p"
        done < <( cd "$ROOT" && find $dirs -type f -size -1000k -print0 2>/dev/null \
            | xargs -0 env LC_ALL=C grep -lIE "${grep_args[@]}" 2>/dev/null || true )
    fi

    [ "$FOUND" -gt "$before" ] || ok "ключей, учёток и сессий по форме не нашлось"
}

# ---------------------------------------------------------------- 7: чужая файловая система

# Мусор macOS: AppleDouble-спутники ._ИМЯ и .DS_Store. Внутри образа Debian им взяться
# неоткуда — если они там есть, значит дерево собирали или перепаковывали через чужую
# файловую систему. Вреда от них не в весе: dpkg видит ._схема.gschema.xml рядом с
# настоящей и сорит этим в выводе (R06D.3).
rule_alien_litter() {
    log "7/8 мусор чужой файловой системы (R06D.3)"
    # Такой мусор приходит не поштучно, а десятками тысяч: называем первые двадцать,
    # остальные считаем одной строкой, чтобы отчёт остался читаемым.
    local before="$FOUND" f shown=0
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        if [ "$shown" -lt 20 ]; then
            found "мусор чужой файловой системы" "$(rel "$f")"
        else
            FOUND=$((FOUND + 1))
        fi
        shown=$((shown + 1))
    done < <(find "$ROOT" \( -name '._*' -o -name '.DS_Store' \) 2>/dev/null)
    if [ "$shown" -gt 20 ]; then
        printf '    \033[31mНАЙДЕНО\033[0m  и ещё таких же: %d\n' "$((shown - 20))"
    fi
    [ "$FOUND" -gt "$before" ] || ok "спутников ._* и .DS_Store в образе нет"
}


# ---------------------------------------------------------------- 8: офис и значки

# Ярлыки офиса, которые обязаны быть на столе. Base, Draw и Math столу не заказаны:
# Draw приезжает жёсткой зависимостью Impress, но своего ярлыка не получает.
OFFICE_ENTRIES="libreoffice-writer libreoffice-calc libreoffice-impress"

# Имена всех значков, лежащих в образе, — по одному разу и без расширения. Собираем
# списком, а не find-ом на каждый ярлык: обходов дерева было бы полтора десятка.
ICON_INDEX=""
ensure_icon_index() {
    [ -z "$ICON_INDEX" ] || return 0
    ICON_INDEX="$TMP/icon-names.txt"
    ( cd "$ROOT" && find usr/share/icons usr/share/pixmaps root/.local/share/icons \
        \( -type f -o -type l \) 2>/dev/null ) \
        | sed -E 's#.*/##; s#\.(png|svg|svgz|xpm|icon)$##' \
        | LC_ALL=C sort -u > "$ICON_INDEX"
}

icon_resolves() {
    case "$1" in
        /*) [ -e "$ROOT$1" ] ;;
        *)  ensure_icon_index; LC_ALL=C grep -Fxq -- "$1" "$ICON_INDEX" ;;
    esac
}

# Программа ярлыка — первое поле Exec, и дословно его брать нельзя по двум причинам.
# Путь в кавычках бывает с пробелами: «/opt/Antigravity IDE/…» разрезался бы по пробелу в
# несуществующий огрызок, и ярлык прощался бы по неверной причине. А ярлык ZCode
# начинается с `env APPDIR=…` — программой считался бы сам /usr/bin/env, который в
# образе есть, и с ярлыка потребовали бы значок программы, которой в образе нет.
entry_exec_program() {
    local line tok
    line="$(sed -n 's/^Exec=//p' "$1" | head -1)"
    while [ -n "$line" ]; do
        case "$line" in
            '"'*) line="${line#\"}"; printf '%s' "${line%%\"*}"; return ;;
        esac
        tok="${line%% *}"
        case "$tok" in
            env|*=*) ;;
            *) printf '%s' "$tok"; return ;;
        esac
        [ "$tok" != "$line" ] || return
        line="${line#* }"
    done
}

entry_icon() { sed -n 's/^Icon=//p' "$1" | head -1; }

program_in_image() {
    case "$1" in
        '') return 1 ;;
        /*) [ -e "$ROOT$1" ] ;;
        *)  [ -e "$ROOT/usr/bin/$1" ] || [ -e "$ROOT/bin/$1" ] || [ -e "$ROOT/usr/local/bin/$1" ] ;;
    esac
}

rule_office_icons() {
    log "8/8 офис на столе и значки ярлыков (R01.4, R11i.2)"
    local before="$FOUND" f name icon prog checked=0 wizard=0

    # Смоук-образ собирается на минимуме пакетов: ни офиса, ни тем значков в нём нет
    # по построению. Пропуск называется вслух — молча пройти он не может.
    if [ -f "$DIR/image.json" ] && grep -q '"version"[^,]*-smoke' "$DIR/image.json"; then
        skip "смоук-образ: офиса и тем значков в нём нет по построению"
        return 0
    fi

    [ -x "$ROOT/usr/bin/soffice" ] || found "офиса в образе нет" "/usr/bin/soffice"
    for name in $OFFICE_ENTRIES; do
        [ -f "$ROOT/root/Desktop/$name.desktop" ] \
            || found "ярлыка офиса на столе нет" "/root/Desktop/$name.desktop"
    done
    if [ -d "$ROOT/usr/share/icons/Yaru" ]; then
        found "набор значков Yaru не убран — 109 МБ веса" "/usr/share/icons/Yaru"
    fi

    # Значок ярлыка обязан находиться в теме, оставшейся в образе. Ярлыки мастера
    # (VS Code, Obsidian, Antigravity, Claude, ZCode) указывают на программы, которых
    # в образе нет и не должно быть: их значки приедут вместе с ними, спрашивать их
    # здесь не с кого. Признак — сама программа ярлыка, а не список имён.
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        icon="$(entry_icon "$f")"
        [ -n "$icon" ] || continue
        prog="$(entry_exec_program "$f")"
        if ! program_in_image "$prog"; then wizard=$((wizard + 1)); continue; fi
        checked=$((checked + 1))
        icon_resolves "$icon" \
            || found "значок «$icon» не найден ни в одной теме образа" "$(rel "$f")"
    done < <(find "$ROOT/root/Desktop" "$ROOT/root/.config/xfce4/panel" \
        -name '*.desktop' 2>/dev/null | LC_ALL=C sort)

    [ "$FOUND" -gt "$before" ] \
        || ok "офис на месте, значков проверено: $checked, ярлыков мастера отложено: $wizard"
}

# ----------------------------------------------------------------

rule_whitelist
rule_history
rule_etc
rule_git
rule_proprietary
rule_secrets
rule_alien_litter
rule_office_icons

if [ "$FOUND" -eq 0 ]; then
    printf '\n\033[32mЧИСТО\033[0m  %s — восемь правил прошли (R05, R06.1, R12i.1, R06D.3, R01.4)\n' "$DIR"
else
    printf '\n\033[31mНЕ ЧИСТО\033[0m  находок: %d — образ раздавать нельзя\n' "$FOUND"
    exit 1
fi
