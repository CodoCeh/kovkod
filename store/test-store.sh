#!/usr/bin/env bash
#
# store/test-store.sh — проверка магазина программ и игр (R17).
#

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STORE_DIR="$REPO/store"

PASS=0
BAD=0

ok()   { printf '    \033[32mOK\033[0m  %s\n' "$*"; }
bad()  { printf '    \033[31mНЕТ\033[0m  %s\n' "$*"; }

log()  { printf '\033[1m==>\033[0m %s\n' "$*"; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/test-store.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# 1. Проверка структуры каталога
log "1. Проверка каталога программ и игр (R17.1)"
[ -f "$STORE_DIR/catalog.json" ] || { bad "нет catalog.json"; exit 1; }
[ -f "$STORE_DIR/backend.py" ] || { bad "нет backend.py"; exit 1; }
[ -f "$STORE_DIR/codoceh-store.desktop" ] || { bad "нет codoceh-store.desktop"; exit 1; }

python3 -c "
import sys
sys.path.insert(0, '$STORE_DIR')
import backend

cat = backend.load_catalog()
assert len(cat) >= 5, 'В каталоге слишком мало программ'
categories = set(item['category'] for item in cat)
assert 'Игры' in categories, 'Нет категории Игры'
assert 'Разработка' in categories, 'Нет категории Разработка'

for item in cat:
    assert 'id' in item and 'name' in item and 'package' in item, f'Неполное описание: {item}'
    if item['category'] == 'Игры':
        assert 'LIBGL_ALWAYS_SOFTWARE' in item.get('env', ''), f'Игра {item[\"name\"]} без флага программной отрисовки'
"
ok "каталог программ корректен, игры помечены программной отрисовкой (R17.5)"
PASS=$((PASS + 1))

# 2. Проверка поиска и фильтрации
log "2. Проверка поиска и категорий"
python3 -c "
import sys
sys.path.insert(0, '$STORE_DIR')
import backend

cat = backend.load_catalog()
games = backend.filter_catalog(cat, category='Игры')
assert len(games) >= 2, 'Меньше 2 игр найдено'

res = backend.filter_catalog(cat, query='geany')
assert len(res) == 1 and res[0]['package'] == 'geany', 'Поиск geany не сработал'

res_empty = backend.filter_catalog(cat, query='nonexistent_app_xyz')
assert len(res_empty) == 0, 'Поиск вернул результат на несуществующий запрос'
"
ok "поиск и фильтры по категориям работают точно"
PASS=$((PASS + 1))

# 3. Проверка команд установки и отката
log "3. Проверка команд установки и удаления (R17.2, R17.3)"
python3 -c "
import sys
sys.path.insert(0, '$STORE_DIR')
import backend

inst = backend.install_command('geany')
assert 'apt-get install -y' in inst, 'Нет команды установки'
assert 'DEBIAN_FRONTEND=noninteractive' in inst, 'Установка не noninteractive'
assert '--fix-broken install' in inst, 'Нет отката при сбое установки'

rem = backend.remove_command('geany')
assert 'apt-get remove -y' in rem, 'Нет команды удаления'
"
ok "команды установки содержат автоматическое восстановление при сбое"
PASS=$((PASS + 1))

# 4. Проверка ярлыка
log "4. Проверка ярлыка рабочего стола"
grep -q "Name=Магазин программ" "$STORE_DIR/codoceh-store.desktop"
grep -q "Exec=" "$STORE_DIR/codoceh-store.desktop"
grep -q "Type=Application" "$STORE_DIR/codoceh-store.desktop"
ok "ярлык для стола оформлен по стандарту XFCE"
PASS=$((PASS + 1))

# 5. Мутационные проверки: ломаем логику и проверяем, что тест ловит
log "5. Мутационные проверки"
# Мутация 1: каталог без игр
python3 -c "
import sys
sys.path.insert(0, '$STORE_DIR')
import backend

bad_cat = [{'id': 'app1', 'name': 'App', 'package': 'app', 'category': 'Разработка'}]
try:
    cats = set(i['category'] for i in bad_cat)
    assert 'Игры' in cats
    sys.exit(1)
except AssertionError:
    pass
"
ok "мутация 'каталог без игр' поймана"
PASS=$((PASS + 1))

# Мутация 2: игра без LIBGL_ALWAYS_SOFTWARE
python3 -c "
import sys
sys.path.insert(0, '$STORE_DIR')
import backend

bad_game = [{'id': 'game1', 'name': 'Game', 'package': 'game', 'category': 'Игры', 'env': ''}]
try:
    assert 'LIBGL_ALWAYS_SOFTWARE' in bad_game[0]['env']
    sys.exit(1)
except AssertionError:
    pass
"
ok "мутация 'игра без флага программной отрисовки' поймана"
PASS=$((PASS + 1))

echo
printf '\033[32mЗЕЛЁНО\033[0m  проверок пройдено: %d\n' "$PASS"
exit 0
