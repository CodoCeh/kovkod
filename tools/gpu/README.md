# GPU-стек стола (turnip + zink)

Своя сборка Mesa 26.3.0-devel (git e79595e), aarch64 glibc Debian 13, собрана
в lima-виртуалке `mesabuild`:

    meson setup build-gl -Dbuildtype=release -Dgallium-drivers=softpipe,zink \
      -Dvulkan-drivers= -Dplatforms=x11 -Dglx=dri -Degl=disabled -Dgbm=disabled \
      -Dllvm=disabled -Dfreedreno-kmds=msm,kgsl -Dvalgrind=disabled -Dlibunwind=disabled

    # turnip (Vulkan) — из отдельной сборки с -Dvulkan-drivers=freedreno

Проверено на Fold8 (SM8850/Adreno 840): glmark2 236–486 fps на 1280x720 против
4,4 fps llvmpipe. Презентация — drisw/kopper (XShm), DRI3 в Xvnc не нужен.
Включается обёрткой `gl-run ПРОГРАММА`; без неё стол рисует софтом.
Известное: полный прогон glmark2 падает на сцене `[buffer]` (бета turnip a8xx).
