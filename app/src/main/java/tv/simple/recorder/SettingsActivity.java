package tv.simple.recorder;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.IntConsumer;

/** Настройки: язык, тема, место сохранения, частота кадров и область записи. */
public class SettingsActivity extends BaseActivity {
    private static final int REQ_PICK_FOLDER = 2001;
    private static final int REQ_OVERLAY = 2002;

    private LinearLayout rows;
    private View rowOverlay;
    private View rowLanguage;
    private View rowTheme;
    private View rowLocation;
    private View rowFps;
    private View rowArea;
    private View rowZone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        rows = findViewById(R.id.rows);

        rowLanguage = addRow(R.string.set_language, v -> chooseLanguage());
        rowTheme = addRow(R.string.set_theme, v -> chooseTheme());
        rowLocation = addRow(R.string.set_location, v -> chooseLocation());
        rowFps = addRow(R.string.set_fps, v -> chooseFps());
        rowArea = addRow(R.string.set_area, v -> chooseArea());
        rowZone = addRow(R.string.set_zone_adjust, v -> openZonePicker());
        rowOverlay = addRow(R.string.set_overlay, v -> chooseOverlay());

        rowLanguage.requestFocus();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private View addRow(int titleRes, View.OnClickListener listener) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_setting, rows, false);
        ((TextView) row.findViewById(R.id.row_title)).setText(titleRes);
        row.setOnClickListener(listener);
        attachFocusScale(row, 1.02f);
        rows.addView(row);
        return row;
    }

    private void setValue(View row, String value) {
        ((TextView) row.findViewById(R.id.row_value)).setText(value);
    }

    private void refresh() {
        setValue(rowLanguage, Prefs.LANG_RU.equals(Prefs.getLanguage(this)) ? "Русский" : "English");
        setValue(rowTheme, getString(Prefs.isDark(this) ? R.string.theme_dark : R.string.theme_light));
        setValue(rowLocation, locationLabel());
        setValue(rowFps, getString(R.string.fps_value, Prefs.getFps(this)));
        setValue(rowOverlay, overlayLabel());

        boolean zoneMode = Prefs.isZoneMode(this);
        setValue(rowArea, getString(zoneMode ? R.string.area_zone : R.string.area_full));
        rowZone.setVisibility(zoneMode ? View.VISIBLE : View.GONE);
        if (zoneMode) {
            RectF zone = Prefs.getZone(this);
            Point screen = realSize();
            setValue(rowZone, getString(R.string.zone_summary,
                    Math.round(zone.width() * screen.x), Math.round(zone.height() * screen.y)));
        }
    }

    @SuppressWarnings("deprecation")
    private Point realSize() {
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        return size;
    }

    private String locationLabel() {
        String location = Prefs.getLocation(this);
        if (Prefs.LOC_CUSTOM.equals(location)) {
            String label = Prefs.getTreeLabel(this);
            return label.isEmpty() ? getString(R.string.loc_pick) : label;
        }
        if (Prefs.LOC_DOWNLOAD.equals(location)) {
            return getString(R.string.loc_download);
        }
        return location;
    }

    // ------------------------------------------------------------------ выбор

    private void choose(int titleRes, String[] items, int checked, IntConsumer onPick) {
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    dialog.dismiss();
                    onPick.accept(which);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void chooseLanguage() {
        int checked = Prefs.LANG_RU.equals(Prefs.getLanguage(this)) ? 0 : 1;
        choose(R.string.set_language, new String[]{"Русский", "English"}, checked, which -> {
            Prefs.setLanguage(this, which == 0 ? Prefs.LANG_RU : Prefs.LANG_EN);
            applyConfigChanges();
            refresh();
        });
    }

    private void chooseTheme() {
        int checked = Prefs.isDark(this) ? 0 : 1;
        String[] items = {getString(R.string.theme_dark), getString(R.string.theme_light)};
        choose(R.string.set_theme, items, checked, which -> {
            Prefs.setTheme(this, which == 0 ? Prefs.THEME_DARK : Prefs.THEME_LIGHT);
            applyConfigChanges();
            refresh();
        });
    }

    private void chooseLocation() {
        String current = Prefs.getLocation(this);
        int checked;
        switch (current) {
            case Prefs.LOC_DOWNLOAD:
                checked = 0;
                break;
            case Prefs.LOC_MOVIES:
                checked = 1;
                break;
            case Prefs.LOC_DCIM:
                checked = 2;
                break;
            default:
                checked = 3;
                break;
        }
        String customLabel = Prefs.LOC_CUSTOM.equals(current) && !Prefs.getTreeLabel(this).isEmpty()
                ? Prefs.getTreeLabel(this)
                : getString(R.string.loc_pick);
        String[] items = {getString(R.string.loc_download), Prefs.LOC_MOVIES, Prefs.LOC_DCIM,
                customLabel};
        choose(R.string.set_location, items, checked, which -> {
            switch (which) {
                case 0:
                    Prefs.setPresetLocation(this, Prefs.LOC_DOWNLOAD);
                    break;
                case 1:
                    Prefs.setPresetLocation(this, Prefs.LOC_MOVIES);
                    break;
                case 2:
                    Prefs.setPresetLocation(this, Prefs.LOC_DCIM);
                    break;
                default:
                    pickFolder();
                    return;
            }
            refresh();
        });
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_FOLDER);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.err_no_folder_picker, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            if (!OverlayController.canDraw(this)) {
                Prefs.setOverlayEnabled(this, false);
                Toast.makeText(this, R.string.err_overlay_denied, Toast.LENGTH_LONG).show();
            } else {
                BubbleService.start(this);
            }
            refresh();
            return;
        }
        if (requestCode != REQ_PICK_FOLDER || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            // Без постоянного доступа запись всё равно сработает, пока выдан временный.
        }
        Prefs.setCustomLocation(this, tree.toString(), folderLabel(tree));
        refresh();
    }

    /** Из «primary:Movies/Screen» делает «Movies/Screen». */
    private static String folderLabel(Uri tree) {
        String id = tree.getLastPathSegment();
        if (id == null) {
            return "";
        }
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return path.isEmpty() ? id : path;
    }

    private void chooseFps() {
        int[] options = Prefs.FPS_OPTIONS;
        String[] items = new String[options.length];
        int checked = 0;
        for (int i = 0; i < options.length; i++) {
            items[i] = getString(R.string.fps_value, options[i]);
            if (options[i] == Prefs.getFps(this)) {
                checked = i;
            }
        }
        choose(R.string.set_fps, items, checked, which -> {
            Prefs.setFps(this, options[which]);
            refresh();
        });
    }

    private void chooseArea() {
        int checked = Prefs.isZoneMode(this) ? 1 : 0;
        String[] items = {getString(R.string.area_full), getString(R.string.area_zone)};
        choose(R.string.set_area, items, checked, which -> {
            Prefs.setZoneMode(this, which == 1);
            refresh();
            if (which == 1) {
                openZonePicker();
            }
        });
    }

    private void openZonePicker() {
        startActivity(new Intent(this, RegionPickerActivity.class));
    }

    // ------------------------------------------------------------ плавающий таймер

    private String overlayLabel() {
        if (!Prefs.isOverlayEnabled(this)) {
            return getString(R.string.overlay_off);
        }
        return getString(OverlayController.canDraw(this)
                ? R.string.overlay_on : R.string.overlay_need_permission);
    }

    private void chooseOverlay() {
        int checked = Prefs.isOverlayEnabled(this) ? 0 : 1;
        String[] items = {getString(R.string.overlay_on), getString(R.string.overlay_off)};
        choose(R.string.set_overlay, items, checked, which -> {
            if (which == 1) {
                Prefs.setOverlayEnabled(this, false);
                BubbleService.stop(this);
                refresh();
                return;
            }
            Prefs.setOverlayEnabled(this, true);
            BubbleService.start(this);
            refresh();
            if (!OverlayController.canDraw(this)
                    && !OverlayController.requestPermission(this, REQ_OVERLAY)) {
                // На этом устройстве такого экрана нет, таймер недоступен.
                Prefs.setOverlayEnabled(this, false);
                refresh();
                Toast.makeText(this, R.string.err_no_overlay, Toast.LENGTH_LONG).show();
            }
        });
    }
}
