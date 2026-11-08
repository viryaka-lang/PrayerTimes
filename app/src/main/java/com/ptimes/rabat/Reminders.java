package com.ptimes.rabat;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Reminders an hour before a prayer: Duha before Dhuhr, the Friday hour of response before
 * Maghrib, and Qiyam al-layl before Fajr. Each can be turned off in settings; all start on.
 *
 * Shown like {@link PrayerAlerts} (phone and Android Auto) but on their own channel. One alarm
 * is pending at a time; each reminder schedules the next.
 */
public final class Reminders {

    private static final String TAG = "Reminders";
    static final String ACTION_REMINDER = "com.ptimes.rabat.action.REMINDER";
    private static final String EXTRA_KIND = "kind";
    private static final String EXTRA_TIME = "time";

    static final int DUHA = 0;
    static final int ISTIJABA = 1;
    static final int QIYAM = 2;
    static final int COUNT = 3;

    /** The prayer each reminder comes before. */
    private static final int[] BEFORE = {PrayerData.DHUHR, PrayerData.MAGHRIB, PrayerData.FAJR};
    static final int[] TITLES = {
            R.string.reminder_duha, R.string.reminder_istijaba, R.string.reminder_qiyam};
    static final int[] DESCRIPTIONS = {
            R.string.reminder_duha_desc, R.string.reminder_istijaba_desc, R.string.reminder_qiyam_desc};

    static final long LEAD_MINUTES = 60;
    private static final String CHANNEL = "reminders";
    private static final int NOTIFICATION_ID = 2;

    private Reminders() {}

    private static PendingIntent alarmIntent(Context context, Intent extras) {
        Intent intent = new Intent(context, PrayerWidget.class).setAction(ACTION_REMINDER);
        if (extras != null) intent.putExtras(extras);
        return PendingIntent.getBroadcast(context, 3, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static boolean applies(int kind, LocalDate date) {
        return kind != ISTIJABA || date.getDayOfWeek() == DayOfWeek.FRIDAY;
    }

    /** Arms the alarm for the next enabled reminder, or cancels it when all are off. */
    public static void schedule(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        Instant now = PrayerData.now();
        LocalDate today = PrayerData.today(context);
        int kind = -1;
        Instant at = null;
        // A week ahead covers the Friday one.
        for (int d = 0; d <= 7 && at == null; d++) {
            PrayerData.Day day = PrayerData.day(context, today.plusDays(d));
            if (day == null) continue;
            for (int k = 0; k < COUNT; k++) {
                if (!AppSettings.reminderEnabled(context, k) || !applies(k, day.date)) continue;
                Instant t = day.times[BEFORE[k]].minusSeconds(LEAD_MINUTES * 60);
                if (t.isAfter(now) && (at == null || t.isBefore(at))) {
                    kind = k;
                    at = t;
                }
            }
        }
        if (at == null) {
            alarms.cancel(alarmIntent(context, null));
            return;
        }
        Intent extras = new Intent()
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_TIME, at.toEpochMilli());
        PendingIntent pi = alarmIntent(context, extras);
        long realAt = PrayerData.toRealMillis(at);
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, realAt, pi);
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, realAt, pi);
        }
    }

    /** The alarm fired: show the reminder if it's still on and timely, then arm the next one. */
    static void onAlarm(Context context, Intent intent) {
        Log.i(TAG, "alarm: extras=" + intent.getExtras() + " now=" + PrayerData.now());
        if (intent.hasExtra(EXTRA_TIME)) {
            int kind = intent.getIntExtra(EXTRA_KIND, 0);
            Instant prayer = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_TIME, 0))
                    .plusSeconds(LEAD_MINUTES * 60);
            // Skip it once the prayer it leads up to has come, e.g. after the phone was off.
            if (AppSettings.reminderEnabled(context, kind) && PrayerData.now().isBefore(prayer)) {
                show(context, kind, prayer);
            }
        }
        schedule(context);
    }

    private static void show(Context base, int kind, Instant prayer) {
        Context context = AppSettings.wrap(base);
        String title = context.getString(TITLES[kind]);
        String text = PrayerData.name(context, BEFORE[kind]) + " "
                + context.getString(R.string.at_time, PrayerData.formatTime(context, prayer))
                + " · " + context.getString(R.string.city);
        long timeoutMs = PrayerData.toRealMillis(prayer) - System.currentTimeMillis();
        PrayerAlerts.post(context, CHANNEL, R.string.reminders_channel_name,
                R.string.reminders_channel_desc, NOTIFICATION_ID, title, text, timeoutMs);
    }
}
