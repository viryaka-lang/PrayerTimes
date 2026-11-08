package com.ptimes.rabat;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.text.format.DateFormat;

import java.util.Locale;

/**
 * User choices: app language (follow the phone, English, or Arabic-only) and clock format.
 *
 * Every screen, the widget and the Android Auto screen read text through
 * {@link #wrap(Context)}, so the language applies everywhere at once.
 */
public final class AppSettings {

    public static final String LANG_SYSTEM = "system";
    public static final String LANG_EN = "en";
    public static final String LANG_AR = "ar";

    public static final String CLOCK_SYSTEM = "system";
    public static final String CLOCK_24 = "24";
    public static final String CLOCK_12 = "12";

    private static final String PREFS = "settings";
    private static final String KEY_LANG = "lang";
    private static final String KEY_CLOCK = "clock";
    private static final String KEY_ALERTS = "alerts";
    private static final String KEY_REMINDER = "reminder_";
    private static final String KEY_ASKED_NOTIFY = "asked_notify";

    // Morocco writes Arabic with Western digits; ar-MA keeps them in dates and numbers.
    private static final Locale ARABIC = new Locale("ar", "MA");

    private AppSettings() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String language(Context context) {
        return prefs(context).getString(KEY_LANG, LANG_SYSTEM);
    }

    public static void setLanguage(Context context, String value) {
        prefs(context).edit().putString(KEY_LANG, value).apply();
    }

    public static String clock(Context context) {
        return prefs(context).getString(KEY_CLOCK, CLOCK_SYSTEM);
    }

    public static void setClock(Context context, String value) {
        prefs(context).edit().putString(KEY_CLOCK, value).apply();
    }

    /** Pop-up alerts before and at each prayer (phone and Android Auto). */
    public static boolean alertsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ALERTS, true);
    }

    public static void setAlertsEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_ALERTS, value).apply();
    }

    /** One of the {@link Reminders} kinds; all start on. */
    public static boolean reminderEnabled(Context context, int kind) {
        return prefs(context).getBoolean(KEY_REMINDER + kind, true);
    }

    public static void setReminderEnabled(Context context, int kind, boolean value) {
        prefs(context).edit().putBoolean(KEY_REMINDER + kind, value).apply();
    }

    /** Whether any alert or reminder is on, so notifications are needed. */
    public static boolean anyNotificationsEnabled(Context context) {
        if (alertsEnabled(context)) return true;
        for (int k = 0; k < Reminders.COUNT; k++) {
            if (reminderEnabled(context, k)) return true;
        }
        return false;
    }

    /** True the first time only: the app asks for notifications once by itself. */
    public static boolean firstNotifyRequest(Context context) {
        if (prefs(context).getBoolean(KEY_ASKED_NOTIFY, false)) return false;
        prefs(context).edit().putBoolean(KEY_ASKED_NOTIFY, true).apply();
        return true;
    }

    public static Locale locale(Context context) {
        switch (language(context)) {
            case LANG_AR:
                return ARABIC;
            case LANG_EN:
                return Locale.ENGLISH;
            default:
                Locale system = Resources.getSystem().getConfiguration().getLocales().get(0);
                return "ar".equals(system.getLanguage()) ? ARABIC : system;
        }
    }

    public static boolean isArabic(Context context) {
        return "ar".equals(locale(context).getLanguage());
    }

    public static boolean is24Hour(Context context) {
        switch (clock(context)) {
            case CLOCK_24:
                return true;
            case CLOCK_12:
                return false;
            default:
                return DateFormat.is24HourFormat(context);
        }
    }

    /** A context whose resources, text direction and formatting follow the chosen language. */
    public static Context wrap(Context base) {
        Locale locale = locale(base);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        config.setLayoutDirection(locale);
        return base.createConfigurationContext(config);
    }

    /** Changes whenever a setting that affects rendered text changes. */
    public static String signature(Context context) {
        return language(context) + "|" + clock(context) + "|" + locale(context).toLanguageTag()
                + "|" + is24Hour(context);
    }
}
