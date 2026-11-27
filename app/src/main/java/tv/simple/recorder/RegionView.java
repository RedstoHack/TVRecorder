package tv.simple.recorder;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** Рамка, которую двигают и растягивают стрелками пульта. */
final class RegionView extends View {
    /** Минимальный размер зоны, доля от экрана по каждой стороне. */
    private static final float MIN_SIZE = 0.15f;

    private final RectF zone = new RectF(0.25f, 0.25f, 0.75f, 0.75f);
    private final Point realSize = new Point(1920, 1080);
    private boolean resizeMode;

    private static final int DRAG_NONE = 0;
    private static final int DRAG_MOVE = 1;
    private static final int DRAG_RESIZE = 2;
    private int drag = DRAG_NONE;
    private float lastX;
    private float lastY;
    /** true на телефонах и планшетах: зону двигают пальцем, а не пультом. */
    private final boolean touchDevice;

    private final float dp;
    private final Paint screenFrame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint helpText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint infoText = new Paint(Paint.ANTI_ALIAS_FLAG);

    @SuppressWarnings("deprecation")
    RegionView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        setFocusable(true);

        int accent = BaseActivity.themeColor(context, R.attr.appAccent);
        int text = BaseActivity.themeColor(context, R.attr.appText);
        int secondary = BaseActivity.themeColor(context, R.attr.appTextSecondary);
        int stroke = BaseActivity.themeColor(context, R.attr.appStroke);
        int bg = BaseActivity.themeColor(context, R.attr.appBg);

        screenFrame.setStyle(Paint.Style.STROKE);
        screenFrame.setStrokeWidth(2 * dp);
        screenFrame.setColor(stroke);

        fill.setStyle(Paint.Style.FILL);
        fill.setColor((accent & 0x00FFFFFF) | 0x38000000);

        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(4 * dp);
        border.setColor(accent);

        guide.setStyle(Paint.Style.STROKE);
        guide.setStrokeWidth(dp);
        guide.setColor((accent & 0x00FFFFFF) | 0x66000000);

        handle.setStyle(Paint.Style.FILL);
        handle.setColor(text);

        plate.setStyle(Paint.Style.FILL);
        plate.setColor((bg & 0x00FFFFFF) | 0xE6000000);

        helpText.setColor(secondary);
        helpText.setTextAlign(Paint.Align.CENTER);
        helpText.setTextSize(getResources().getDimension(R.dimen.picker_help_size));

        infoText.setColor(text);
        infoText.setTextAlign(Paint.Align.CENTER);
        infoText.setTextSize(getResources().getDimension(R.dimen.picker_info_size));
        infoText.setFakeBoldText(true);

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        display.getRealSize(realSize);

        UiModeManager uiMode = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        boolean tv = uiMode != null
                && uiMode.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        touchDevice = !tv && context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    /** Касание: тянем за правый нижний угол, чтобы изменить размер, или внутри рамки, чтобы переместить. */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float grab = 48 * dp;
                if (Math.abs(x - zone.right * w) <= grab && Math.abs(y - zone.bottom * h) <= grab) {
                    drag = DRAG_RESIZE;
                } else if (zone.contains(x / w, y / h)) {
                    drag = DRAG_MOVE;
                } else {
                    drag = DRAG_NONE;
                }
                lastX = x;
                lastY = y;
                resizeMode = drag == DRAG_RESIZE;
                invalidate();
                return drag != DRAG_NONE;
            }
            case MotionEvent.ACTION_MOVE:
                if (drag == DRAG_NONE) {
                    return false;
                }
                nudge((x - lastX) / w, (y - lastY) / h);
                lastX = x;
                lastY = y;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean was = drag != DRAG_NONE;
                drag = DRAG_NONE;
                resizeMode = false;
                invalidate();
                return was;
            default:
                return false;
        }
    }

    void setZone(RectF z) {
        zone.set(z);
        invalidate();
    }

    RectF getZone() {
        return new RectF(zone);
    }

    /** Обрабатывает кнопки пульта. Возвращает true, если нажатие использовано. */
    boolean handleKey(int keyCode, KeyEvent event) {
        // При удержании кнопки шаг растёт: от 0,5% до 7% экрана за нажатие.
        float step = 0.005f * (1 + Math.min(12, event.getRepeatCount() / 2));
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                nudge(-step, 0f);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                nudge(step, 0f);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                nudge(0f, -step);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                nudge(0f, step);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (event.getRepeatCount() == 0) {
                    resizeMode = !resizeMode;
                    invalidate();
                }
                return true;
            default:
                return false;
        }
    }

    private void nudge(float dx, float dy) {
        if (resizeMode) {
            zone.right = clamp(zone.right + dx, zone.left + MIN_SIZE, 1f);
            zone.bottom = clamp(zone.bottom + dy, zone.top + MIN_SIZE, 1f);
        } else {
            float w = zone.width();
            float h = zone.height();
            float left = clamp(zone.left + dx, 0f, 1f - w);
            float top = clamp(zone.top + dy, 0f, 1f - h);
            zone.set(left, top, left + w, top + h);
        }
        invalidate();
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();

        canvas.drawRect(dp, dp, w - dp, h - dp, screenFrame);

        float l = zone.left * w;
        float t = zone.top * h;
        float r = zone.right * w;
        float b = zone.bottom * h;

        canvas.drawRect(l, t, r, b, fill);
        float cw = (r - l) / 3f;
        float ch = (b - t) / 3f;
        for (int i = 1; i <= 2; i++) {
            canvas.drawLine(l + cw * i, t, l + cw * i, b, guide);
            canvas.drawLine(l, t + ch * i, r, t + ch * i, guide);
        }
        canvas.drawRect(l, t, r, b, border);
        if (resizeMode || touchDevice) {
            // На сенсорных экранах уголок виден всегда: за него тянут, чтобы изменить размер.
            canvas.drawCircle(r, b, (resizeMode ? 14 : 10) * dp, handle);
        }

        int zoneW = Math.round(zone.width() * realSize.x);
        int zoneH = Math.round(zone.height() * realSize.y);
        String mode = getContext().getString(
                resizeMode ? R.string.picker_mode_resize : R.string.picker_mode_move);
        String help = getContext().getString(
                touchDevice ? R.string.picker_help_touch : R.string.picker_help);
        drawLabel(canvas, help, w / 2f, 72 * dp, helpText);
        drawLabel(canvas, getContext().getString(R.string.picker_info, mode, zoneW, zoneH),
                w / 2f, h - 48 * dp, infoText);
    }

    /** Рисует подпись на плашке. Если текст шире экрана, уменьшает его, чтобы он не обрезался. */
    private void drawLabel(Canvas canvas, String text, float cx, float baseline, Paint paint) {
        float original = paint.getTextSize();
        float available = getWidth() - 48 * dp;
        float width = paint.measureText(text);
        if (available > 0 && width > available) {
            paint.setTextSize(Math.max(original * available / width, 9 * dp));
        }
        float half = Math.min(paint.measureText(text) / 2f + 16 * dp, getWidth() / 2f - 8 * dp);
        Paint.FontMetrics fm = paint.getFontMetrics();
        canvas.drawRoundRect(cx - half, baseline + fm.ascent - 8 * dp,
                cx + half, baseline + fm.descent + 8 * dp, 12 * dp, 12 * dp, plate);
        canvas.drawText(text, cx, baseline, paint);
        paint.setTextSize(original);
    }
}
