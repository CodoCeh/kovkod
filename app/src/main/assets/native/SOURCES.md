# Родные бинарники: откуда взяты и на каких условиях

Этот файл — источник для раздела «О программе». Всё, что лежит в `bin/`, `lib/` и
`libexec/`, — готовые сборки Termux для aarch64. Ничего не пересобиралось и не
патчилось: файлы вынуты из `.deb` как есть, поэтому в таблице у каждого стоит
«модифицирован: нет».

Скачано 17.08.2026 из репозитория `termux-main`, ветка `stable`, компонент `main`,
архитектура `aarch64`. Индекс:
`https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz`.
Sha256 каждого `.deb` сверен с индексом и совпал. Подпись репозитория (`Release.gpg`)
не проверялась — доверие тут на уровне HTTPS к `packages.termux.dev`.

Тексты лицензий — в `licenses/<пакет>/`. Там, где `copyright` внутри `.deb` оказался
ссылкой на общий текст, взят файл из пакета `termux-licenses 2.2`
(`https://packages.termux.dev/apt/termux-main/pool/main/t/termux-licenses/termux-licenses_2.2_all.deb`,
sha256 `a3265cd1cf7d04754f2fb683eaf5b21918263792fd714457127900c1d6d9bcd9`).

## Как это соотносится с нашим кодом

Наш код чужие библиотеки не линкует. Каждый бинарник запускается отдельным процессом
через `ProotSession`, обмен — аргументы, потоки и код возврата. Поэтому копилефт
бинарников на наш код не распространяется, а обязательства закрываются раздачей их
исходников и текстов лицензий.

## Файлы

| Файл | Байт | sha256 | Пакет Termux | Лицензия | Копилефт | Модифицирован |
| --- | --- | --- | --- | --- | --- | --- |
| `bin/proot` | 241240 | `cafe2f3957f0c5c9…` | proot 5.1.107.91 | GPL-2.0 | да | нет |
| `bin/dash` | 107552 | `1900215a59ff7c1e…` | dash 0.5.12-2 | BSD 3-Clause | нет | нет |
| `bin/zstd` | 170944 | `2764819fb697bd7d…` | zstd 1.5.7-1 | BSD 3-Clause (двойная, см. ниже) | нет | нет |
| `libexec/proot/loader` | 18136 | `44ef39c1e1a18c09…` | proot 5.1.107.91 | GPL-2.0 | да | нет |
| `lib/libandroid-shmem.so` | 14432 | `84475798e07c8174…` | libandroid-shmem 0.7 | BSD 3-Clause | нет | нет |
| `lib/liblzma.so.5` | 159496 | `daa0cf43130e6aa1…` | liblzma 5.8.3 | 0BSD | нет | нет |
| `lib/libtalloc.so.2` | 31440 | `3c9b207c0a6ea289…` | libtalloc 2.4.3 | GPL-3.0 по Termux, LGPL-3.0 по upstream | да | нет |
| `lib/libz.so.1` | 72880 | `c47748ed4b6ca6a2…` | zlib 1.3.2 | Zlib | нет | нет |
| `lib/libzstd.so.1` | 820840 | `1677d5e828c1839f…` | zstd 1.5.7-1 | BSD 3-Clause (двойная, см. ниже) | нет | нет |

Полные суммы sha256 — в конце файла.

## Пакеты

### proot 5.1.107.91 — `bin/proot`, `libexec/proot/loader`

- `.deb`: `https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.91_aarch64.deb`
- sha256 `.deb`: `1ad09f7ddf65f297a7b59a398cd1f23a48748b1144b225f0c1e2d9f447ef3efc`
- лицензия: GPL-2.0, текст `licenses/proot/GPL-2.0.txt`
- проект: `https://proot-me.github.io/`, сборка Termux: `https://github.com/termux/proot`
- исходники, из которых собран этот бинарник:
  `https://github.com/termux/proot/archive/v5.1.107.91.zip`

Единственная часть с сильным копилефтом, замены которой сейчас нет: proot — это и есть
способ поднять Linux без root. Условия: не модифицируем и запускаем отдельным процессом.

### dash 0.5.12-2 — `bin/dash`

- `.deb`: `https://packages.termux.dev/apt/termux-main/pool/main/d/dash/dash_0.5.12-2_aarch64.deb`
- sha256 `.deb`: `47d1ffce6c74fd7b3201162f26e7449f69c96bbd0aee4bf60e80ea2ba6708839`
- лицензия: BSD 3-Clause, текст `licenses/dash/copyright`
- проект: `http://gondor.apana.org.au/~herbert/dash/`
- исходники: `https://git.kernel.org/pub/scm/utils/dash/dash.git/snapshot/dash-0.5.12.tar.gz`

Оболочка на стороне Android. Взят dash, а не busybox: busybox под GPL-2.0, dash — под BSD.

### zstd 1.5.7-1 — `bin/zstd`, `lib/libzstd.so.1`

- `.deb`: `https://packages.termux.dev/apt/termux-main/pool/main/z/zstd/zstd_1.5.7-1_aarch64.deb`
- sha256 `.deb`: `e1b4a5113648da8de189620ba1fce74c48b2d0833d9043391b9a1c91fb606fd3`
- лицензия: zstd раздаётся под двойной лицензией BSD 3-Clause **или** GPL-2.0;
  берём разрешительную ветку. Текст BSD — `licenses/zstd/LICENSE-BSD-3-Clause.txt`
  (взят из `https://raw.githubusercontent.com/facebook/zstd/v1.5.7/LICENSE`), текст
  GPL-2.0 из пакета Termux оставлен рядом как вторая половина двойной лицензии.
- проект: `https://github.com/facebook/zstd`
- исходники: `https://github.com/facebook/zstd/archive/refs/tags/v1.5.7.tar.gz`

### libtalloc 2.4.3 — `lib/libtalloc.so.2`

- `.deb`: `https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb`
- sha256 `.deb`: `ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da`
- лицензия: рецепт Termux заявляет GPL-3.0 и кладёт этот текст
  (`licenses/libtalloc/GPL-3.0.txt`); сам проект talloc заявляет LGPL-3.0-or-later.
  Записаны обе версии, потому что расходятся источники, а не потому что мы выбирали.
- проект: `https://talloc.samba.org/`
- исходники: `https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz`

Нужна не нам, а proot: без неё proot не запустится.

### libandroid-shmem 0.7 — `lib/libandroid-shmem.so`

- `.deb`: `https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb`
- sha256 `.deb`: `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6`
- лицензия: BSD 3-Clause, текст `licenses/libandroid-shmem/copyright`
- проект и исходники: `https://github.com/termux/libandroid-shmem`

Зависимость proot: System V shared memory поверх Android.

### liblzma 5.8.3 — `lib/liblzma.so.5`

- `.deb`: `https://packages.termux.dev/apt/termux-main/pool/main/libl/liblzma/liblzma_5.8.3_aarch64.deb`
- sha256 `.deb`: `594925a313879f590fbd24050305551a78eadd9a9319f6e612389b1a521113c6`
- лицензия: 0BSD. Так сказано в `COPYING` самого пакета: «liblzma is under the BSD Zero
  Clause License». Строка лицензии в рецепте Termux («LGPL-2.1, GPL-2.0, GPL-3.0»)
  устарела и относится к старым версиям XZ Utils; тексты из пакета лежат рядом
  целиком, чтобы это можно было перепроверить, а не поверить на слово.
- проект: `https://tukaani.org/xz/`
- исходники: `https://github.com/tukaani-project/xz/releases/download/v5.8.3/xz-5.8.3.tar.xz`

Зависимость `zstd`.

### zlib 1.3.2 — `lib/libz.so.1`

- `.deb`: `https://packages.termux.dev/apt/termux-main/pool/main/z/zlib/zlib_1.3.2_aarch64.deb`
- sha256 `.deb`: `75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9`
- лицензия: Zlib, текст `licenses/zlib/copyright`
- проект: `https://www.zlib.net/`
- исходники: `https://github.com/madler/zlib/releases/download/v1.3.2/zlib-1.3.2.tar.xz`

Зависимость `zstd`. В Android есть системная `libz.so`, но `zstd` просит `libz.so.1`,
которой в системе нет.

## Чего здесь намеренно нет

- **GNU tar** — GPL-3.0. Вместо него распаковку делает наш собственный код на стороне
  Android: `zstd -dc` отдаёт поток, tar разбирается в приложении. Так меньше чужого и
  ноль копилефта на этом участке.
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
cafe2f3957f0c5c92c2f0f545e259e09d1b580d2ea1264acdbb96e88223e19fd  bin/proot
1900215a59ff7c1efc98fd9fd3f923121339cea7fbbd59e5a52e43b143611d0a  bin/dash
2764819fb697bd7d931242b35a0a73141d54bc1ad43c819002ade960337df9f8  bin/zstd
44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04  libexec/proot/loader
84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731  lib/libandroid-shmem.so
daa0cf43130e6aa1aa8239c4f0ec4d2fc34ccebc69e6f518b67556b6ab0d588b  lib/liblzma.so.5
3c9b207c0a6ea2896b7523e03f55d9ab0d9e88baa115d4c32b84058ff4246fbb  lib/libtalloc.so.2
c47748ed4b6ca6a2ecd664bab04795f3493956ab1f5cddc4ba0efa737ef3b711  lib/libz.so.1
1677d5e828c1839f111f0261697c08f99a5e4e80434659ab88e5ff859a1374f7  lib/libzstd.so.1
```

## Как это запускается

Файлы в assets не исполняемы. `NativeAssets` копирует `bin/`, `lib/` и `libexec/` в
`filesDir/native/` и ставит бит исполнения. Бинарники Termux собраны с
`RUNPATH=/data/data/com.termux/files/usr/lib`, которого у нас нет, поэтому `ProotSession`
передаёт им `LD_LIBRARY_PATH` на наш `filesDir/native/lib`: линкер Android смотрит туда
раньше, чем в RUNPATH.
