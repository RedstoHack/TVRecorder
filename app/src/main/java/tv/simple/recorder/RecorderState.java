package tv.simple.recorder;

import android.os.SystemClock;

import java.util.Locale;

/**
 * Состояние записи. Сервис и экраны живут в одном процессе,
 * поэтому достаточно общих volatile-полей.
 */
final class RecorderState {
    /** Идёт сеанс записи: короткий старт, сама запись или пауза. */
    static volatile boolean recording;
    /** Кадры реально пишутся (стартовая задержка прошла). */
    static volatile boolean capturing;
    static volatile boolean paused;
    static volatile boolean finalizing;
    /** SystemClock.elapsedRealtime() в момент начала записи. */
    static volatile long startedAt;
    /** Момент начала текущей паузы. */
    static volatile long pausedAt;
    /** Сколько миллисекунд записи уже прошло на паузе. */
    static volatile long pausedTotal;
    /** Последнее сообщение для главного экрана (результат или ошибка). */
    static volatile String message;

    private RecorderState() {
    }

    /** Подготовка к новому сеансу. */
    static void reset() {
        capturing = false;
        paused = false;
        finalizing = false;
        startedAt = 0;
        pausedAt = 0;
        pausedTotal = 0;
        message = null;
    }

    /** Длительность записи без пауз. */
    static long elapsedMs() {
        if (!capturing) {
            return 0;
        }
        long end = paused ? pausedAt : SystemClock.elapsedRealtime();
        return Math.max(0, end - startedAt - pausedTotal);
    }

    static String format(long millis) {
        long total = Math.max(0, millis / 1000);
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }
}
