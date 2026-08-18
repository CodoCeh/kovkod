#!/usr/bin/env bash
# docs/check-reports.sh — сторож отчётов владельцу (R04, R05i, R06i, R09i, R13i, R14i).
#
# Отчёт — такая же поставка, как код, и портится он так же тихо: выпала ссылка на
# первоисточник, ужался разбор вариантов до одного, пропал пункт из отложенного. Здесь
# всё это ловится механически, а не на честном слове.
#
# Прогон: bash docs/check-reports.sh
# Красное — ненулевой код возврата и строка, называющая, что именно сломалось.

set -u
export LC_ALL=en_US.UTF-8

DOCS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$DOCS/.." && pwd)"
IOS="$DOCS/ios.md"
LAUNCHER="$DOCS/launcher.md"
GAMES="$DOCS/games.md"

FAILED=0

bad() { printf 'КРАСНОЕ: %s\n' "$1" >&2; FAILED=1; }
ok()  { printf 'ok: %s\n' "$1"; }

# Ищет строку буквально (-F), чтобы кавычки и звёздочки разметки не превращались в regexp.
has() { grep -qF -- "$2" "$1"; }

need() { # need <файл> <строка> <что доказываем>
    if has "$1" "$2"; then ok "$3"; else bad "$3 — в $(basename "$1") нет строки «${2}»"; fi
}

# --- 0. Отчёты вообще на месте -----------------------------------------------

for f in "$IOS" "$LAUNCHER" "$GAMES"; do
    if [ ! -f "$f" ]; then
        bad "отчёта нет: ${f#$ROOT/}"
    fi
done
if [ "$FAILED" -ne 0 ]; then
    printf '\nОтчётов нет — остальное проверять не на чем.\n' >&2
    exit 1
fi
ok "все три отчёта на месте"

# --- 1. iOS: ссылки на первоисточники (R04.1) --------------------------------

need "$IOS" 'https://developer.apple.com/app-store/review/guidelines/' \
     'R04.1 ссылка на правила App Store'
need "$IOS" '2.5.2' \
     'R04.1 назван конкретный пункт правил'
need "$IOS" 'https://support.apple.com/guide/security/app-code-signing-process-sec7c917bf14/web' \
     'R04.1 ссылка на первоисточник про подпись кода'
need "$IOS" 'Mandatory code signing' \
     'R04.1 техническая причина процитирована, а не пересказана'

# --- 2. iOS: у каждой оценки скорости либо источник, либо пометка (R04.2) -----

for path in 'UTM SE' 'iSH' 'Клиент к столу'; do
    need "$IOS" "$path" "R04.2 путь «${path}» разобран"
done

# Данные таблицы: строки, начинающиеся с '|', кроме шапки и разделителя.
# В каждой обязана стоять ссылка (http) или честная пометка «числа не существует».
rows=0
naked=0
while IFS= read -r line; do
    case "$line" in
        '|'*) : ;;
        *) continue ;;
    esac
    # разделитель вида |---|---|
    if printf '%s' "$line" | grep -qE '^\|[[:space:]|:-]*$'; then continue; fi
    # шапка
    if printf '%s' "$line" | grep -qF 'Откуда число'; then continue; fi
    rows=$((rows + 1))
    if printf '%s' "$line" | grep -qF 'http'; then continue; fi
    if printf '%s' "$line" | grep -qF 'числа не существует'; then continue; fi
    naked=$((naked + 1))
    bad "R04.2 оценка скорости без источника и без пометки «числа не существует»: ${line:0:70}…"
done < "$IOS"

if [ "$rows" -lt 3 ]; then
    bad "R04.2 в таблице путей $rows строк, а путей должно быть не меньше трёх"
elif [ "$naked" -eq 0 ]; then
    ok "R04.2 все $rows оценки скорости с источником или с пометкой"
fi

# --- 3. iOS: кода по iOS в репозитории нет (R04.3) ---------------------------

swift_hits="$(find "$ROOT" -path "$ROOT/.git" -prune -o \
    \( -name '*.swift' -o -name '*.xcodeproj' -o -name '*.xcworkspace' \
       -o -name '*.pbxproj' -o -name 'Package.swift' \) -print 2>/dev/null)"
if [ -n "$swift_hits" ]; then
    bad "R04.3 в репозитории появился код по iOS:"
    printf '%s\n' "$swift_hits" >&2
else
    ok "R04.3 ни одного .swift и ни одного проекта Xcode"
fi

# --- 4. Лаунчер: не меньше трёх разобранных вариантов (R13i.1) ---------------

variants=$(grep -c '^### ' "$LAUNCHER")
verdicts=$(grep -c '^\*\*Вердикт:\*\*' "$LAUNCHER")
reasons=$(grep -c '^\*\*Причина:\*\*' "$LAUNCHER")

if [ "$variants" -lt 3 ]; then
    bad "R13i.1 разобрано вариантов: $variants, требуется не меньше трёх"
else
    ok "R13i.1 разобрано вариантов: $variants"
fi
if [ "$verdicts" -ne "$variants" ]; then
    bad "R13i.1 вердиктов $verdicts на $variants вариантов — у каждого варианта обязан быть вердикт"
else
    ok "R13i.1 у каждого варианта есть вердикт"
fi
if [ "$reasons" -ne "$variants" ]; then
    bad "R13i.1 причин $reasons на $variants вариантов — у каждого вердикта обязана быть причина"
else
    ok "R13i.1 у каждого вердикта есть причина"
fi

# Вердикт без первоисточника — мнение. Требуем ссылки на каталог пакетов.
deb_links=$(grep -oF 'https://packages.debian.org/trixie/' "$LAUNCHER" | wc -l | tr -d ' ')
if [ "$deb_links" -lt 3 ]; then
    bad "R13i.1 ссылок на каталог Debian: $deb_links — сборка под arm64 обязана подтверждаться первоисточником, а не памятью"
else
    ok "R13i.1 сборка под arm64 подтверждена каталогом Debian ($deb_links ссылки)"
fi

# --- 5. Лаунчер: выбор XFCE обоснован сравнением (R13i.2) --------------------

need "$LAUNCHER" 'Почему пока перестраиваем XFCE' \
     'R13i.2 выбор в пользу XFCE сравнён с найденными вариантами'

# --- 6. Лаунчер: годная находка вынесена владельцу, а не решена молча (R13i.3)

if has "$LAUNCHER" 'проходит все три условия'; then
    need "$LAUNCHER" 'Вопрос владельцу' \
         'R13i.3 годный вариант вынесен владельцу вопросом'
    need "$LAUNCHER" 'цена перехода' \
         'R13i.3 названа цена перехода'
else
    ok "R13i.3 годных вариантов не заявлено — выносить нечего"
fi

# --- 7. Отложенное: все шесть пунктов на месте (R14i.1) ----------------------

if ! has "$LAUNCHER" '## Отложенное'; then
    bad "R14i.1 в отчёте нет раздела отложенного"
else
    ok "R14i.1 раздел отложенного на месте"
    deferred="$(mktemp)"
    awk '/^## Отложенное/{on=1} on' "$LAUNCHER" > "$deferred"
    for item in \
        'Клавиша Win' \
        'Прозрачность и размытие' \
        'Превью окон' \
        'Snap Assist' \
        'Центр уведомлений' \
        'Значки звука, батареи и сети'
    do
        if grep -qF -- "$item" "$deferred"; then
            ok "R14i.1 отложено с причиной: $item"
        else
            bad "R14i.1 в разделе отложенного пропал пункт «${item}»"
        fi
    done
    # Причина у каждого пункта: шесть штук «Причина:» внутри раздела.
    why=$(grep -cF 'Причина:' "$deferred")
    if [ "$why" -lt 6 ]; then
        bad "R14i.1 причин в отложенном: $why, а пунктов шесть — пункт без причины выглядит забытым"
    else
        ok "R14i.1 у каждого отложенного пункта названа причина"
    fi
    rm -f "$deferred"
fi

# --- 8. Игры: ответ про windows держится на замере (R09i) --------------------

# Число обязано быть добытым, а не названным: в отчёте стоит команда замера и
# артефакты, которые она пишет. Без этого «4,4 кадра» — просто цифра в тексте.
need "$GAMES" 'bash tests/e2e/render-ceiling.sh' \
     'R09i.1 названа команда, которой снято число'
for tag in 'render-ceiling-35-stol_phone_api35.json' 'render-ceiling-30-stol_phone_api30.json'; do
    need "$GAMES" "$tag" "R09i.1 назван артефакт замера: $tag"
done

# Решение из числа: раздел есть, и в нём стоит само число, а не одни слова.
if ! has "$GAMES" '## Решение'; then
    bad "R09i.2 в отчёте нет раздела с решением — число без вывода ничего не закрывает"
else
    decision="$(mktemp)"
    awk '/^## Решение/{on=1} on' "$GAMES" > "$decision"
    if grep -qE '[0-9]+,[0-9]+ кадра' "$decision"; then
        ok "R09i.2 решение принято по измеренному числу"
    else
        bad "R09i.2 в разделе решения нет измеренного числа — вывод повис в воздухе"
    fi
    rm -f "$decision"
fi

# Каждая оценка скорости — либо с указанием, откуда число, либо с пометкой.
# Строка таблицы без того и другого и есть то самое обещание сверх измеренного.
rows=0
naked=0
while IFS= read -r line; do
    case "$line" in
        '|'*) : ;;
        *) continue ;;
    esac
    if printf '%s' "$line" | grep -qE '^\|[[:space:]|:-]*$'; then continue; fi
    if printf '%s' "$line" | grep -qF 'Откуда число'; then continue; fi
    rows=$((rows + 1))
    if printf '%s' "$line" | grep -qF 'render-ceiling'; then continue; fi
    if printf '%s' "$line" | grep -qF 'не проверялось'; then continue; fi
    naked=$((naked + 1))
    bad "R09i.3 оценка скорости без замера и без пометки «не проверялось»: ${line:0:70}…"
done < "$GAMES"

if [ "$rows" -lt 2 ]; then
    bad "R09i.3 в таблице замеров $rows строк — число снималось не меньше чем на двух версиях Android"
elif [ "$naked" -eq 0 ]; then
    ok "R09i.3 все $rows оценки скорости с замером или с пометкой «не проверялось»"
fi

# --- 9. Игры: список в отчёте совпадает с каталогом магазина (R05.1i) --------

# Документ уже отставал от каталога: в нём стояли две игры, а в магазине лежало шесть.
# Сверка механическая, чтобы это не повторилось молча.
CATALOG="$ROOT/store/catalog.json"
if [ ! -f "$CATALOG" ]; then
    bad "R05.1i каталога магазина нет: ${CATALOG#$ROOT/} — список игр сверять не с чем"
else
    cat_games="$(python3 -c '
import json, sys
items = json.load(open(sys.argv[1], encoding="utf-8"))
print("\n".join(sorted(i["package"] for i in items if i.get("category") == "Игры")))
' "$CATALOG")" || cat_games=""

    # В отчёте игры перечислены строками вида «- **Имя** (`пакет`) — …» в своём разделе.
    doc_games="$(awk '/^## Что вместо этого лежит в магазине/{on=1; next} on && /^## /{on=0} on' "$GAMES" \
        | grep -oE '^- \*\*[^*]+\*\* \(`[a-z0-9.+-]+`\)' \
        | grep -oE '`[a-z0-9.+-]+`' | tr -d '`' | sort)"

    if [ -z "$cat_games" ]; then
        bad "R05.1i в каталоге магазина нет ни одной игры — сверять нечего, а раздел «Игры» пропасть не мог"
    elif [ -z "$doc_games" ]; then
        bad "R05.1i в games.md не найден список игр — нужен раздел «Что вместо этого лежит в магазине» со строками «- **Имя** (\`пакет\`) — …»"
    else
        missing="$(comm -23 <(printf '%s\n' "$cat_games") <(printf '%s\n' "$doc_games"))"
        extra="$(comm -13 <(printf '%s\n' "$cat_games") <(printf '%s\n' "$doc_games"))"
        for p in ${missing}; do
            bad "R05.1i игра ${p} есть в каталоге магазина, а в games.md её нет — документ отстал от состава"
        done
        for p in ${extra}; do
            bad "R05.1i игра ${p} названа в games.md, а в каталоге магазина её нет — документ обещает то, чего не поставят"
        done
        if [ -z "$missing" ] && [ -z "$extra" ]; then
            ok "R05.1i список игр в games.md совпадает с каталогом магазина ($(printf '%s\n' "$cat_games" | wc -l | tr -d ' ') шт.)"
        fi
    fi
fi

# --- 10. Кэш питона не под git (R06.1i) --------------------------------------

# Скомпилированный кэш — выход интерпретатора, а не исходник: пока он лежал под git,
# он менялся от каждого прогона и засорял каждый коммит.
if ! git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
    ok "R06.1i репозитория git нет — проверять индекс не на чем"
else
    pyc="$(git -C "$ROOT" ls-files | grep -E '\.pyc$|__pycache__' || true)"
    if [ -n "$pyc" ]; then
        bad "R06.1i под git лежит кэш питона:"
        printf '%s\n' "$pyc" >&2
    else
        ok "R06.1i под git ни одного .pyc и ни одного __pycache__"
    fi
    if git -C "$ROOT" check-ignore -q store/__pycache__/x.pyc; then
        ok "R06.1i кэш закрыт правилом в .gitignore"
    else
        bad "R06.1i .gitignore не закрывает __pycache__ — кэш вернётся под git первым же add"
    fi
fi

# --- Итог --------------------------------------------------------------------

printf '\n'
if [ "$FAILED" -ne 0 ]; then
    printf 'ОТЧЁТЫ НЕ ПРИНЯТЫ — смотри строки «КРАСНОЕ» выше.\n' >&2
    exit 1
fi
printf 'Отчёты на месте и сторожатся: iOS, лаунчер Windows, отложенное, windows-игры,\nсостав игр магазина, кэш питона вне git.\n'
exit 0
