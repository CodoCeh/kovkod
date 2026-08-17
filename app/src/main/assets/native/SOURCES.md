# Родные бинарники: откуда взяты и на каких условиях

Этот файл — источник для раздела «О программе». Всё родное лежит в
`app/src/main/jniLibs/arm64-v8a/` и едет в APK как каталог родных библиотек; тексты
лицензий — рядом с этим файлом, в `licenses/<пакет>/`.

Файлы — готовые сборки Termux для aarch64. Ничего не пересобиралось и не патчилось: они
вынуты из `.deb` как есть, поэтому в таблице у каждого стоит «модифицирован: нет».
Переезд в каталог библиотек **переименовал** файлы (система распаковывает из APK только
имена вида `lib*.so`), но содержимое не тронул: sha256 в таблице — те же суммы, что были
у файлов внутри `.deb`, и их можно сверить с оригиналом побайтово.

Скачано 17.08.2026 из репозитория `termux-main`, ветка `stable`, компонент `main`,
архитектура `aarch64`. Индекс:
`https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz`.
Sha256 каждого `.deb` сверен с индексом и совпал. Подпись репозитория (`Release.gpg`)
не проверялась — доверие тут на уровне HTTPS к `packages.termux.dev`.

Там, где `copyright` внутри `.deb` оказался ссылкой на общий текст, взят файл из пакета
`termux-licenses 2.2`
(`https://packages.termux.dev/apt/termux-main/pool/main/t/termux-licenses/termux-licenses_2.2_all.deb`,
sha256 `a3265cd1cf7d04754f2fb683eaf5b21918263792fd714457127900c1d6d9bcd9`).

## Как это соотносится с нашим кодом

Наш код чужие библиотеки не линкует. Каждый бинарник запускается отдельным процессом
через `ProotSession`, обмен — аргументы, потоки и код возврата. Поэтому копилефт
бинарников на наш код не распространяется, а обязательства закрываются раздачей их
исходников и текстов лицензий.

## Файлы

| Файл в `jniLibs/arm64-v8a` | Что это в пакете | Байт | sha256 | Пакет Termux | Лицензия | Копилефт | Модифицирован |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `libproot.so` | `bin/proot` | 241240 | `cafe2f3957f0c5c9…` | proot 5.1.107.91 | GPL-2.0 | да | нет |
| `libproot-loader.so` | `libexec/proot/loader` | 18136 | `44ef39c1e1a18c09…` | proot 5.1.107.91 | GPL-2.0 | да | нет |
| `libtalloc.so` | `lib/libtalloc.so.2` | 31440 | `3c9b207c0a6ea289…` | libtalloc 2.4.3 | GPL-3.0 по Termux, LGPL-3.0 по upstream | да | нет |
| `libandroid-shmem.so` | `lib/libandroid-shmem.so` | 14432 | `84475798e07c8174…` | libandroid-shmem 0.7 | BSD 3-Clause | нет | нет |

Полные суммы sha256 — в конце файла.

## Пакеты

### proot 5.1.107.91 — `libproot.so`, `libproot-loader.so`

- `.deb`: `https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.91_aarch64.deb`
- sha256 `.deb`: `1ad09f7ddf65f297a7b59a398cd1f23a48748b1144b225f0c1e2d9f447ef3efc`
- лицензия: GPL-2.0, текст `licenses/proot/GPL-2.0.txt`
- проект: `https://proot-me.github.io/`, сборка Termux: `https://github.com/termux/proot`
- исходники, из которых собран этот бинарник:
  `https://github.com/termux/proot/archive/v5.1.107.91.zip`

Единственная часть с сильным копилефтом, замены которой сейчас нет: proot — это и есть
способ поднять Linux без root. Условия: не модифицируем и запускаем отдельным процессом.

### libtalloc 2.4.3 — `libtalloc.so`

- `.deb`: `https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb`
- sha256 `.deb`: `ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da`
- лицензия: рецепт Termux заявляет GPL-3.0 и кладёт этот текст
  (`licenses/libtalloc/GPL-3.0.txt`); сам проект talloc заявляет LGPL-3.0-or-later.
  Записаны обе версии, потому что расходятся источники, а не потому что мы выбирали.
- проект: `https://talloc.samba.org/`
- исходники: `https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz`

Нужна не нам, а proot: без неё proot не запустится. Внутри у неё имя `libtalloc.so.2` —
про это см. «Как это запускается».

### libandroid-shmem 0.7 — `libandroid-shmem.so`

- `.deb`: `https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb`
- sha256 `.deb`: `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6`
- лицензия: BSD 3-Clause, текст `licenses/libandroid-shmem/copyright`
- проект и исходники: `https://github.com/termux/libandroid-shmem`

Зависимость proot: System V shared memory поверх Android.

## Чего здесь намеренно нет

- **zstd** — бинарник `bin/zstd` и библиотеки `libzstd.so.1`, `libz.so.1`,
  `liblzma.so.5` убраны: образ распаковывает наш собственный код на стороне Android
  (`RootfsInstaller`), а `.zst` ему разжимает библиотека `zstd-jni` внутри процесса.
  Отдельный процесс `zstd` перестал вызываться, и вместе с ним отпали три библиотеки,
  которые были нужны только ему, — около 1,2 МБ. Текст лицензии zstd
  (`licenses/zstd/`) остался: код zstd по-прежнему едет в APK, но уже внутри `zstd-jni`
  (BSD 2-Clause), а не отдельным бинарником.
- **GNU tar** — GPL-3.0. Разбор `.tar` тоже делает наш код, на Commons Compress
  (Apache-2.0). Так меньше чужого и ноль копилефта на этом участке.
- **bsdtar (libarchive, BSD-2)** — рассматривался как разрешительная замена GNU tar и
  отклонён: сборка Termux тянет `libxml2`, а та — ICU, это плюс ~30 МБ ради разбора XML
  в архиваторе.
- **busybox / toybox** — busybox под GPL-2.0, toybox в `termux-main` нет. Оболочка — dash.
- **curl** — лицензия у него разрешительная, но бинарник не нужен: скачивать умеет сам
  Android (`HttpURLConnection`), и вместе с curl отпадают `libcurl`, `openssl`,
  `libssh2`, `libnghttp2/3`, `libngtcp2` и набор корневых сертификатов — около 7 МБ
  чужого кода.
- **`libexec/proot/loader32`** из пакета `proot` — 32-битный загрузчик ARM. Целевая
  архитектура только arm64 (R13i), и файл провалил бы `NativeBinariesArm64Test`.

## Полные суммы sha256

```
cafe2f3957f0c5c92c2f0f545e259e09d1b580d2ea1264acdbb96e88223e19fd  libproot.so
44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04  libproot-loader.so
1900215a59ff7c1efc98fd9fd3f923121339cea7fbbd59e5a52e43b143611d0a  libdash.so
3c9b207c0a6ea2896b7523e03f55d9ab0d9e88baa115d4c32b84058ff4246fbb  libtalloc.so
84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731  libandroid-shmem.so
```

## Как это запускается

Файлы лежат в `jniLibs/arm64-v8a/` и попадают в APK как родные библиотеки. При установке
система распаковывает их (`extractNativeLibs=true`) в свой каталог библиотек и ставит бит
исполнения; приложению этот каталог доступен только на чтение. Оттуда запуск разрешён при
любом `targetSdk` — в отличие от каталога данных приложения, где с Android 10 запуск
запрещён правилом W^X. Из-за этого правила и стоял `targetSdk 28`; после переезда его
больше ничто не держит.

Отсюда два ограничения, которые видны прямо в именах файлов:

1. **Имя обязано выглядеть как `lib*.so`** — остальное установщик из APK не распаковывает.
   Поэтому `proot` стал `libproot.so`, а его загрузчик — `libproot-loader.so`. Загрузчик
   proot ищет рядом с собой, но зашитое в сборку Termux имя другое, поэтому `ProotSession`
   передаёт полный путь в `PROOT_LOADER`.
2. **Версионное имя библиотеки так не записать.** proot просит `libtalloc.so.2` — линкер
   ищет буквально это имя, а файл в каталоге библиотек называется `libtalloc.so`. Разницу
   закрывает символическая ссылка `files/soname/libtalloc.so.2` на файл в каталоге
   библиотек: имя берётся из каталога данных, а исполняется и отображается всё равно файл
   из каталога библиотек, так что запрет W^X не нарушается. Ссылку `ProotSession`
   переставляет перед каждым запуском — после обновления APK каталог библиотек лежит по
   новому пути.

Бинарники Termux собраны с `RUNPATH=/data/data/com.termux/files/usr/lib`, которого у нас
нет, поэтому `ProotSession` передаёт `LD_LIBRARY_PATH` из двух каталогов: сам каталог
библиотек и каталог ссылок. Линкер Android смотрит туда раньше, чем в RUNPATH.
