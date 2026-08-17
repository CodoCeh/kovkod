package ru.codoceh.stol.launch;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Опрос маячка готовности стола (шов 3, R03.3). Маячок открывает порт только когда панель и
 * стол уже отрисованы.
 */
public interface BeaconPoller {

    /**
     * Ждёт готовности стола на порту beaconPort до timeoutMs миллисекунд.
     *
     * @return true если маячок ответил, false если вышло время ожидания
     */
    boolean waitForBeacon(int beaconPort, long timeoutMs);

    /** Стандартный опрос через подключение к сокету на 127.0.0.1. */
    BeaconPoller SYSTEM = new BeaconPoller() {
        @Override
        public boolean waitForBeacon(int beaconPort, long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", beaconPort), 250);
                    return true;
                } catch (IOException notYet) {
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            return false;
        }
    };
}
