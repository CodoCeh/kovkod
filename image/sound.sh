#!/bin/sh
#
# /opt/stol/sound.sh — звук стола: PulseAudio пишет в пустой выход, его поток уходит
# на 127.0.0.1:4713, откуда приложение КовКод проигрывает его в динамик телефона.

export HOME="${HOME:-/root}"
pulseaudio --kill 2>/dev/null
exec pulseaudio -n --daemonize=no --exit-idle-time=-1 --disallow-exit \
    --load="module-native-protocol-unix auth-anonymous=1" \
    --load="module-null-sink sink_name=kovkod sink_properties=device.description=Телефон" \
    --load="module-simple-protocol-tcp listen=127.0.0.1 port=4713 source=kovkod.monitor record=true playback=false format=s16le rate=48000 channels=2" \
    --load="module-always-sink"
