#!/usr/bin/env bash
#
# tools/checkimage.sh — проверяет собранный образ КодоЦех Стол.
#
# Проверка идёт по БЕЛОМУ списку. Рядом с образом лежит manifest.txt — перечень
# ожидаемых путей в /root и /home; всё, чего в перечне нет, — повод упасть. Чёрный
# список ловит только то, что кто-то заранее вспомнил; белый ловит и то, чего никто
# не ждал.
#
# Поверх белого списка — пять правил, каждое падает само по себе:
#   2) непустые истории команд и журналы;
#   3) /etc/hosts и /etc/resolv.conf, отличные от эталонных рядом с образом;
#   4) любые каталоги .git и следы git;
#   5) файлы шести проприетарных программ (VS Code, Obsidian, Antigravity,
#      Antigravity 2.0, Claude Desktop, ZCode);
#   6) секреты и сессии по форме — по виду имени, каталога и содержимого, а не по
#      знанию имён конкретных программ.
#
# Это доказательство по R05 (без личных данных), R06.1 (программы не залогинены) и
# R12i.1 (проприетарного в образе нет). Найденное значение не печатается никогда:
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
    zstd -dc "$DIR/rootfs.tar.zst" | tar -C "$ROOT" -xf - --no-same-owner \
        || die "архив образа не распаковался"
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

# ---------------------------------------------------------------- 1: белый список

tree_paths() {
    ( cd "$ROOT" && find root home -print 2>/dev/null | while IFS= read -r p; do
        if   [ -L "$p" ]; then printf '/%s\n' "$p"
        elif [ -d "$p" ]; then printf '/%s/\n' "$p"
        else printf '/%s\n' "$p"; fi
    done ) | LC_ALL=C sort
}

rule_whitelist() {
    log "1/6 белый список путей в /root и /home (R05.2)"
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
    log "2/6 истории команд и журналы (R05.2)"
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
    log "3/6 /etc/hosts и /etc/resolv.conf против эталонов (R05.2)"
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
    log "4/6 каталоги .git и следы git (R05.2)"
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

# Единственные законные совпадения. Ярлыки стола — это .desktop из манифеста: они
# указывают на программы, которые поставит мастер, а самих программ в образе нет.
# antigravity.py — пасхалка стандартной библиотеки CPython (открывает xkcd 353),
# приходит с libpython3-stdlib и к Antigravity отношения не имеет.
prop_allowed() {
    case "$1" in
        */Desktop/*.desktop)
            manifest_has "$1" && return 0 ;;
        /usr/lib/python3*/antigravity.py|/usr/lib/python3*/__pycache__/antigravity.cpython-*.pyc)
            return 0 ;;
    esac
    return 1
}

rule_proprietary() {
    log "5/6 файлы шести проприетарных программ (R12i.1)"
    local before="$FOUND" launchers=0 f p args=()
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
                *) allow "файл стандартной библиотеки Python, не Antigravity" "$p" ;;
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

    [ "$FOUND" -gt "$before" ] \
        || ok "проприетарных программ нет; ярлыков мастера из манифеста: $launchers из 6"
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
    log "6/6 секреты и сессии по форме (R06.1)"
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

# ----------------------------------------------------------------

rule_whitelist
rule_history
rule_etc
rule_git
rule_proprietary
rule_secrets

if [ "$FOUND" -eq 0 ]; then
    printf '\n\033[32mЧИСТО\033[0m  %s — шесть правил прошли (R05, R06.1, R12i.1)\n' "$DIR"
else
    printf '\n\033[31mНЕ ЧИСТО\033[0m  находок: %d — образ раздавать нельзя\n' "$FOUND"
    exit 1
fi
