#!/bin/bash
#
# /opt/stol/win-keys.sh — сочетания Win+↑, Win+←/→, Win+D как в Windows.
#
# xfwm4 под Xtigervnc сочетания не ловит, а xfsettingsd ловит (Win+E работает),
# поэтому клавиши висят в /commands/custom и зовут этот скрипт.

set -u

case "${1:-}" in
    max)
        wmctrl -r :ACTIVE: -b toggle,maximized_vert,maximized_horz
        ;;
    desktop)
        case "$(xprop -root _NET_SHOWING_DESKTOP)" in
            *'= 1') wmctrl -k off ;;
            *) wmctrl -k on ;;
        esac
        ;;
    left|right)
        read -r x y w h < <(xprop -root _NET_WORKAREA | sed 's/.*= //; s/,//g' | cut -d' ' -f1-4)
        half=$((w / 2))
        [ "$1" = right ] && x=$((x + half))
        wmctrl -r :ACTIVE: -b remove,maximized_vert,maximized_horz
        wmctrl -r :ACTIVE: -e "0,$x,$y,$half,$h"
        ;;
    *)
        echo "usage: $0 max|desktop|left|right" >&2
        exit 2
        ;;
esac
