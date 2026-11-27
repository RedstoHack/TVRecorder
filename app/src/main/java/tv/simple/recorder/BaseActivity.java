package tv.simple.recorder;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;

/** Общая база экранов: язык, тема и анимация фокуса для пульта. */
abstract class BaseActivity extends Activity {
    private String createdLang;
    private String createdTheme;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        createdLang = Prefs.getLanguage(this);
        createdTheme = Prefs.getTheme(this);
        setTheme(Prefs.isDark(this) ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyConfigChanges();
    }

    /** Если язык или тема поменялись (например, в настройках), пересоздаёт экран. */
    protected void applyConfigChanges() {
        if (!Prefs.getLanguage(this).equals(createdLang)
                || !Prefs.getTheme(this).equals(createdTheme)) {
            recreate();
        }
    }

    protected int themeColor(int attr) {
        return themeColor(this, attr);
    }

    static int themeColor(Context context, int attr) {
        TypedValue tv = new TypedValue();
        Resources.Theme theme = context.getTheme();
        theme.resolveAttribute(attr, tv, true);
        return tv.data;
    }

    /** Плавно увеличивает элемент, когда на нём фокус пульта. */
    protected static void attachFocusScale(View view, final float scale) {
        view.setOnFocusChangeListener((v, hasFocus) -> v.animate()
                .scaleX(hasFocus ? scale : 1f)
                .scaleY(hasFocus ? scale : 1f)
                .setDuration(120)
                .start());
    }
}
