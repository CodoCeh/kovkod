#!/usr/bin/env bash
#
# tools/test-nologin.sh — тест проверки отсутствия сессий и токенов (R06).
#
# Доказывает, что check-nologin.sh пропускает чистые деревья и ловит подложенные
# сессии, токены, куки и ключи в профилях программ стола.
#

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="$REPO/tools/check-nologin.sh"
[ -f "$CHECK" ] || { echo "нет $CHECK" >&2; exit 2; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/test-nologin.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

PASS=0
BAD=0

ok()   { printf '    \033[32mOK\033[0m  %s\n' "$*"; }
bad()  { printf '    \033[31mНЕТ\033[0m  %s\n' "$*"; }

build_clean() {
    local d="$WORK/clean" r="$WORK/clean/rootfs"
    mkdir -p "$r/root/.config/xfce4" "$r/root/.config/gemini-app" "$r/root/Desktop" "$r/home"
    touch "$r/root/.bashrc"
    touch "$r/root/.profile"
}

run_case() {
    local name="$1" tree="$2" expect_code="$3"
    local log="$WORK/$name.log" code=0
    set +e
    bash "$CHECK" "$tree" >"$log" 2>&1
    code=$?
    set -e

    if [ "$code" -eq "$expect_code" ]; then
        ok "$name (код $code как и ожидалось)"
        PASS=$((PASS + 1))
    else
        bad "$name: ожидали код $expect_code, получили $code"
        cat "$log"
        BAD=$((BAD + 1))
    fi
}

echo "1. Чистое дерево образа проходит проверку"
build_clean
run_case "clean-tree" "$WORK/clean/rootfs" 0

echo "2. Мутационные проверки: залогиненные сессии обязаны валить проверку"

# VS Code sync / cookies
mkdir -p "$WORK/mut-vscode/rootfs/root/.config/Code/User"
touch "$WORK/mut-vscode/rootfs/root/.config/Code/Cookies"
run_case "mutation-vscode-cookies" "$WORK/mut-vscode/rootfs" 1

# Claude session
mkdir -p "$WORK/mut-claude/rootfs/root/.config/Claude"
touch "$WORK/mut-claude/rootfs/root/.config/Claude/session.json"
run_case "mutation-claude-session" "$WORK/mut-claude/rootfs" 1

# Gemini webapp cookies
mkdir -p "$WORK/mut-gemini/rootfs/root/.config/gemini-app/Default/Network"
touch "$WORK/mut-gemini/rootfs/root/.config/gemini-app/Default/Network/Cookies"
run_case "mutation-gemini-cookies" "$WORK/mut-gemini/rootfs" 1

# Obsidian auth
mkdir -p "$WORK/mut-obsidian/rootfs/root/.config/obsidian"
touch "$WORK/mut-obsidian/rootfs/root/.config/obsidian/auth.json"
run_case "mutation-obsidian-auth" "$WORK/mut-obsidian/rootfs" 1

# SSH secret key
mkdir -p "$WORK/mut-ssh/rootfs/root/.ssh"
printf 'FAKE PRIVATE KEY\n' > "$WORK/mut-ssh/rootfs/root/.ssh/id_ed25519"
run_case "mutation-ssh-key" "$WORK/mut-ssh/rootfs" 1

# Git credentials
mkdir -p "$WORK/mut-git/rootfs/root"
printf 'https://user:token@github.com\n' > "$WORK/mut-git/rootfs/root/.git-credentials"
run_case "mutation-git-credentials" "$WORK/mut-git/rootfs" 1

echo
if [ "$BAD" -gt 0 ]; then
    printf '\033[31mПРОВАЛЕНО\033[0m: сошлось %d, разошлось %d\n' "$PASS" "$BAD"
    exit 1
fi

printf '\033[32mЗЕЛЁНО\033[0m  проверок пройдено: %d\n' "$PASS"
exit 0
