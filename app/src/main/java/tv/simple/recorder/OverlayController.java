package tv.simple.recorder;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Плавающий значок поверх всех приложений.
 * <ul>
 *   <li>Готов к записи: серая точка. Нажатие раскрывает «Запись» и «Скрыть».</li>
 *   <li>Запись и пауза: красная точка или жёлтая пауза и таймер. Нажатие раскрывает «Пауза» и «Стоп».</li>
 * </ul>
 * Значок можно перетаскивать. Окно помечено FLAG_SECURE: система не включает такие окна в запись
 * экрана, поэтому значок виден пользователю, но в видео не попадает. На части устройств это
 * поведение может отличаться.
 */
final class OverlayController {
    private static final String TAG = "OverlayController";
    private static final long COLLAPSE_MS = 4000;

    enum Mode {IDLE, RECORDING, PAUSED, SAVING}

    interface Listener {
        void onRecord();

        void onTogglePause();

        void onStop();

        void onClose();
    }

    private final Context service;
    private final Context ui;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WindowManager windowManager;

    private WindowManager.LayoutParams params;
    private View root;
    private ImageView dot;
    private TextView time;
    private View controls;
    private ImageButton recordButton;
    private ImageButton closeButton;
    private ImageButton pauseButton;
    private ImageButton stopButton;

    private Mode mode = Mode.IDLE;
    private boolean expanded;
    private float downX;
    private float downY;
    private int startX;
    private int startY;
    private boolean moved;

    private final Runnable collapse = () -> setExpanded(false);

    /**
     * @param service сервис значка (для WindowManager)
     * @param ui      контекст с выбранным в приложении языком (для строк и разметки)
     */
    OverlayController(Context service, Context ui, Listener listener) {
        this.service = service;
        this.ui = ui;
        this.listener = listener;
        this.windowManager = service.getSystemService(WindowManager.class);
    }

    /** Есть ли разрешение «Поверх других окон». */
    static boolean canDraw(Context context) {
        return Settings.canDrawOverlays(context);
    }

    /**
     * Открывает системный экран с разрешением «Поверх других окон».
     *
     * @return false, если на устройстве такого экрана нет (так бывает на части ТВ)
     */
    @SuppressWarnings("deprecation")
    static boolean requestPermission(Activity activity, int requestCode) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + activity.getPackageName()));
        try {
            activity.startActivityForResult(intent, requestCode);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    /** Показывает значок. false, если окно создать не удалось. */
    boolean show() {
        if (root != null) {
            return true;
        }
        if (!canDraw(service) || windowManager == null) {
            return false;
        }
        try {
            root = LayoutInflater.from(ui).inflate(R.layout.overlay_bubble, null);
            dot = root.findViewById(R.id.ov_dot);
            time = root.findViewById(R.id.ov_time);
            controls = root.findViewById(R.id.ov_controls);
            recordButton = root.findViewById(R.id.ov_record);
            closeButton = root.findViewById(R.id.ov_close);
            pauseButton = root.findViewById(R.id.ov_pause);
            stopButton = root.findViewById(R.id.ov_stop);

            recordButton.setOnClickListener(v -> {
                setExpanded(false);
                listener.onRecord();
            });
            closeButton.setOnClickListener(v -> listener.onClose());
            pauseButton.setOnClickListener(v -> {
                listener.onTogglePause();
                scheduleCollapse();
            });
            stopButton.setOnClickListener(v -> listener.onStop());
            root.setOnTouchListener(this::onTouch);
            root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol || b - t != ob - ot) {
                    applyClamped();
                }
            });
            applyMode(Mode.IDLE, 0);

            DisplayMetrics dm = service.getResources().getDisplayMetrics();
            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_SECURE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            int[] saved = Prefs.getOverlayPos(service);
            params.x = saved != null ? saved[0] : Math.round(24 * dm.density);
            params.y = saved != null ? saved[1] : Math.round(24 * dm.density);
            clamp(Math.round(160 * dm.density), Math.round(48 * dm.density));

            windowManager.addView(root, params);
            return true;
        } catch (RuntimeException e) {
            // Нет разрешения, окно запрещено прошивкой и т. п.
            Log.w(TAG, "Cannot show the overlay", e);
            root = null;
            return false;
        }
    }

    void hide() {
        handler.removeCallbacks(collapse);
        if (root == null) {
            return;
        }
        try {
            windowManager.removeView(root);
        } catch (RuntimeException e) {
            Log.w(TAG, "removeView failed", e);
        }
        root = null;
    }

    /** Обновляет вид под состояние записи. Вызывается примерно дважды в секунду. */
    void update(Mode newMode, long elapsedMs) {
        if (root == null) {
            return;
        }
        applyMode(newMode, elapsedMs);
    }

    private void applyMode(Mode newMode, long elapsedMs) {
        boolean changed = newMode != mode || controls.getTag() == null;
        mode = newMode;
        controls.setTag(Boolean.TRUE);
        switch (newMode) {
            case RECORDING:
            case PAUSED:
                time.setText(RecorderState.format(elapsedMs));
                break;
            case SAVING:
                time.setText(R.string.bubble_saving);
                break;
            default:
                time.setText(R.string.bubble_idle);
                break;
        }
        if (!changed) {
            return;
        }
        int dotRes = newMode == Mode.RECORDING ? R.drawable.ic_overlay_dot
                : newMode == Mode.PAUSED ? R.drawable.ic_overlay_paused
                : R.drawable.ic_overlay_idle;
        dot.setImageResource(dotRes);
        boolean idle = newMode == Mode.IDLE;
        boolean active = newMode == Mode.RECORDING || newMode == Mode.PAUSED;
        recordButton.setVisibility(idle ? View.VISIBLE : View.GONE);
        closeButton.setVisibility(idle ? View.VISIBLE : View.GONE);
        pauseButton.setVisibility(active ? View.VISIBLE : View.GONE);
        stopButton.setVisibility(active ? View.VISIBLE : View.GONE);
        boolean paused = newMode == Mode.PAUSED;
        pauseButton.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
        pauseButton.setContentDescription(
                ui.getString(paused ? R.string.notif_resume : R.string.notif_pause));
        refreshControls();
    }

    // ------------------------------------------------------------------ касания

    private boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                startX = params.x;
                startY = params.y;
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                int slop = ViewConfiguration.get(ui).getScaledTouchSlop();
                if (!moved && Math.hypot(dx, dy) > slop) {
                    moved = true;
                }
                if (moved) {
                    params.x = startX + Math.round(dx);
                    params.y = startY + Math.round(dy);
                    applyClamped();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (moved) {
                    Prefs.setOverlayPos(service, params.x, params.y);
                } else {
                    setExpanded(!expanded);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return false;
        }
    }

    private void setExpanded(boolean value) {
        if (root == null) {
            return;
        }
        expanded = value;
        refreshControls();
        handler.removeCallbacks(collapse);
        if (value) {
            scheduleCollapse();
        }
    }

    /** Кнопки видны, если значок раскрыт и идёт не сохранение файла. */
    private void refreshControls() {
        controls.setVisibility(expanded && mode != Mode.SAVING ? View.VISIBLE : View.GONE);
    }

    private void scheduleCollapse() {
        handler.removeCallbacks(collapse);
        handler.postDelayed(collapse, COLLAPSE_MS);
    }

    // ---------------------------------------------------------------- положение

    /** Не даёт значку уйти за край экрана. */
    private void clamp(int width, int height) {
        DisplayMetrics dm = service.getResources().getDisplayMetrics();
        params.x = Math.max(0, Math.min(params.x, dm.widthPixels - width));
        params.y = Math.max(0, Math.min(params.y, dm.heightPixels - height));
    }

    private void applyClamped() {
        if (root == null) {
            return;
        }
        int x = params.x;
        int y = params.y;
        clamp(root.getWidth(), root.getHeight());
        try {
            windowManager.updateViewLayout(root, params);
        } catch (RuntimeException e) {
            Log.w(TAG, "updateViewLayout failed", e);
        }
        if (x != params.x || y != params.y) {
            // Значок упёрся в край экрана: палец и значок снова двигаются синхронно.
            startX += params.x - x;
            startY += params.y - y;
        }
    }
}
