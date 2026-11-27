package tv.simple.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Держит плавающий значок на экране. Значок появляется при запуске приложения и остаётся, пока
 * его не скроют: из него можно начать запись, поставить её на паузу и остановить, не возвращаясь
 * в приложение. Сама запись идёт в {@link RecorderService}.
 */
public class BubbleService extends Service {
    private static final String TAG = "BubbleService";

    static final String ACTION_CLOSE = "tv.simple.recorder.action.CLOSE_BUBBLE";
    private static final String CHANNEL_ID = "bubble";
    private static final int NOTIFICATION_ID = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Context localized;
    private OverlayController overlay;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (overlay == null) {
                return;
            }
            overlay.update(currentMode(), RecorderState.elapsedMs());
            main.postDelayed(this, 500);
        }
    };

    /** Показывает значок, если он включён в настройках и выдано разрешение. Можно звать повторно. */
    static void start(Context context) {
        if (!Prefs.isOverlayEnabled(context) || !OverlayController.canDraw(context)) {
            return;
        }
        try {
            context.startForegroundService(new Intent(context, BubbleService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot start the bubble", e);
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, BubbleService.class));
    }

    private static OverlayController.Mode currentMode() {
        if (RecorderState.finalizing) {
            return OverlayController.Mode.SAVING;
        }
        if (RecorderState.capturing) {
            return RecorderState.paused ? OverlayController.Mode.PAUSED
                    : OverlayController.Mode.RECORDING;
        }
        return OverlayController.Mode.IDLE;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        localized = LocaleHelper.wrap(this);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                localized.getString(R.string.bubble_channel), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CLOSE.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            enterForeground();
        } catch (RuntimeException e) {
            Log.e(TAG, "startForeground failed", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (overlay == null) {
            overlay = new OverlayController(this, localized, new OverlayController.Listener() {
                @Override
                public void onRecord() {
                    startCapture();
                }

                @Override
                public void onTogglePause() {
                    startService(RecorderService.togglePauseIntent(BubbleService.this));
                }

                @Override
                public void onStop() {
                    startService(RecorderService.stopIntent(BubbleService.this));
                }

                @Override
                public void onClose() {
                    stopSelf();
                }
            });
            if (!overlay.show()) {
                overlay = null;
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        main.removeCallbacks(tick);
        main.post(tick);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(tick);
        if (overlay != null) {
            overlay.hide();
            overlay = null;
        }
        super.onDestroy();
    }

    /** Запись из значка: прозрачный экран показывает системный запрос поверх текущего экрана. */
    private void startCapture() {
        try {
            startActivity(new Intent(this, CaptureActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot open the capture screen", e);
        }
    }

    private void enterForeground() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent close = PendingIntent.getService(this, 3,
                new Intent(this, BubbleService.class).setAction(ACTION_CLOSE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_rec)
                .setContentTitle(localized.getString(R.string.bubble_notif_title))
                .setContentText(localized.getString(R.string.bubble_notif_text))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_close),
                        localized.getString(R.string.bubble_notif_close), close).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }
}
