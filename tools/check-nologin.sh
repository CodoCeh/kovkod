#!/usr/bin/env bash
#
# tools/check-nologin.sh — проверка отсутствия залогиненных сессий и токенов (R06).
#
# Проверяет, что после сборки и работы мастера в каталогах пользователя (/root, /home)
# нет сохранённых профилей, куки, токенов авторизации и учётных записей.
#
# Использование:
#   bash tools/check-nologin.sh /путь/к/rootfs [каталог_пользователя]
#

set -euo pipefail

ROOTFS="${1:-}"
[ -n "$ROOTFS" ] && [ -d "$ROOTFS" ] || {
    echo "Использование: $0 /путь/к/rootfs [каталог_пользователя]" >&2
    exit 2
}

USER_DIR="${2:-}"

log()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
ok()   { printf '    \033[32mOK\033[0m  %s\n' "$*"; }
bad()  { printf '    \033[31mНЕТ\033[0m  %s\n' "$*"; }

ERRORS=0

# Пути к профилям и хранилищам сессий
SESSION_PATTERNS=(
    "Cookies"
    "Cookies-journal"
    "Login Data"
    "Login Data-journal"
    "Session Storage"
    "Sessions"
    "account.json"
    "accounts.json"
    "session.json"
    "token.json"
    "auth.json"
    ".netrc"
    ".git-credentials"
    ".npmrc"
    "credentials"
    "sync.json"
)

# Каталоги программ, где ищутся следы авторизации
APP_DIRS=(
    "root/.config/Code"
    "root/.config/Code - OSS"
    "root/.config/Claude"
    "root/.config/claude-desktop"
    "root/.config/obsidian"
    "root/.config/antigravity"
    "root/.config/antigravity2"
    "root/.config/zcode"
    "root/.config/gemini-app"
    "root/.config/chromium"
    "root/.vscode"
)

check_app_sessions() {
    local base="$1"
    for app in "${APP_DIRS[@]}"; do
        local target="$base/$app"
        [ -d "$target" ] || continue

        for pattern in "${SESSION_PATTERNS[@]}"; do
            while IFS= read -r match; do
                [ -n "$match" ] || continue
                bad "найден файл сессии/авторизации: ${match#$base/}"
                ERRORS=$((ERRORS + 1))
            done < <(find "$target" -name "$pattern" -type f 2>/dev/null || true)
        done

        # Проверка leveldb на сохранённые токены
        while IFS= read -r ldb; do
            [ -n "$ldb" ] || continue
            if grep -Eqi "(auth_token|access_token|session_token|bearer|password|refresh_token)" "$ldb" 2>/dev/null; then
                bad "в хранилище найден токен авторизации: ${ldb#$base/}"
                ERRORS=$((ERRORS + 1))
            fi
        done < <(find "$target" -path "*/Local Storage/leveldb/*.ldb" -type f 2>/dev/null || true)
    done
}

check_general_secrets() {
    local base="$1"
    local search_roots=("$base/root")
    if [ -d "$base/home" ]; then
        search_roots+=("$base/home")
    fi

    for root in "${search_roots[@]}"; do
        [ -d "$root" ] || continue

        # Закрытые ключи ssh/gnupg
        while IFS= read -r keyfile; do
            [ -n "$keyfile" ] || continue
            bad "найден закрытый ключ: ${keyfile#$base/}"
            ERRORS=$((ERRORS + 1))
        done < <(find "$root" -type f \( -name "id_rsa" -o -name "id_ed25519" -o -name "id_ecdsa" -o -name "*.sec" -o -name "secring.gpg" \) 2>/dev/null || true)

        # Конфиги с авторизацией
        for conf in ".git-credentials" ".netrc" ".docker/config.json"; do
            if [ -f "$root/$conf" ] && [ -s "$root/$conf" ]; then
                bad "найден непустой файл учётных данных: $conf"
                ERRORS=$((ERRORS + 1))
            fi
        done
    done
}

log "проверка отсутствия сессий и учётных данных (R06)"

if [ -n "$USER_DIR" ] && [ -d "$USER_DIR" ]; then
    check_app_sessions "$USER_DIR"
    check_general_secrets "$USER_DIR"
else
    check_app_sessions "$ROOTFS"
    check_general_secrets "$ROOTFS"
fi

if [ "$ERRORS" -gt 0 ]; then
    bad "найдено $ERRORS нарушений: сессии или токены не очищены"
    exit 1
fi

ok "сессий и сохранённых учётных данных не обнаружено (R06)"
exit 0
