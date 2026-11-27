package tv.simple.recorder;

import android.media.MediaRecorder;
import android.util.Log;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.List;

/**
 * Запасной путь: системный MediaRecorder. Внутри он сам задаёт кодировщику параметры, которые
 * подходят конкретному устройству, поэтому на ТВ-приставках часто работает там, где ручная
 * настройка MediaCodec не проходит.
 */
final class RecorderEncoder extends ScreenEncoder {
    private static final String TAG = "RecorderEncoder";

    private final MediaRecorder recorder;
    private final Surface inputSurface;
    private volatile Exception failure;
    private boolean paused;
    private boolean released;

    private RecorderEncoder(MediaRecorder recorder, Surface inputSurface,
                            int width, int height, int fps) {
        super(width, height, fps);
        this.recorder = recorder;
        this.inputSurface = inputSurface;
    }

    @SuppressWarnings("deprecation")
    static RecorderEncoder create(int wantW, int wantH, int wantFps, FileDescriptor fd,
                                  List<String> log) throws IOException {
        for (int[] candidate : candidates(wantW, wantH, wantFps)) {
            int w = candidate[0];
            int h = candidate[1];
            int fps = candidate[2];
            MediaRecorder recorder = new MediaRecorder();
            try {
                rewind(fd);
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setOutputFile(fd);
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                recorder.setVideoSize(w, h);
                recorder.setVideoFrameRate(fps);
                recorder.setVideoEncodingBitRate(bitrate(w, h, fps));
                recorder.prepare();
                Surface surface = recorder.getSurface();
                Log.i(TAG, "MediaRecorder " + w + "x" + h + "@" + fps);
                return new RecorderEncoder(recorder, surface, w, h, fps);
            } catch (Exception e) {
                Log.w(TAG, "MediaRecorder " + w + "x" + h + "@" + fps, e);
                log.add("MediaRecorder: " + describe(e));
                try {
                    recorder.release();
                } catch (Exception ignored) {
                    // уже освобождён
                }
            }
        }
        throw new IOException("MediaRecorder could not be prepared");
    }

    @Override
    Surface getInputSurface() {
        return inputSurface;
    }

    @Override
    void start() {
        recorder.start();
    }

    @Override
    void setPaused(boolean pause) {
        if (pause) {
            recorder.pause();
        } else {
            recorder.resume();
        }
        paused = pause;
    }

    @Override
    boolean stop() {
        boolean ok = false;
        try {
            if (paused) {
                recorder.resume();
                paused = false;
            }
            // Бросает исключение, если не пришло ни одного кадра: тогда файл не сохраняем.
            recorder.stop();
            ok = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "stop failed", e);
            failure = e;
        }
        releaseAll();
        return ok;
    }

    @Override
    void abort() {
        try {
            recorder.reset();
        } catch (RuntimeException ignored) {
            // ещё не был запущен
        }
        releaseAll();
    }

    @Override
    Exception getFailure() {
        return failure;
    }

    private synchronized void releaseAll() {
        if (released) {
            return;
        }
        released = true;
        try {
            recorder.release();
        } catch (Exception ignored) {
            // уже освобождён
        }
        try {
            inputSurface.release();
        } catch (Exception ignored) {
            // освобождена вместе с MediaRecorder
        }
    }
}
