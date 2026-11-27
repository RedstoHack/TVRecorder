package tv.simple.recorder;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** Применяет язык, выбранный в настройках приложения, независимо от языка системы. */
final class LocaleHelper {
    private LocaleHelper() {
    }

    static Context wrap(Context base) {
        Locale locale = new Locale(Prefs.getLanguage(base));
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        return base.createConfigurationContext(config);
    }
}
