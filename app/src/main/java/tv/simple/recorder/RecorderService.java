package tv.simple.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.widget.Toast;

import java.io.IOException;

/** Foreground-сервис, который держит запись экрана, паузу и плавающий таймер. */
public class RecorderService extends Service {
    private static final String TAG = "RecorderService";

    static final String ACTION_START = "tv.simple.recorder.action.START";
    static final String ACTION_STOP = "tv.simple.recorder.action.STOP";
    static final String ACTION_TOGGLE_PAUSE = "tv.simple.recorder.action.TOGGLE_PAUSE";
    private static final String EXTRA_RESULT_CODE = "result_code";
    private static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "recording";
    private static final int NOTIFICATION_ID = 1;

    /** Максимальный размер видео: больше 1080p на ТВ не нужно, а кодировщики 4K часто не тянут. */
    private static final float MAX_LONG_SIDE = 1920f;
    private static final float MAX_SHORT_SIDE = 1080f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Context localized;

    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private ScreenEncoder encoder;
    private RegionRenderer renderer;
    private OutputTarget output;

    private boolean active;
    private boolean stopping;

    static Intent startIntent(Context context, int resultCode, Intent resultData) {
        return new Intent(context, RecorderService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData);
    }

    static Intent stopIntent(Context context) {
        return new Intent(context, RecorderService.class).setAction(ACTION_STOP);
    }

    static Intent togglePauseIntent(Context context) {
        return new Intent(context, RecorderService.class).setAction(ACTION_TOGGLE_PAUSE);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        localized = LocaleHelper.wrap(this);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                localized.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            try {
                enterForeground();
            } catch (RuntimeException e) {
                Log.e(TAG, "startForeground failed", e);
                failStart(e);
                return START_NOT_STICKY;
            }
            if (!active && !stopping) {
                begin(intent);
            }
        } else if (ACTION_STOP.equals(action)) {
            if (active) {
                stopRecording();
            } else if (!stopping) {
                stopSelf();
            }
        } else if (ACTION_TOGGLE_PAUSE.equals(action)) {
            if (active) {
                togglePause();
            }
        } else if (!active && !stopping) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        // Сюда можно попасть, только если запись уже остановлена или процесс закрывают.
        if (active) {
            active = false;
            RecorderState.recording = false;
            RecorderState.capturing = false;
            RecorderState.paused = false;
            quietRelease();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ запуск

    private void enterForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        final boolean paused = RecorderState.paused;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent(this),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_rec)
                .setContentTitle(localized.getString(
                        paused ? R.string.notif_title_paused : R.string.notif_title))
                .setContentText(localized.getString(R.string.notif_text))
                .setContentIntent(open)
                .setOngoing(true);
        if (RecorderState.capturing) {
            PendingIntent toggle = PendingIntent.getService(this, 2, togglePauseIntent(this),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, paused ? R.drawable.ic_play : R.drawable.ic_pause),
                    localized.getString(paused ? R.string.notif_resume : R.string.notif_pause),
                    toggle).build());
        }
        builder.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stop),
                localized.getString(R.string.notif_stop), stop).build());
        return builder.build();
    }

    private void refreshNotification() {
        try {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot update the notification", e);
        }
    }

    /** Запись начинается сразу: сервис уже работает на переднем плане. */
    @SuppressWarnings("deprecation")
    private void begin(Intent intent) {
        int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        RecorderState.reset();
        RecorderState.recording = true;
        startRecording(code, data);
    }

    private void startRecording(int resultCode, Intent resultData) {
        try {
            if (resultData == null) {
                throw new IOException("No projection permission data");
            }
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                throw new IOException("MediaProjection is null");
            }
            // Android 14+ требует зарегистрировать callback до создания VirtualDisplay.
            projectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    main.post(() -> {
                        if (active) {
                            stopRecording();
                        }
                    });
                }
            };
            projection.registerCallback(projectionCallback, main);

            Point screen = screenSize();
            int fps = Prefs.getFps(this);
            int dpi = getResources().getDisplayMetrics().densityDpi;
            output = OutputTarget.create(this);

            if (Prefs.isZoneMode(this)) {
                RectF zone = Prefs.getZone(this);
                int zoneW = Math.max(2, Math.round(zone.width() * screen.x));
                int zoneH = Math.max(2, Math.round(zone.height() * screen.y));
                encoder = createEncoder(fit(zoneW, zoneH), fps);
                renderer = new RegionRenderer(encoder.getInputSurface(), encoder.width,
                        encoder.height, screen.x, screen.y, zone);
                Surface source = renderer.start();
                virtualDisplay = projection.createVirtualDisplay("TVRecorder", screen.x, screen.y,
                        dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, source, null, null);
            } else {
                encoder = createEncoder(fit(screen.x, screen.y), fps);
                virtualDisplay = projection.createVirtualDisplay("TVRecorder",
                        encoder.width, encoder.height, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        encoder.getInputSurface(), null, null);
            }
            if (virtualDisplay == null) {
                throw new IOException("VirtualDisplay was not created");
            }
            encoder.start();

            active = true;
            RecorderState.startedAt = SystemClock.elapsedRealtime();
            RecorderState.pausedTotal = 0;
            RecorderState.paused = false;
            RecorderState.capturing = true;
            RecorderState.recording = true;
            refreshNotification();
        } catch (Exception e) {
            Log.e(TAG, "Cannot start recording", e);
            failStart(e);
        }
    }

    /**
     * Подбирает кодировщик под файл. Если выбранное место не принимает запись (например, папка
     * на накопителе, который не поддерживает нужный доступ), пробует папку приложения.
     */
    private ScreenEncoder createEncoder(int[] size, int fps) throws IOException {
        try {
            return ScreenEncoder.create(size[0], size[1], fps, output.pfd.getFileDescriptor());
        } catch (ScreenEncoder.OutputException e) {
            Log.w(TAG, "Selected location rejected the file, using the app folder", e);
            output.finish(false);
            output = OutputTarget.createFallback(this);
            return ScreenEncoder.create(size[0], size[1], fps, output.pfd.getFileDescriptor());
        }
    }

    private void failStart(Exception e) {
        quietRelease();
        if (output != null) {
            output.finish(false);
            output = null;
        }
        String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        String text = localized.getString(R.string.err_start_failed, reason);
        RecorderState.reset();
        RecorderState.recording = false;
        RecorderState.message = text;
        toast(text);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ------------------------------------------------------------------ пауза

    private void togglePause() {
        if (!active || stopping || encoder == null) {
            return;
        }
        boolean pause = !RecorderState.paused;
        try {
            encoder.setPaused(pause);
        } catch (RuntimeException e) {
            Log.w(TAG, "Pause is not supported by this encoder", e);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (pause) {
            RecorderState.pausedAt = now;
        } else {
            RecorderState.pausedTotal += now - RecorderState.pausedAt;
        }
        RecorderState.paused = pause;
        refreshNotification();
    }

    // ---------------------------------------------------------------- остановка

    private void stopRecording() {
        if (!active || stopping) {
            return;
        }
        stopping = true;
        active = false;
        RecorderState.recording = false;
        RecorderState.capturing = false;
        RecorderState.paused = false;
        RecorderState.finalizing = true;

        final VirtualDisplay display = virtualDisplay;
        final RegionRenderer region = renderer;
        final ScreenEncoder enc = encoder;
        final MediaProjection proj = projection;
        final MediaProjection.Callback callback = projectionCallback;
        final OutputTarget out = output;

        // Остановка может занять пару секунд, поэтому выполняем её не в главном потоке.
        new Thread(() -> {
            boolean ok = false;
            String error = null;
            try {
                if (display != null) {
                    display.release();
                }
                if (region != null) {
                    region.release();
                }
                ok = enc != null && enc.stop();
                if (!ok && enc != null && enc.getFailure() != null) {
                    error = enc.getFailure().getMessage();
                }
            } catch (Exception e) {
                Log.e(TAG, "Stop failed", e);
                error = e.getMessage();
            }
            try {
                if (proj != null) {
                    if (callback != null) {
                        proj.unregisterCallback(callback);
                    }
                    proj.stop();
                }
            } catch (Exception e) {
                Log.w(TAG, "Projection stop failed", e);
            }
            if (out != null) {
                out.finish(ok);
            }
            final boolean saved = ok;
            final String err = error;
            main.post(() -> onStopped(saved, out, err));
        }, "recorder-stop").start();
    }

    private void onStopped(boolean saved, OutputTarget out, String error) {
        String text;
        if (saved && out != null) {
            text = out.usedFallback
                    ? localized.getString(R.string.toast_saved_fallback, out.displayPath)
                    : localized.getString(R.string.toast_saved, out.displayPath);
        } else if (error != null) {
            text = localized.getString(R.string.toast_save_failed, error);
        } else {
            text = localized.getString(R.string.toast_empty);
        }
        RecorderState.message = text;
        RecorderState.finalizing = false;
        toast(text);

        virtualDisplay = null;
        renderer = null;
        encoder = null;
        projection = null;
        projectionCallback = null;
        output = null;
        stopping = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** Аварийное освобождение без сохранения результата. */
    private void quietRelease() {
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
            }
        } catch (Exception ignored) {
            // уже освобождён
        }
        try {
            if (renderer != null) {
                renderer.release();
            }
        } catch (Exception ignored) {
            // уже освобождён
        }
        try {
            if (encoder != null) {
                encoder.abort();
            }
        } catch (Exception ignored) {
            // уже освобождён
        }
        try {
            if (projection != null) {
                if (projectionCallback != null) {
                    projection.unregisterCallback(projectionCallback);
                }
                projection.stop();
            }
        } catch (Exception ignored) {
            // уже остановлена
        }
        virtualDisplay = null;
        renderer = null;
        encoder = null;
        projection = null;
        projectionCallback = null;
    }

    // ------------------------------------------------------------------ утилиты

    private Point screenSize() {
        Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        Point size = new Point();
        display.getRealSize(size);
        return size;
    }

    /** Вписывает размер в 1920×1080 (с сохранением пропорций), без увеличения. */
    private static int[] fit(int w, int h) {
        float scale = Math.min(1f, Math.min(
                MAX_LONG_SIDE / Math.max(w, h),
                MAX_SHORT_SIDE / Math.min(w, h)));
        return new int[]{
                ScreenEncoder.even(Math.round(w * scale)),
                ScreenEncoder.even(Math.round(h * scale))};
    }

    private void toast(String text) {
        main.post(() -> Toast.makeText(localized, text, Toast.LENGTH_LONG).show());
    }
}
