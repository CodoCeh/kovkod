package ru.codoceh.stol.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Запуск постороннего процесса. Отдельный шов ровно для того, чтобы {@link ProotSession}
 * проверялся без устройства: в тестах подставляется запись вызова вместо настоящего запуска.
 */
public interface ProcessLauncher {

    /**
     * @param background не ждать завершения; код возврата тогда говорит только о старте
     * @return код возврата процесса, либо 0 при удачном старте фонового процесса
     */
    int run(List<String> argv, Map<String, String> env, boolean background) throws IOException;

    /** Запускает процесс и отдаёт его вызывающему живым, не дожидаясь завершения. */
    Handle open(List<String> argv, Map<String, String> env) throws IOException;

    /** Живой процесс во владении приложения. */
    interface Handle {

        /** Работает ли он ещё. */
        boolean isAlive();

        /**
         * Дожидается конца процесса и освобождает его. Заканчиваться ему велят до этого
         * вызова: сигнал снаружи снимает только его самого, а порождённое им остаётся.
         */
        void close();
    }
}
