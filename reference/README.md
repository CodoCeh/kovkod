# Эталон стола

Слепок живого стола КодоЦеха с Galaxy Fold (SM-F971B). Снят один раз и закоммичен;
сверка с ним — доказательство по **R07** (тот же фон) и **R08** (та же тема).

Это образец для сверки, а не источник образа: чистый образ собирается с нуля скриптом,
rootfs телефона не копируется.

## Что в дампе

| Файл | Что это |
|---|---|
| `wallpaper.png` | сами обои, 1600×1000 PNG, лежат в образе как `/usr/share/backgrounds/codoceh/wallpaper-codoceh.png` |
| `wallpaper.sha256` | сумма обоев в формате `sha256sum`, по ней сверяется R07.1 |
| `xfconf/<канал>.txt` | вывод `xfconf-query -c <канал> -l -v` по четырём каналам: `xsettings`, `xfwm4`, `xfce4-desktop`, `xfce4-panel` |
| `desktop-entries/*.desktop` | семь ярлыков со стола (`/root/Desktop`), содержимым |
| `screenshot.png` | снимок стола 1600×1000, `import -window root` |

Что зафиксировано по существу: обои
`/usr/share/backgrounds/codoceh/wallpaper-codoceh.png` со стилем `image-style 5` (растянуть)
и настройки стола, которые сборка раскладывает в образ.

**Дампы — источник вида, а не только слепок.** Снят слепок был с телефона (Yaru, две
панели, четыре рабочих места), но продукту заказан вид Windows 10-11, и правится он
здесь же — в дампах, а не руками в XML. Сегодня в них: тема `Arc`, шрифт `Noto Sans 10`
со сглаживанием, одна панель снизу во всю ширину (кнопка «Пуск» whiskermenu, закреплённые
значки, список окон, распорный разделитель, трей, часы), кнопки окна справа (`|HMC`),
одно рабочее место. Проверяется это обратным чтением из собранного образа —
`bash tests/e2e/windows-look.sh`.

## Личных данных в дампе нет

Перед укладкой каждый файл прочитан глазами. Внутри остались только пути устройства стола
(`/opt/...`, `/root/Desktop/...`, `/usr/share/...`) — это и есть предмет сверки. Токенов,
ключей, имён аккаунтов и личных ссылок не найдено; заглушек ставить не пришлось.
Ровно это же перепроверяет `verify-dump.sh` при каждом прогоне.

## Проверка

```
bash reference/verify-dump.sh
```

Скрипт **не ходит на телефон** — он проверяет сам дамп: все артефакты на месте и непустые,
сумма обоев сходится, в каждом из четырёх каналов есть значения, состав ярлыков совпадает
со спекой, и ни в одном файле нет строк, похожих на токен или ключ.

## Как переснять

Стол живёт на телефоне, подключённом по USB; вход — Termux по SSH.

```bash
adb forward tcp:8022 tcp:8022
ssh -p 8022 u0_a363@127.0.0.1
```

Если SSH не отвечает, `sshd` в Termux не запущен: вывести Termux на передний план
(`adb shell am start -n com.termux/.app.TermuxActivity`), убедиться, что он в фокусе
(`adb shell dumpsys activity activities | grep topResumedActivity`), и напечатать в него
`adb shell input text 'sshd'` + `adb shell input keyevent 66`.

Стол поднимается из Termux (не из proot):

```bash
bash ~/start-gui.sh
```

Готовность — `DISPLAY=:1 wmctrl -l` должен показать `xfce4-panel` и `Desktop`.
`xfconf-query` без запущенной сессии молчит, поэтому сначала стол, потом съём.

Дальше — внутрь Debian:

```bash
proot-distro login debian --shared-tmp -- bash -lc "<команда>"
```

### Две грабли, на которые тут наступаешь

**Первая: `xfconf-query` не достучится до шины живой сессии.** `dbus-daemon` пускает
только того, чей uid по `SO_PEERCRED` совпадает с заявленным, — то есть реальный
андроидный uid (`10363`). А `proot-distro login` заходит с `--change-id=0:0`, и libdbus
честно представляется нулём. Получаешь `Failed to init libxfconf: The connection is closed`.

Обход, которым снят этот дамп: своя сессия шины поверх тех же сохранённых каналов —

```bash
dbus-run-session -- xfconf-query -c xsettings -l -v
```

`xfconfd` поднимается свой и читает те же
`/root/.config/xfce4/xfconf/xfce-perchannel-xml/*.xml`, что и живая сессия. Значения
настоящие, формат — родного `xfconf-query`. Читающие запросы XML не трогают: sha256
каталога до и после снятия совпали, это проверялось.

**Вторая: часть бинарников не запускается.** `xfconf-query`, `wmctrl`, `import` падают с
`cannot execute: required file not found`. Лечится явным вызовом через загрузчик:

```bash
/lib/ld-linux-aarch64.so.1 /usr/bin/xfconf-query -c xsettings -l -v
```

Ещё до кучи: `LD_LIBRARY_PATH` из Termux протекает внутрь proot и уводит загрузчик в
бионический `libc.so`. Перед съёмом — `unset LD_LIBRARY_PATH`.

### Сами команды съёма

```bash
# каналы
for c in xsettings xfwm4 xfce4-desktop xfce4-panel; do
  dbus-run-session -- xfconf-query -c "$c" -l -v > "$c.txt"
done

# обои
sha256sum /usr/share/backgrounds/codoceh/wallpaper-codoceh.png

# ярлыки
tar -C /root/Desktop -cf - *.desktop

# снимок
DISPLAY=:1 import -window root png:- > screenshot.png
```

Обои, ярлыки и снимок забираются на машину через `base64` поверх того же SSH.
После пересъёма — обновить `wallpaper.sha256` (`shasum -a 256 wallpaper.png > wallpaper.sha256`)
и прогнать `verify-dump.sh`.

На телефоне при съёме ничего не меняется: стол можно поднять и остановить, больше ничего.
