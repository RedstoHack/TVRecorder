package tv.simple.recorder;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

/** Главный экран: «Запись» и «Настройки», во время записи ещё «Пауза». */
public class MainActivity extends BaseActivity {
    private static final int REQ_OVERLAY = 1001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Button btnRecord;
    private Button btnPause;
    private TextView status;
    /** Какой значок сейчас на кнопке паузы: null до первого показа. */
    private Boolean pauseIconIsPlay;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateUi();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnRecord = findViewById(R.id.btn_record);
        btnPause = findViewById(R.id.btn_pause);
        Button btnSettings = findViewById(R.id.btn_settings);
        status = findViewById(R.id.status);
        TextView footer = findViewById(R.id.footer);

        footer.setText(getString(R.string.main_footer, versionName(), getPackageName()));

        btnRecord.setOnClickListener(v -> onRecordClicked());
        btnPause.setOnClickListener(v -> startService(RecorderService.togglePauseIntent(this)));
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        attachFocusScale(btnRecord, 1.05f);
        attachFocusScale(btnPause, 1.05f);
        attachFocusScale(btnSettings, 1.05f);
        btnRecord.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
        ensureBubble();
    }

    /**
     * Плавающий значок появляется сразу при открытии приложения. Если разрешения «Поверх других
     * окон» ещё нет, один раз открываем его системный экран; после возврата значок покажется сам.
     */
    private void ensureBubble() {
        if (!Prefs.isOverlayEnabled(this)) {
            return;
        }
        if (OverlayController.canDraw(this)) {
            BubbleService.start(this);
        } else if (!Prefs.wasOverlayAsked(this)) {
            Prefs.setOverlayAsked(this);
            OverlayController.requestPermission(this, REQ_OVERLAY);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    private String versionName() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return name != null ? name : "1.0";
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0";
        }
    }

    private void updateUi() {
        boolean recording = RecorderState.recording;
        boolean capturing = RecorderState.capturing;
        boolean paused = RecorderState.paused;
        boolean finalizing = RecorderState.finalizing;

        btnRecord.setText(recording ? R.string.main_stop : R.string.main_record);
        btnRecord.setEnabled(!finalizing);

        boolean showPause = capturing && !finalizing;
        btnPause.setVisibility(showPause ? Button.VISIBLE : Button.GONE);
        if (showPause) {
            btnPause.setText(paused ? R.string.main_resume : R.string.main_pause);
            setPauseIcon(paused);
        }

        String text;
        if (finalizing) {
            text = getString(R.string.main_status_saving);
        } else if (capturing) {
            String time = RecorderState.format(RecorderState.elapsedMs());
            text = getString(paused ? R.string.main_status_paused
                    : R.string.main_status_recording, time);
        } else if (RecorderState.message != null) {
            text = RecorderState.message;
        } else {
            text = getString(R.string.main_status_idle);
        }
        status.setText(text);
    }

    /** Значок меняем явно вместе с цветом, чтобы он был виден и на светлой теме. */
    private void setPauseIcon(boolean paused) {
        if (pauseIconIsPlay != null && pauseIconIsPlay == paused) {
            return;
        }
        pauseIconIsPlay = paused;
        Drawable icon = getDrawable(paused ? R.drawable.ic_play : R.drawable.ic_pause).mutate();
        btnPause.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
        btnPause.setCompoundDrawableTintList(ColorStateList.valueOf(themeColor(R.attr.appText)));
    }

    // -------------------------------------------------------------- запуск записи

    private void onRecordClicked() {
        if (RecorderState.finalizing) {
            return;
        }
        if (RecorderState.recording) {
            startService(RecorderService.stopIntent(this));
            return;
        }
        // Разрешения и системный запрос на захват экрана: тот же путь, что и у значка на экране.
        startActivity(new Intent(this, CaptureActivity.class));
    }
}
