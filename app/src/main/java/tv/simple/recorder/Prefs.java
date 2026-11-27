package tv.simple.recorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.RectF;

import java.util.Locale;

/** Все настройки приложения в одном месте. */
final class Prefs {
    static final String LANG_RU = "ru";
    static final String LANG_EN = "en";

    static final String THEME_DARK = "dark";
    static final String THEME_LIGHT = "light";

    static final String LOC_DOWNLOAD = "Download";
    static final String LOC_MOVIES = "Movies";
    static final String LOC_DCIM = "DCIM";
    static final String LOC_CUSTOM = "custom";

    static final String AREA_FULL = "full";
    static final String AREA_ZONE = "zone";

    static final int[] FPS_OPTIONS = {15, 24, 30, 60, 120};

    private static final String FILE = "tv_recorder_prefs";
    private static final String K_LANG = "lang";
    private static final String K_THEME = "theme";
    private static final String K_LOCATION = "location";
    private static final String K_TREE_URI = "tree_uri";
    private static final String K_TREE_LABEL = "tree_label";
    private static final String K_FPS = "fps";
    private static final String K_AREA = "area";
    private static final String K_ZONE_L = "zone_l";
    private static final String K_ZONE_T = "zone_t";
    private static final String K_ZONE_R = "zone_r";
    private static final String K_ZONE_B = "zone_b";
    private static final String K_NOTIF_ASKED = "notif_asked";
    private static final String K_OVERLAY = "overlay";
    private static final String K_OVERLAY_ASKED = "overlay_asked";
    private static final String K_OVERLAY_X = "overlay_x";
    private static final String K_OVERLAY_Y = "overlay_y";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- Язык ----
    static String getLanguage(Context c) {
        String def = "ru".equals(Locale.getDefault().getLanguage()) ? LANG_RU : LANG_EN;
        return sp(c).getString(K_LANG, def);
    }

    static void setLanguage(Context c, String lang) {
        sp(c).edit().putString(K_LANG, lang).apply();
    }

    // ---- Тема ----
    static String getTheme(Context c) {
        return sp(c).getString(K_THEME, THEME_DARK);
    }

    static boolean isDark(Context c) {
        return !THEME_LIGHT.equals(getTheme(c));
    }

    static void setTheme(Context c, String theme) {
        sp(c).edit().putString(K_THEME, theme).apply();
    }

    // ---- Место сохранения ----
    static String getLocation(Context c) {
        String loc = sp(c).getString(K_LOCATION, LOC_DOWNLOAD);
        if (LOC_CUSTOM.equals(loc) && getTreeUri(c) == null) {
            return LOC_DOWNLOAD;
        }
        return loc;
    }

    static void setPresetLocation(Context c, String loc) {
        sp(c).edit().putString(K_LOCATION, loc).apply();
    }

    static void setCustomLocation(Context c, String treeUri, String label) {
        sp(c).edit()
                .putString(K_LOCATION, LOC_CUSTOM)
                .putString(K_TREE_URI, treeUri)
                .putString(K_TREE_LABEL, label)
                .apply();
    }

    static String getTreeUri(Context c) {
        return sp(c).getString(K_TREE_URI, null);
    }

    static String getTreeLabel(Context c) {
        return sp(c).getString(K_TREE_LABEL, "");
    }

    // ---- Частота кадров ----
    static int getFps(Context c) {
        int fps = sp(c).getInt(K_FPS, 30);
        for (int option : FPS_OPTIONS) {
            if (option == fps) {
                return fps;
            }
        }
        return 30;
    }

    static void setFps(Context c, int fps) {
        sp(c).edit().putInt(K_FPS, fps).apply();
    }

    // ---- Разрешение на уведомления (Android 13+) ----
    static boolean wasNotificationsAsked(Context c) {
        return sp(c).getBoolean(K_NOTIF_ASKED, false);
    }

    static void setNotificationsAsked(Context c) {
        sp(c).edit().putBoolean(K_NOTIF_ASKED, true).apply();
    }

    // ---- Плавающий таймер ----
    /** Включён по умолчанию; работает, только если выдано разрешение «Поверх других окон». */
    static boolean isOverlayEnabled(Context c) {
        return sp(c).getBoolean(K_OVERLAY, true);
    }

    static void setOverlayEnabled(Context c, boolean enabled) {
        sp(c).edit().putBoolean(K_OVERLAY, enabled).apply();
    }

    static boolean wasOverlayAsked(Context c) {
        return sp(c).getBoolean(K_OVERLAY_ASKED, false);
    }

    static void setOverlayAsked(Context c) {
        sp(c).edit().putBoolean(K_OVERLAY_ASKED, true).apply();
    }

    /** Сохранённое место плашки в пикселях {x, y} или null, если её ещё не двигали. */
    static int[] getOverlayPos(Context c) {
        SharedPreferences p = sp(c);
        if (!p.contains(K_OVERLAY_X) || !p.contains(K_OVERLAY_Y)) {
            return null;
        }
        return new int[]{p.getInt(K_OVERLAY_X, 0), p.getInt(K_OVERLAY_Y, 0)};
    }

    static void setOverlayPos(Context c, int x, int y) {
        sp(c).edit().putInt(K_OVERLAY_X, x).putInt(K_OVERLAY_Y, y).apply();
    }

    // ---- Область записи ----
    static boolean isZoneMode(Context c) {
        return AREA_ZONE.equals(sp(c).getString(K_AREA, AREA_FULL));
    }

    static void setZoneMode(Context c, boolean zone) {
        sp(c).edit().putString(K_AREA, zone ? AREA_ZONE : AREA_FULL).apply();
    }

    /** Зона хранится в долях экрана (0..1), поэтому не зависит от разрешения. */
    static RectF getZone(Context c) {
        SharedPreferences p = sp(c);
        RectF z = new RectF(
                p.getFloat(K_ZONE_L, 0.25f),
                p.getFloat(K_ZONE_T, 0.25f),
                p.getFloat(K_ZONE_R, 0.75f),
                p.getFloat(K_ZONE_B, 0.75f));
        boolean valid = z.left >= 0f && z.top >= 0f && z.right <= 1f && z.bottom <= 1f
                && z.width() >= 0.05f && z.height() >= 0.05f;
        return valid ? z : new RectF(0.25f, 0.25f, 0.75f, 0.75f);
    }

    static void setZone(Context c, RectF z) {
        sp(c).edit()
                .putFloat(K_ZONE_L, z.left)
                .putFloat(K_ZONE_T, z.top)
                .putFloat(K_ZONE_R, z.right)
                .putFloat(K_ZONE_B, z.bottom)
                .apply();
    }
}
