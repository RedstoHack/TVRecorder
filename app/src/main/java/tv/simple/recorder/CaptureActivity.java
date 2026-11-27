package tv.simple.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Невидимый экран, который запускает запись: спрашивает нужные разрешения, показывает системный
 * запрос на захват экрана и запускает {@link RecorderService}. Один и тот же путь используют
 * кнопка «Запись» в приложении и значок на экране. Сам экран прозрачный, поэтому запрос
 * появляется прямо поверх того, что сейчас на экране, и запись начинается с этого кадра.
 */
public class CaptureActivity extends Activity {
    private static final int REQ_CAPTURE = 1;
    private static final int REQ_STORAGE = 2;
    private static final int REQ_NOTIFICATIONS = 3;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            // Экран пересоздан во время запроса: результат система доставит сама.
            return;
        }
        if (RecorderState.recording || RecorderState.finalizing) {
            finish();
            return;
        }
        checkStorage();
    }

    /** Android 8–9 пишут прямо в общую папку и просят разрешение на память. */
    private void checkStorage() {
        if (Build.VERSION.SDK_INT < 29
                && !Prefs.LOC_CUSTOM.equals(Prefs.getLocation(this))
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_STORAGE);
            return;
        }
        checkNotifications();
    }

    /**
     * На Android 13+ без разрешения на уведомления в шторке не будет кнопок «Пауза» и «Стоп».
     * Спрашиваем один раз; отказ записи не мешает.
     */
    private void checkNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                && !Prefs.wasNotificationsAsked(this)) {
            Prefs.setNotificationsAsked(this);
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
            return;
        }
        requestCapture();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            // Даже без разрешения запись можно начать: файл уйдёт в папку приложения.
            checkNotifications();
        } else if (requestCode == REQ_NOTIFICATIONS) {
            requestCapture();
        }
    }

    @SuppressWarnings("deprecation")
    private void requestCapture() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.err_no_capture, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            startForegroundService(RecorderService.startIntent(this, resultCode, data));
        }
        finish();
    }
}
