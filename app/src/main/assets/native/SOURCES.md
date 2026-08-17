# Откуда взяты родные бинарники

Все файлы в `bin/`, `lib/` и `libexec/` — готовые сборки Termux для aarch64, скачанные
17.08.2026 из репозитория `termux-main`, ветка `stable`, компонент `main`, архитектура
`aarch64`. Ничего не пересобиралось и не патчилось: файлы вынуты из `.deb` как есть.

Индекс: `https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz`.
Sha256 каждого `.deb` сверен с индексом и совпал. Подпись репозитория (`Release.gpg`) при
этом не проверялась — доверие тут на уровне HTTPS к `packages.termux.dev`.

Тексты лицензий лежат в `licenses/<пакет>/`. Там, где в самом `.deb` файл `copyright` —
это ссылка на общий текст, взят текст из пакета `termux-licenses 2.2`
(`https://packages.termux.dev/apt/termux-main/pool/main/t/termux-licenses/termux-licenses_2.2_all.deb`,
sha256 `a3265cd1cf7d04754f2fb683eaf5b21918263792fd714457127900c1d6d9bcd9`).

## Файлы

| Файл | Байт | sha256 файла | Пакет Termux | Лицензия |
| --- | --- | --- | --- | --- |
| `bin/proot` | 241240 | `cafe2f3957f0c5c9…` | proot 5.1.107.91 | GPL-2.0 |
| `bin/dash` | 107552 | `1900215a59ff7c1e…` | dash 0.5.12-2 | BSD 3-Clause |
| `bin/tar` | 467080 | `66b641dee510d75e…` | tar 1.35-3 | GPL-3.0 |
| `bin/zstd` | 170944 | `2764819fb697bd7d…` | zstd 1.5.7-1 | GPL-2.0 |
| `bin/curl` | 308272 | `e9cd08920d448bb6…` | curl 8.21.0 | None |
| `libexec/proot/loader` | 18136 | `44ef39c1e1a18c09…` | proot 5.1.107.91 | GPL-2.0 |
| `lib/libacl.so` | 35360 | `650b7bea7c8e663e…` | libacl 2.4.0 | LGPL-2.1, GPL-2.0 |
| `lib/libandroid-glob.so` | 10536 | `e47405b23e40aea9…` | libandroid-glob 0.6-3 | BSD 3-Clause |
| `lib/libandroid-selinux.so` | 179056 | `d769bc3757df751e…` | libandroid-selinux 14.0.0.11-1 | Public Domain |
| `lib/libandroid-shmem.so` | 14432 | `84475798e07c8174…` | libandroid-shmem 0.7 | BSD 3-Clause |
| `lib/libcrypto.so.3` | 5205472 | `4122f5c95088f11a…` | openssl 1:3.6.3 | Apache-2.0 |
| `lib/libcurl.so` | 949832 | `79c192dc5c969277…` | libcurl 8.21.0 | MIT |
| `lib/libiconv.so` | 1082512 | `53349c7a84ad06da…` | libiconv 1.18-1 | LGPL-2.1, GPL-3.0 |
| `lib/liblzma.so.5` | 159496 | `daa0cf43130e6aa1…` | liblzma 5.8.3 | LGPL-2.1, GPL-2.0, GPL-3.0 |
| `lib/libnghttp2.so` | 160600 | `54cb84a8d8a48829…` | libnghttp2 1.70.0 | MIT |
| `lib/libnghttp3.so` | 155544 | `e8db2a6c16b1ef4b…` | libnghttp3 1.18.0 | MIT |
| `lib/libngtcp2.so` | 341944 | `c21394371208f6d7…` | libngtcp2 1.25.0 | MIT |
| `lib/libngtcp2_crypto_ossl.so` | 44264 | `844ce91538696526…` | libngtcp2 1.25.0 | MIT |
| `lib/libpcre2-8.so` | 489384 | `a6bbeb410e269e50…` | pcre2 10.47 | BSD 3-Clause |
| `lib/libssh2.so` | 248232 | `82e42fa48c77efd4…` | libssh2 1.11.1-2 | BSD 3-Clause |
| `lib/libssl.so.3` | 875304 | `9bf626fadf689237…` | openssl 1:3.6.3 | Apache-2.0 |
| `lib/libtalloc.so.2` | 31440 | `3c9b207c0a6ea289…` | libtalloc 2.4.3 | GPL-3.0 |
| `lib/libz.so.1` | 72880 | `c47748ed4b6ca6a2…` | zlib 1.3.2 | ZLIB |
| `lib/libzstd.so.1` | 820840 | `1677d5e828c1839f…` | zstd 1.5.7-1 | GPL-2.0 |

## Пакеты

### curl 8.21.0

- файлы: `bin/curl`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/c/curl/curl_8.21.0_aarch64.deb
- sha256 `.deb`: `6274522e6f172678ffbb70aa9fc82bb547117f3ba56f337735a44d19aaee9f40`
- лицензия по Termux: None; текст: `licenses/curl/copyright`
- проект: https://curl.se/

### dash 0.5.12-2

- файлы: `bin/dash`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/d/dash/dash_0.5.12-2_aarch64.deb
- sha256 `.deb`: `47d1ffce6c74fd7b3201162f26e7449f69c96bbd0aee4bf60e80ea2ba6708839`
- лицензия по Termux: BSD 3-Clause; текст: `licenses/dash/copyright`
- проект: http://gondor.apana.org.au/~herbert/dash/
- исходники, из которых Termux собирал: https://git.kernel.org/pub/scm/utils/dash/dash.git/snapshot/dash-${TERMUX_PKG_VERSION}.tar.gz

### libacl 2.4.0

- файлы: `lib/libacl.so`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/liba/libacl/libacl_2.4.0_aarch64.deb
- sha256 `.deb`: `cb620e33405e74b566cb05740dca488705cf0c475ec7f795fa333412211b6d92`
- лицензия по Termux: LGPL-2.1, GPL-2.0; текст: `licenses/libacl/COPYING`, `licenses/libacl/COPYING.LGPL`, `licenses/libacl/LGPL-2.1.txt`, `licenses/libacl/GPL-2.0.txt`
- проект: http://savannah.nongnu.org/projects/acl
- исходники, из которых Termux собирал: https://download.savannah.gnu.org/releases/acl/acl-${TERMUX_PKG_VERSION}.tar.gz

### libandroid-glob 0.6-3

- файлы: `lib/libandroid-glob.so`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-glob/libandroid-glob_0.6-3_aarch64.deb
- sha256 `.deb`: `2276ae8adedf0db76c2f4ffc94cc4cceb2f4f5d78e021b54e2e046d1233e7826`
- лицензия по Termux: BSD 3-Clause; текст: `licenses/libandroid-glob/copyright`
- проект: https://man7.org/linux/man-pages/man3/glob.3.html

### libandroid-selinux 14.0.0.11-1

- файлы: `lib/libandroid-selinux.so`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-selinux/libandroid-selinux_14.0.0.11-1_aarch64.deb
- sha256 `.deb`: `00afd8c34087c2864737b51fd9d104dc5e955f6ec3c0f50c0c7ef5b4a56866b9`
- лицензия по Termux: Public Domain; текст: `licenses/libandroid-selinux/Public Domain.txt`
- проект: https://selinuxproject.org
- исходники, из которых Termux собирал: https://android.googlesource.com/platform/external/selinux

### libandroid-shmem 0.7

- файлы: `lib/libandroid-shmem.so`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb
- sha256 `.deb`: `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6`
- лицензия по Termux: BSD 3-Clause; текст: `licenses/libandroid-shmem/copyright`
- проект: https://github.com/termux/libandroid-shmem
- исходники, из которых Termux собирал: https://github.com/termux/libandroid-shmem/archive/refs/tags/v${TERMUX_PKG_VERSION}.tar.gz

### libcurl 8.21.0

- файлы: `lib/libcurl.so`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/libc/libcurl/libcurl_8.21.0_aarch64.deb
- sha256 `.deb`: `ad644c7e16183d40eaf0f55df339021a69b25cc9b390e9634251e681be1ddcb8`
- лицензия по Termux: MIT; текст: `licenses/libcurl/copyright`
- проект: https://curl.se/
- исходники, из которых Termux собирал: https://github.com/curl/curl/releases/download/curl-${TERMUX_PKG_VERSION//./_}/curl-${TERMUX_PKG_VERSION}.tar.xz

### libiconv 1.18-1

- файлы: `lib/libiconv.so`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/libi/libiconv/libiconv_1.18-1_aarch64.deb
- sha256 `.deb`: `b19e6f348034bb48d2a5590b5cb242769f682c476717374d134d004cc663dc84`
- лицензия по Termux: LGPL-2.1, GPL-3.0; текст: `licenses/libiconv/LGPL-2.1.txt`, `licenses/libiconv/GPL-3.0.txt`
- проект: https://www.gnu.org/software/libiconv/
- исходники, из которых Termux собирал: https://mirrors.kernel.org/gnu/libiconv/libiconv-$TERMUX_PKG_VERSION.tar.gz

### liblzma 5.8.3

- файлы: `lib/liblzma.so.5`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/libl/liblzma/liblzma_5.8.3_aarch64.deb
- sha256 `.deb`: `594925a313879f590fbd24050305551a78eadd9a9319f6e612389b1a521113c6`
- лицензия по Termux: LGPL-2.1, GPL-2.0, GPL-3.0; текст: `licenses/liblzma/COPYING`, `licenses/liblzma/COPYING.GPLv2`, `licenses/liblzma/COPYING.GPLv3`, `licenses/liblzma/COPYING.LGPLv2.1`, `licenses/liblzma/COPYING`, `licenses/liblzma/COPYING.0BSD`, `licenses/liblzma/COPYING.GPLv2`
- проект: https://tukaani.org/xz/
- исходники, из которых Termux собирал: https://github.com/tukaani-project/xz/releases/download/v$TERMUX_PKG_VERSION/xz-$TERMUX_PKG_VERSION.tar.xz

### libnghttp2 1.70.0

- файлы: `lib/libnghttp2.so`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/libn/libnghttp2/libnghttp2_1.70.0_aarch64.deb
- sha256 `.deb`: `ab2e0a3408fe4934ffdf774bd5049a265db2e6db62dcb651baecbecfb81e3f0b`
- лицензия по Termux: MIT; текст: `licenses/libnghttp2/copyright`
- проект: https://nghttp2.org/
- исходники, из которых Termux собирал: https://github.com/nghttp2/nghttp2/releases/download/v${TERMUX_PKG_VERSION}/nghttp2-${TERMUX_PKG_VERSION}.tar.xz

### libnghttp3 1.18.0

- файлы: `lib/libnghttp3.so`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/libn/libnghttp3/libnghttp3_1.18.0_aarch64.deb
- sha256 `.deb`: `0be2ee96def3608afaf24eb0b4b55f3484a37c95ea21bb1d6d85c7d543467603`
- лицензия по Termux: MIT; текст: `licenses/libnghttp3/copyright`
- проект: https://nghttp2.org/nghttp3/
- исходники, из которых Termux собирал: https://github.com/ngtcp2/nghttp3/releases/download/v${TERMUX_PKG_VERSION}/nghttp3-${TERMUX_PKG_VERSION}.tar.xz

### libngtcp2 1.25.0

- файлы: `lib/libngtcp2.so`, `lib/libngtcp2_crypto_ossl.so`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/libn/libngtcp2/libngtcp2_1.25.0_aarch64.deb
- sha256 `.deb`: `f471bad7f4329b6b0b4aedf124e0a23a35f6ac99bfbae73304136f8bfd570fc3`
- лицензия по Termux: MIT; текст: `licenses/libngtcp2/copyright`
- проект: https://github.com/ngtcp2/ngtcp2
- исходники, из которых Termux собирал: https://github.com/ngtcp2/ngtcp2/releases/download/v$TERMUX_PKG_VERSION/ngtcp2-$TERMUX_PKG_VERSION.tar.gz

### libssh2 1.11.1-2

- файлы: `lib/libssh2.so`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/libs/libssh2/libssh2_1.11.1-2_aarch64.deb
- sha256 `.deb`: `1add4e0a926b848814e7e2f1817ea28b123c54cabd5bbc4e5cfd65291cbad84e`
- лицензия по Termux: BSD 3-Clause; текст: `licenses/libssh2/copyright`
- проект: https://www.libssh2.org
- исходники, из которых Termux собирал: https://www.libssh2.org/download/libssh2-$TERMUX_PKG_VERSION.tar.gz

### libtalloc 2.4.3

- файлы: `lib/libtalloc.so.2`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb
- sha256 `.deb`: `ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da`
- лицензия по Termux: GPL-3.0; текст: `licenses/libtalloc/GPL-3.0.txt`
- проект: https://talloc.samba.org/talloc/doc/html/index.html
- исходники, из которых Termux собирал: https://www.samba.org/ftp/talloc/talloc-${TERMUX_PKG_VERSION}.tar.gz

### openssl 1:3.6.3

- файлы: `lib/libcrypto.so.3`, `lib/libssl.so.3`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/o/openssl/openssl_1:3.6.3_aarch64.deb
- sha256 `.deb`: `86760e9ce736f463236f2c15b1eb3a3fdcfc5778d0fd7077a917448dcc90f3aa`
- лицензия по Termux: Apache-2.0; текст: `licenses/openssl/Apache-2.0.txt`
- проект: https://www.openssl.org/
- исходники, из которых Termux собирал: https://github.com/openssl/openssl/releases/download/openssl-${TERMUX_PKG_VERSION:2}/openssl-${TERMUX_PKG_VERSION:2}.tar.gz

### pcre2 10.47

- файлы: `lib/libpcre2-8.so`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/p/pcre2/pcre2_10.47_aarch64.deb
- sha256 `.deb`: `51f915d22de639bfca6ec029ae613987bbe3bc73626eede13319fd2e95f50b63`
- лицензия по Termux: BSD 3-Clause; текст: `licenses/pcre2/COPYING`, `licenses/pcre2/copyright`
- проект: https://pcre2project.github.io/pcre2/
- исходники, из которых Termux собирал: https://github.com/PCRE2Project/pcre2/releases/download/pcre2-${TERMUX_PKG_VERSION}/pcre2-${TERMUX_PKG_VERSION}.tar.bz2

### proot 5.1.107.91

- файлы: `bin/proot`, `libexec/proot/loader`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.91_aarch64.deb
- sha256 `.deb`: `1ad09f7ddf65f297a7b59a398cd1f23a48748b1144b225f0c1e2d9f447ef3efc`
- лицензия по Termux: GPL-2.0; текст: `licenses/proot/GPL-2.0.txt`
- проект: https://proot-me.github.io/
- исходники, из которых Termux собирал: https://github.com/termux/proot/archive/v${TERMUX_PKG_VERSION}.zip

### tar 1.35-3

- файлы: `bin/tar`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/t/tar/tar_1.35-3_aarch64.deb
- sha256 `.deb`: `a3847bc259b8654f621d36fa6345b1d6e93932e60e315e526eac2e3ecc5549ec`
- лицензия по Termux: GPL-3.0; текст: `licenses/tar/GPL-3.0.txt`
- проект: https://www.gnu.org/software/tar/
- исходники, из которых Termux собирал: https://mirrors.kernel.org/gnu/tar/tar-${TERMUX_PKG_VERSION}.tar.xz

### zlib 1.3.2

- файлы: `lib/libz.so.1`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/z/zlib/zlib_1.3.2_aarch64.deb
- sha256 `.deb`: `75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9`
- лицензия по Termux: ZLIB; текст: `licenses/zlib/copyright`
- проект: https://www.zlib.net/
- исходники, из которых Termux собирал: https://github.com/madler/zlib/releases/download/v${TERMUX_PKG_VERSION}/zlib-${TERMUX_PKG_VERSION}.tar.xz

### zstd 1.5.7-1

- файлы: `bin/zstd`, `lib/libzstd.so.1`
- `.deb`: https://packages.termux.dev/apt/termux-main/pool/main/z/zstd/zstd_1.5.7-1_aarch64.deb
- sha256 `.deb`: `e1b4a5113648da8de189620ba1fce74c48b2d0833d9043391b9a1c91fb606fd3`
- лицензия по Termux: GPL-2.0; текст: `licenses/zstd/GPL-2.0.txt`
- проект: https://github.com/facebook/zstd
- исходники, из которых Termux собирал: https://github.com/facebook/zstd/archive/refs/tags/v${TERMUX_PKG_VERSION}.tar.gz

## Что намеренно не взято

- `libexec/proot/loader32` из пакета `proot` — 32-битный загрузчик ARM. Целевая
  архитектура только arm64 (R13i), и файл провалил бы проверку `NativeBinariesArm64Test`.
- Пакет `curl` своего `share/doc/curl/copyright` не содержит: бинарник `curl` собран
  вместе с `libcurl` и в Termux это подпакет. Текст лицензии взят из `libcurl 8.21.0`,
  он же лежит в `licenses/curl/copyright`.
- `ca-certificates` не взят: пакет скачивался при разборе зависимостей, но в assets не
  положен. Без набора корневых сертификатов `curl` не проверит TLS — тому, кто будет
  качать программы по сети, придётся либо положить `cert.pem`, либо указать `--capath`
  на системное хранилище Android `/system/etc/security/cacerts`.

## Как это запускается

Файлы в assets не исполняемы. `NativeAssets` копирует `bin/`, `lib/` и `libexec/` в
`filesDir/native/` и ставит бит исполнения. Бинарники Termux собраны с
`RUNPATH=/data/data/com.termux/files/usr/lib`, которого у нас нет, поэтому `ProotSession`
передаёт им `LD_LIBRARY_PATH` на наш `filesDir/native/lib` — линкер Android смотрит туда
раньше, чем в RUNPATH.
