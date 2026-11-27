package tv.simple.recorder;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Кодирует кадры экрана в MP4. Кадры приходят на {@link #getInputSurface()}.
 * <p>
 * Сначала пробуется MediaCodec ({@link CodecEncoder}: перебор всех кодировщиков и вариантов
 * настроек), а если ни один не подошёл, системный MediaRecorder ({@link RecorderEncoder}).
 * На ТВ-приставках кодировщики часто капризные, поэтому запасной путь важен.
 */
abstract class ScreenEncoder {
    private static final String TAG = "ScreenEncoder";

    /** Не удалось открыть файл для записи (а не подобрать кодек). */
    static final class OutputException extends IOException {
        OutputException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Реально выбранные параметры (могут отличаться от желаемых, если кодек не тянет). */
    final int width;
    final int height;
    final int fps;

    ScreenEncoder(int width, int height, int fps) {
        this.width = width;
        this.height = height;
        this.fps = fps;
    }

    abstract Surface getInputSurface();

    abstract void start();

    /** Дожидается последних кадров, закрывает MP4. true, если в файле есть хотя бы один кадр. */
    abstract boolean stop();

    /** Освобождает всё, если запись так и не стартовала. */
    abstract void abort();

    /** Пауза и продолжение. Время паузы в видео не попадает. */
    abstract void setPaused(boolean paused);

    abstract Exception getFailure();

    static ScreenEncoder create(int wantW, int wantH, int wantFps, FileDescriptor fd)
            throws IOException {
        List<String> log = new ArrayList<>();
        try {
            return CodecEncoder.create(wantW, wantH, wantFps, fd, log);
        } catch (OutputException e) {
            throw e;
        } catch (IOException e) {
            Log.w(TAG, "MediaCodec path failed: " + e.getMessage());
        }
        try {
            return RecorderEncoder.create(wantW, wantH, wantFps, fd, log);
        } catch (IOException e) {
            Log.w(TAG, "MediaRecorder path failed: " + e.getMessage());
        }
        throw new IOException("No suitable video encoder. " + summary(log));
    }

    // ------------------------------------------------------------ общие помощники

    /** Кратко: до пяти разных причин отказа. Это попадает в сообщение для пользователя. */
    private static String summary(List<String> log) {
        Set<String> unique = new LinkedHashSet<>(log);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String line : unique) {
            if (shown == 5) {
                sb.append(" (+").append(unique.size() - shown).append(")");
                break;
            }
            if (shown > 0) {
                sb.append("; ");
            }
            sb.append(line);
            shown++;
        }
        Log.e(TAG, "Encoder attempts: " + unique);
        return sb.toString();
    }

    static String describe(Throwable t) {
        String text = t.getClass().getSimpleName();
        if (t.getMessage() != null) {
            text += ": " + t.getMessage();
        }
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    /** Размеры по убыванию: желаемый, выровненный по 16, затем 1080p, 720p, 480p и 360p. */
    static List<int[]> candidateSizes(int w, int h) {
        int[][] limits = {{1920, 1080}, {1280, 720}, {854, 480}, {640, 360}};
        List<int[]> sizes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int[] limit : limits) {
            float scale = Math.min(1f, Math.min(
                    (float) limit[0] / Math.max(w, h),
                    (float) limit[1] / Math.min(w, h)));
            int sw = Math.round(w * scale);
            int sh = Math.round(h * scale);
            int[][] options = {{even(sw), even(sh)}, {align(sw, 16), align(sh, 16)}};
            for (int[] size : options) {
                if (seen.add(size[0] + "x" + size[1])) {
                    sizes.add(size);
                }
            }
        }
        return sizes;
    }

    /** Желаемая частота, затем меньшие из 60, 30, 24, 15. */
    static int[] candidateFps(int fps) {
        int[] all = {60, 30, 24, 15};
        List<Integer> result = new ArrayList<>();
        result.add(fps);
        for (int f : all) {
            if (f < fps) {
                result.add(f);
            }
        }
        int[] out = new int[result.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = result.get(i);
        }
        return out;
    }

    /**
     * Варианты (ширина, высота, частота) от желаемого к запасным. Выбранную частоту стараемся
     * сохранить: для неё размер снижается только до ~720p, и лишь потом падает сама частота.
     */
    static List<int[]> candidates(int w, int h, int wantFps) {
        List<int[]> sizes = candidateSizes(w, h);
        long largest = (long) sizes.get(0)[0] * sizes.get(0)[1];
        int[] rates = candidateFps(wantFps);
        List<int[]> result = new ArrayList<>();
        for (int i = 0; i < rates.length; i++) {
            for (int[] size : sizes) {
                boolean big = (long) size[0] * size[1] * 10 >= largest * 4;
                if (i == 0 && rates.length > 1 && !big) {
                    continue;
                }
                result.add(new int[]{size[0], size[1], rates[i]});
            }
        }
        return result;
    }

    static int bitrate(int w, int h, int fps) {
        long bits = (long) (w * (long) h * fps * 0.10);
        return (int) Math.max(1_500_000L, Math.min(40_000_000L, bits));
    }

    static int even(int v) {
        return Math.max(2, v & ~1);
    }

    static int align(int v, int step) {
        int s = Math.max(1, step);
        return Math.max(s, (v + s / 2) / s * s);
    }

    /** Возвращает запись в начало и очищает файл перед новой попыткой. */
    static void rewind(FileDescriptor fd) {
        try {
            Os.ftruncate(fd, 0);
            Os.lseek(fd, 0, OsConstants.SEEK_SET);
        } catch (ErrnoException | RuntimeException ignored) {
            // не у всех файлов это возможно, для новых файлов и не нужно
        }
    }
}
