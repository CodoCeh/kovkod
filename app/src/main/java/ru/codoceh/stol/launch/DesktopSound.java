package ru.codoceh.stol.launch;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Звук стола: поток PulseAudio с 127.0.0.1:4713 — в динамик телефона. */
public final class DesktopSound {

    public static final int PORT = 4713;
    private static final int RATE = 48000;

    private volatile boolean running;
    private Thread thread;

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "stol-sound");
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    static boolean silent(byte[] pcm) {
        for (byte b : pcm) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private void loop() {
        int minBuffer = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        while (running) {
            AudioTrack track = null;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", PORT), 2000);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build())
                        .setBufferSizeInBytes(minBuffer * 2)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                DataInputStream in = new DataInputStream(socket.getInputStream());
                byte[] buffer = new byte[Math.max(4096, minBuffer / 2) & ~3];
                long silentSince = 0;
                while (running) {
                    in.readFully(buffer);
                    if (silent(buffer)) {
                        long now = System.currentTimeMillis();
                        if (silentSince == 0) {
                            silentSince = now;
                        } else if (now - silentSince > 1500 && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                            track.pause();
                            track.flush();
                        }
                        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                            continue;
                        }
                    } else {
                        silentSince = 0;
                        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                            track.play();
                        }
                    }
                    track.write(buffer, 0, buffer.length);
                }
            } catch (IOException | RuntimeException retry) {
            } finally {
                if (track != null) {
                    track.release();
                }
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException stopped) {
                return;
            }
        }
    }
}
