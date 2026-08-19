# Требования — Падение на Android 11: RemoteServiceException в DesktopService

Прогон: `padenie-na-android-11-remoteserviceexception-v-d`

_Файл собран из `state.json`. Править его руками бесполезно: источник истины один, и это состояние._

| ID | Статус | Из брифа | Основание | Таски |
| --- | --- | --- | --- | --- |
| R01 | закрыто | RemoteServiceException: startForegroundService() did not then call startForeground() | Устранить падение RemoteServiceException в DesktopService | 01 |
| R02 | закрыто | главный поток не укладывается в пять секунд | Гарантировать вызов Service.startForeground() без задержек главного потока | 01 |
| R03i | закрыто | Доказательство — прогон на живом Android 11, а не рассуждение | Доказать устранение падения живым прогоном на эмуляторе stol_phone_api30 (Android 11 / API 30) | 02 |
| R04i | закрыто | процесс снимается вместе со столом | Служба DesktopService стабильно удерживает процесс стола при переходе состояний | 01 |

## Итог

Требований: 4 — закрыто 4.
