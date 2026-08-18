#!/usr/bin/env bash
#
# store/test-mutations.sh — прогон рычагов порчи магазина.
#
# Проверка, которая не умеет краснеть, ничего не доказывает. Здесь по очереди
# ломается по одному кирпичу, и каждый раз store/test-store.sh обязан вернуть
# ненулевой код. Зелёное здесь означает ровно одно: проверка магазина
# чувствительна ко всем четырём поломкам, а не проходит по инерции.
#
# Рычаги:
#   stub-install   установка подменена заглушкой — пакета не появится;
#   stub-repair    восстановление после обрыва подменено заглушкой;
#   bad-catalog    в каталог подложена игра на GTK4 — правило отбора обязано отказать;
#   no-autostart   стол не поднимать (и погасить оставшийся) — ставить некуда.
#
# Рычаг no-autostart идёт последним: он гасит стол, и следующему прогону
# пришлось бы поднимать его заново — полторы минуты на пустом месте.
#
# Прогоны с мутацией намеренно урезаны (одна игра вместо всех, окно живёт три
# секунды вместо пятнадцати): доказывается чувствительность проверки, а не
# каталог — каталог целиком доказывает сам test-store.sh.
#
# Переменные окружения: STOL_MUT_GAME (игра для урезанных прогонов),
# STOL_MUT_ALIVE (сколько секунд держать окно), плюс всё, что понимает
# test-store.sh.

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STORE_DIR="$REPO/store"
ART="$STORE_DIR/artifacts"
MUT_GAME="${STOL_MUT_GAME:-hex-a-hop}"
MUT_ALIVE="${STOL_MUT_ALIVE:-3}"

mkdir -p "$ART"

PASS=0
FAIL=0
ok()  { PASS=$((PASS + 1)); printf '    \033[32mОК\033[0m    %s\n' "$*"; }
bad() { FAIL=$((FAIL + 1)); printf '    \033[31mПЛОХО\033[0m %s\n' "$*"; }
log() { printf '\n\033[1m%s\033[0m\n' "$*"; }

# Рычаг → переменные окружения для test-store.sh. Ждать от каждого одного и того
# же нельзя: рычаг стола валит прогон до всякой установки, остальные — внутри.
lever_env() {
    case "$1" in
        stub-install) printf 'STOL_STORE_MUTATION=stub-install' ;;
        stub-repair)  printf 'STOL_STORE_MUTATION=stub-repair' ;;
        bad-catalog)  printf 'STOL_STORE_MUTATION=bad-catalog' ;;
        no-autostart) printf 'STOL_STORE_NO_AUTOSTART=1' ;;
    esac
}

# Что обязано прозвучать в выводе. Без этого «красное» доказывало бы только то,
# что прогон упал — а упасть он мог и по своей причине, мимо рычага.
lever_grep() {
    case "$1" in
        stub-install) printf 'не поставил|неустановленным' ;;
        stub-repair)  printf 'после восстановления|остался сломанным' ;;
        bad-catalog)  printf 'правило отбора' ;;
        no-autostart) printf 'ставить некуда|стол не погас' ;;
    esac
}

run_lever() {
    local lever="$1"
    local out="$ART/mutation-$lever.log"
    log "Рычаг $lever"
    printf '    журнал: %s\n' "$out"

    env $(lever_env "$lever") \
        STOL_STORE_GAME="$MUT_GAME" \
        STOL_STORE_ALIVE="$MUT_ALIVE" \
        bash "$STORE_DIR/test-store.sh" > "$out" 2>&1
    local code=$?

    if [ "$code" -eq 0 ]; then
        bad "рычаг $lever не сработал: проверка вернула 0 там, где обязана краснеть"
        tail -5 "$out" | sed 's/^/        /'
        return
    fi
    if ! grep -Eqi "$(lever_grep "$lever")" "$out"; then
        bad "рычаг $lever уронил прогон (код $code), но не той причиной — в выводе нет ожидаемого"
        tail -5 "$out" | sed 's/^/        /'
        return
    fi
    ok "рычаг $lever дал красное (код $code): $(grep -Eiom1 "$(lever_grep "$lever")" "$out" | head -1)"
}

log "Рычаги порчи: каждый обязан дать красное"
printf '    урезанный прогон: игра %s, окно живёт %s с\n' "$MUT_GAME" "$MUT_ALIVE"

for lever in stub-install stub-repair bad-catalog no-autostart; do
    run_lever "$lever"
done

# Рычаги ломают систему нарочно: stub-repair оставляет пакет полуустановленным.
# Убирать за собой обязан тот, кто ломал, — иначе следующий прогон начинается с
# чужой поломки. Чиним тем же, чем чинит магазин, и требуем зелёный apt-get check.
log "Приборка: то, что ломали нарочно, возвращаем в строй"
VM="${STOL_LIMA_VM:-ceho}"
WORK_VM="${STOL_STORE_WORK:-/var/tmp/stol-store}"
ROOTFS="${STOL_ROOTFS:-$WORK_VM/rootfs}"
{
    printf '#!/bin/bash\ncd /opt/stol/store || exit 2\n'
    printf 'python3 -c "import backend, sys; sys.stdout.write(backend.repair_command(%s))" > /tmp/mut-repair.sh\n' "''"
    printf 'bash /tmp/mut-repair.sh >/tmp/mut-repair.log 2>&1\napt-get check\n'
} | limactl shell "$VM" -- sudo tee "$ROOTFS/tmp/mut-cleanup.sh" >/dev/null 2>&1
if limactl shell "$VM" -- sudo "$WORK_VM/login.sh" "$ROOTFS" /tmp/mut-cleanup.sh >/dev/null 2>&1; then
    ok "пакетный менеджер в образе снова здоров — следующий прогон начинается с чистого"
else
    bad "после рычагов пакетный менеджер остался сломанным — восстановление магазина не справилось"
fi

log "Итог"
printf '    пройдено: %d, провалено: %d\n' "$PASS" "$FAIL"
if [ "$FAIL" -eq 0 ]; then
    printf '\n\033[32mЗЕЛЁНО\033[0m  все рычаги порчи дали красное — проверка магазина умеет краснеть\n'
    exit 0
fi
printf '\n\033[31mКРАСНО\033[0m  рычагов промолчало: %d — проверка магазина ничего не доказывает\n' "$FAIL"
exit 1
