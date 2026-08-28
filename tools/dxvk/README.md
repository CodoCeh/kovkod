# DXVK на столе — статус

`DXVK` (`D3D→Vulkan`) упирается в презентацию: `Xvnc` без `DRI3` — `vkcube` `No DRI3 support detected`, `turnip` при этом жив (`vulkaninfo` `Adreno 840`).

Путь:
- `WineD3D→GL→zink` уже работает через `/opt/stol/gpu/gl-run` (проверено `Notepad`/`Mines`).
- `DXVK→winevulkan→turnip` требует `WSI`-шим `swapchain→XShm` или `wayland` (`labwc` + `Xwayland`). Заготовка — неделя работы, пока `WineD3D` закрывает игры.

Включается, когда `Mesa` `a8xx` стабилизируется и появится шим. Телеметрия падений: `/opt/stol/telemetry/collect.sh`.
