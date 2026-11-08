package com.ptimes.rabat;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.car.app.notification.CarAppExtender;
import androidx.car.app.notification.CarNotificationManager;
import androidx.car.app.notification.CarPendingIntent;
import androidx.car.app.model.CarColor;
import androidx.core.app.NotificationCompat;

import com.ptimes.rabat.car.PrayerCarAppService;

import java.time.Instant;

/**
 * Pop-up alerts 10 minutes before and at each of the five prayers.
 *
 * Posted through the Car App Library, so besides the phone notification Android Auto shows
 * them as a heads-up banner over whatever is on the car screen, Maps included. One alarm
 * is pending at a time; each alert schedules the next.
 */
public final class PrayerAlerts {

    private static final String TAG = "PrayerAlerts";
    static final String ACTION_ALERT = "com.ptimes.rabat.action.ALERT";
    private static final String EXTRA_INDEX = "index";
    private static final String EXTRA_TIME = "time";
    private static final String EXTRA_EARLY = "early";

    private static final String CHANNEL = "prayer_alerts";
    private static final int NOTIFICATION_ID = 1;
    static final long LEAD_MINUTES = 10;

    private PrayerAlerts() {}

    public static boolean canNotify(Context context) {
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private static PendingIntent alarmIntent(Context context, Intent extras) {
        Intent intent = new Intent(context, PrayerWidget.class).setAction(ACTION_ALERT);
        if (extras != null) intent.putExtras(extras);
        return PendingIntent.getBroadcast(context, 1, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Arms the alarm for the next alert, or cancels it when alerts are off. */
    public static void schedule(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (!AppSettings.alertsEnabled(context)) {
            alarms.cancel(alarmIntent(context, null));
            return;
        }
        Instant now = PrayerData.now();
        PrayerData.Next next = PrayerData.nextPrayer(context, now);
        if (next == null) return;

        Instant early = next.time().minusSeconds(LEAD_MINUTES * 60);
        boolean isEarly = early.isAfter(now);
        Instant at = isEarly ? early : next.time();

        Intent extras = new Intent()
                .putExtra(EXTRA_INDEX, next.index)
                .putExtra(EXTRA_TIME, next.time().toEpochMilli())
                .putExtra(EXTRA_EARLY, isEarly);
        PendingIntent pi = alarmIntent(context, extras);
        long realAt = PrayerData.toRealMillis(at);
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, realAt, pi);
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, realAt, pi);
        }
    }

    /** The alarm fired: show the alert if it's still timely, then arm the next one. */
    static void onAlarm(Context context, Intent intent) {
        boolean enabled = AppSettings.alertsEnabled(context);
        Log.i(TAG, "alarm: enabled=" + enabled + " extras=" + intent.getExtras() + " now=" + PrayerData.now());
        if (enabled && intent.hasExtra(EXTRA_TIME)) {
            int index = intent.getIntExtra(EXTRA_INDEX, 0);
            Instant time = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_TIME, 0));
            boolean early = intent.getBooleanExtra(EXTRA_EARLY, false);
            Instant now = PrayerData.now();
            // Skip alerts that arrive late, e.g. after the phone was off.
            Instant deadline = early ? time : time.plusSeconds(5 * 60);
            if (now.isBefore(deadline)) show(context, index, time, early);
        }
        schedule(context);
    }

    private static void show(Context base, int index, Instant time, boolean early) {
        Context context = AppSettings.wrap(base);
        String name = PrayerData.name(context, index);
        String title = early
                ? context.getString(R.string.alert_soon, name, LEAD_MINUTES)
                : context.getString(R.string.alert_now, name);
        String text = PrayerData.formatTime(context, time) + " · " + context.getString(R.string.city);
        // The "soon" alert gives way to the "now" one; the "now" one clears with the count-up.
        long timeoutMs = (early ? LEAD_MINUTES * 60 : PrayerData.SINCE_WINDOW_SECONDS) * 1000;
        post(context, CHANNEL, R.string.channel_name, R.string.channel_desc,
                NOTIFICATION_ID, title, text, timeoutMs);
    }

    /** Posts a pop-up on the phone and, through the Car App Library, over the Android Auto screen. */
    static void post(Context context, String channel, int channelName, int channelDesc, int id,
                     String title, String text, long timeoutMs) {
        if (!canNotify(context)) return;
        ensureChannel(context, channel, channelName, channelDesc);

        PendingIntent openPhone = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent openCar = CarPendingIntent.getCarApp(context, 0,
                new Intent().setComponent(new ComponentName(context, PrayerCarAppService.class)), 0);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_crescent)
                .setColor(context.getColor(R.color.gold_dark))
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setTimeoutAfter(timeoutMs)
                .setContentIntent(openPhone)
                .extend(new CarAppExtender.Builder()
                        .setContentTitle(title)
                        .setContentText(text)
                        .setSmallIcon(R.drawable.ic_crescent)
                        .setColor(CarColor.createCustom(
                                context.getColor(R.color.gold), context.getColor(R.color.gold_dark)))
                        .setImportance(NotificationManager.IMPORTANCE_HIGH)
                        .setContentIntent(openCar)
                        .build());
        CarNotificationManager.from(context).notify(id, builder);
    }

    private static void ensureChannel(Context context, String id, int name, int desc) {
        NotificationChannel channel = new NotificationChannel(id,
                context.getString(name), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(desc));
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
}
