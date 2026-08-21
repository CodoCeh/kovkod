# Требования — Стек Windows-программ (Hangover/Wine) при targetSdk 36 с загрузкой PE в…

Стек Windows-программ (Hangover/Wine) при targetSdk 36 с загрузкой PE в анонимную память

Прогон: `stek-windows-programm-hangover-wine-pri-targetsd`

_Файл собран из `state.json`. Править его руками бесполезно: источник истины один, и это состояние._

| ID | Статус | Из брифа | Основание | Таски |
| --- | --- | --- | --- | --- |
| R01 | закрыто | Стек Windows-программ (Hangover/Wine) | Поддержка запуска Windows-приложений на aarch64 Linux через Hangover | 02, 03, 04 |
| R02 | закрыто | при targetSdk 36 | Работа при targetSdk 36 в домене untrusted_app без понижения API | 01, 04 |
| R03 | закрыто | с загрузкой PE в анонимную память | Обход SELinux execmod через сборку ntdll.so с removable=TRUE | 01, 02, 04 |
| R04i | закрыто | Стек Windows-программ | Установка стека Hangover/Wine и ntdll.so по требованию из магазина | 03, 04 |
| R05i | закрыто | Стек Windows-программ (Hangover/Wine) при targetSdk 36 с загрузкой PE в анонимную память | Сквозной тест запуска windows-apps.sh на живом эмуляторе | 04 |

## Итог

Требований: 5 — закрыто 5.
