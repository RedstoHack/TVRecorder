package tv.simple.recorder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * H.264 / H.265 кодировщик с входной поверхностью на MediaCodec, готовые пакеты в отдельном
 * потоке уходят в MediaMuxer.
 * <p>
 * Подбор устроен «до первого рабочего»: все кодировщики системы по очереди, для каждого размеры
 * от желаемого вниз и несколько вариантов настроек от полного к минимальному.
 */
final class CodecEncoder extends ScreenEncoder {
    private static final String TAG = "CodecEncoder";

    private static final String[] MIMES = {
            MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC};

    private final MediaCodec codec;
    private final MediaMuxer muxer;
    private final Surface inputSurface;

    private Thread drainThread;
    private volatile boolean muxerStarted;
    private int trackIndex = -1;
    private long firstPtsUs = -1;
    private long lastPtsUs;
    private volatile long stopDeadlineNs;
    private volatile boolean wroteSamples;
    private volatile Exception failure;
    private boolean released;

    // Пауза: интервалы, которые нужно вырезать из времени видео (микросекунды, часы nanoTime).
    private volatile boolean suspended;
    private long pauseStartUs = -1;
    private final List<long[]> pauses = new ArrayList<>();

    private CodecEncoder(MediaCodec codec, MediaMuxer muxer, Surface inputSurface,
                         int width, int height, int fps) {
        super(width, height, fps);
        this.codec = codec;
        this.muxer = muxer;
        this.inputSurface = inputSurface;
    }

    // ---------------------------------------------------------------- подбор кодека

    static CodecEncoder create(int wantW, int wantH, int wantFps, FileDescriptor fd,
                               List<String> log) throws IOException {
        for (String mime : MIMES) {
            String tag = MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime) ? "H.264" : "H.265";
            List<MediaCodecInfo> encoders = encodersFor(mime);
            if (encoders.isEmpty()) {
                log.add(tag + ": no encoders");
                continue;
            }
            for (MediaCodecInfo info : encoders) {
                try {
                    CodecEncoder encoder = tryEncoder(info, mime, wantW, wantH, wantFps, fd, log);
                    if (encoder != null) {
                        return encoder;
                    }
                } catch (OutputFailure e) {
                    throw new OutputException(
                            "Cannot create the MP4 file: " + describe(e.getCause()), e.getCause());
                }
            }
        }
        throw new IOException("No working MediaCodec encoder");
    }

    /** Все кодировщики формата. Те, что заявляют вход через Surface, идут первыми. */
    private static List<MediaCodecInfo> encodersFor(String mime) {
        List<MediaCodecInfo> withSurface = new ArrayList<>();
        List<MediaCodecInfo> others = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) {
                continue;
            }
            boolean match = false;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mime)) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                continue;
            }
            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
                (supportsSurface(caps) ? withSurface : others).add(info);
            } catch (IllegalArgumentException ignored) {
                // кодировщик не отдаёт возможности для этого формата
            }
        }
        withSurface.addAll(others);
        return withSurface;
    }

    private static boolean supportsSurface(MediaCodecInfo.CodecCapabilities caps) {
        for (int color : caps.colorFormats) {
            if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                return true;
            }
        }
        return false;
    }

    private static CodecEncoder tryEncoder(MediaCodecInfo info, String mime, int wantW, int wantH,
                                           int wantFps, FileDescriptor fd, List<String> log) {
        final String name = info.getName();
        final MediaCodecInfo.CodecCapabilities caps;
        try {
            caps = info.getCapabilitiesForType(mime);
        } catch (IllegalArgumentException e) {
            return null;
        }
        final MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
        final int variants = MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime) ? 3 : 2;
        boolean attempted = false;

        for (int[] candidate : candidates(wantW, wantH, wantFps)) {
            int w = candidate[0];
            int h = candidate[1];
            int f = candidate[2];
            if (video != null) {
                w = align(w, video.getWidthAlignment());
                h = align(h, video.getHeightAlignment());
                if (!video.isSizeSupported(w, h)) {
                    continue;
                }
                try {
                    Range<Double> rates = video.getSupportedFrameRatesFor(w, h);
                    if (f > rates.getUpper()) {
                        continue;
                    }
                } catch (IllegalArgumentException e) {
                    continue;
                }
            }
            for (int variant = 0; variant < variants; variant++) {
                attempted = true;
                MediaCodec codec = null;
                Surface surface;
                try {
                    codec = MediaCodec.createByCodecName(name);
                    codec.configure(buildFormat(mime, w, h, f, variant, caps, video),
                            null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    surface = codec.createInputSurface();
                } catch (Exception e) {
                    Log.w(TAG, name + " " + w + "x" + h + "@" + f + " v" + variant, e);
                    log.add(name + ": " + describe(e));
                    release(codec);
                    continue;
                }

                MediaMuxer muxer;
                try {
                    rewind(fd);
                    muxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                } catch (Exception e) {
                    surface.release();
                    release(codec);
                    // Это не вина кодека: сообщаем отдельно, чтобы сменить место сохранения.
                    throw new OutputFailure(e);
                }
                Log.i(TAG, "Encoder " + name + " " + mime + " " + w + "x" + h + "@" + f
                        + " variant " + variant);
                return new CodecEncoder(codec, muxer, surface, w, h, f);
            }
        }
        if (!attempted) {
            log.add(name + ": no supported size");
        }
        return null;
    }

    /** Служебное исключение: пробрасывается из подбора и превращается в OutputException. */
    private static final class OutputFailure extends RuntimeException {
        OutputFailure(Throwable cause) {
            super(cause);
        }
    }

    private static void release(MediaCodec codec) {
        if (codec == null) {
            return;
        }
        try {
            codec.release();
        } catch (Exception ignored) {
            // уже освобождён
        }
    }

    /**
     * Варианты настроек. 0: полные (повтор кадра, ограничение частоты); 1: минимальные, без
     * необязательных ключей, которые некоторые кодировщики не принимают; 2: с явным профилем и
     * уровнем H.264, их иногда требуют кодировщики ТВ-приставок.
     */
    private static MediaFormat buildFormat(String mime, int w, int h, int fps, int variant,
                                           MediaCodecInfo.CodecCapabilities caps,
                                           MediaCodecInfo.VideoCapabilities video) {
        MediaFormat format = MediaFormat.createVideoFormat(mime, w, h);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        int bitrate = bitrate(w, h, fps);
        if (video != null) {
            bitrate = video.getBitrateRange().clamp(bitrate);
        }
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (variant == 0) {
            // Если экран не меняется, кодек сам повторяет прошлый кадр, и длина видео не «съедается».
            format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps);
            format.setFloat("max-fps-to-encoder", fps);
        } else if (variant == 2) {
            format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps);
            int[] profileLevel = bestAvcProfile(caps);
            if (profileLevel != null) {
                format.setInteger(MediaFormat.KEY_PROFILE, profileLevel[0]);
                format.setInteger(MediaFormat.KEY_LEVEL, profileLevel[1]);
            }
        }
        return format;
    }

    private static int[] bestAvcProfile(MediaCodecInfo.CodecCapabilities caps) {
        int[] preferred = {
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline};
        for (int profile : preferred) {
            int level = 0;
            for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
                if (pl.profile == profile) {
                    level = Math.max(level, pl.level);
                }
            }
            if (level > 0) {
                return new int[]{profile, level};
            }
        }
        return null;
    }

    // ----------------------------------------------------------------- работа

    @Override
    Surface getInputSurface() {
        return inputSurface;
    }

    @Override
    void start() {
        codec.start();
        drainThread = new Thread(this::drainLoop, "encoder-drain");
        drainThread.start();
    }

    @Override
    void setPaused(boolean pause) {
        Bundle params = new Bundle();
        params.putInt(MediaCodec.PARAMETER_KEY_SUSPEND, pause ? 1 : 0);
        codec.setParameters(params);
        long now = System.nanoTime() / 1000;
        synchronized (pauses) {
            if (pause) {
                pauseStartUs = now;
            } else if (pauseStartUs >= 0) {
                pauses.add(new long[]{pauseStartUs, now});
                pauseStartUs = -1;
            }
        }
        suspended = pause;
    }

    /** Сколько микросекунд паузы прошло до этого кадра: столько вычитаем из его времени. */
    private long pausedBefore(long ptsUs) {
        long total = 0;
        synchronized (pauses) {
            for (long[] p : pauses) {
                if (p[1] <= ptsUs) {
                    total += p[1] - p[0];
                }
            }
        }
        return total;
    }

    @Override
    boolean stop() {
        if (suspended) {
            // Остановленный на паузе кодировщик может не выдать конец потока.
            try {
                Bundle params = new Bundle();
                params.putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 0);
                codec.setParameters(params);
            } catch (Exception e) {
                Log.w(TAG, "resume before stop failed", e);
            }
        }
        try {
            codec.signalEndOfInputStream();
        } catch (Exception e) {
            Log.w(TAG, "signalEndOfInputStream failed", e);
        }
        stopDeadlineNs = System.nanoTime() + 4_000_000_000L;
        if (drainThread != null) {
            try {
                drainThread.join(7000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            releaseAll();
        }
        return wroteSamples && failure == null;
    }

    @Override
    void abort() {
        if (drainThread == null) {
            releaseAll();
        } else {
            stop();
        }
    }

    @Override
    Exception getFailure() {
        return failure;
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int index = codec.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    long deadline = stopDeadlineNs;
                    if (deadline != 0 && System.nanoTime() > deadline) {
                        Log.w(TAG, "EOS did not arrive in time");
                        break;
                    }
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        throw new IllegalStateException("Output format changed twice");
                    }
                    int track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                    trackIndex = track;
                } else if (index >= 0) {
                    ByteBuffer data = codec.getOutputBuffer(index);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (data != null && info.size > 0 && muxerStarted) {
                        long source = info.presentationTimeUs;
                        if (firstPtsUs < 0) {
                            firstPtsUs = source;
                        }
                        long pts = source - firstPtsUs - pausedBefore(source);
                        // Время в файле не должно идти назад, даже если пара кадров «просочилась» на паузе.
                        pts = Math.max(pts, lastPtsUs);
                        lastPtsUs = pts;
                        info.presentationTimeUs = pts;
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        muxer.writeSampleData(trackIndex, data, info);
                        wroteSamples = true;
                    }
                    codec.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "drain failed", e);
            failure = e;
        } finally {
            releaseAll();
        }
    }

    private synchronized void releaseAll() {
        if (released) {
            return;
        }
        released = true;
        try {
            codec.stop();
        } catch (Exception ignored) {
            // кодек мог быть не запущен
        }
        try {
            codec.release();
        } catch (Exception ignored) {
            // уже освобождён
        }
        try {
            if (muxerStarted) {
                muxer.stop();
            }
        } catch (Exception e) {
            Log.w(TAG, "muxer.stop failed", e);
            if (failure == null) {
                failure = e;
            }
        }
        try {
            muxer.release();
        } catch (Exception ignored) {
            // уже освобождён
        }
        try {
            inputSurface.release();
        } catch (Exception ignored) {
            // уже освобождена
        }
    }
}
