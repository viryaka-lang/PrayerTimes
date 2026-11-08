package com.ptimes.rabat;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Home screen widget: the next prayer with a large live countdown, in three sizes.
 *
 * The countdown is a Chronometer, so the launcher ticks it without us waking up.
 * The device is woken only when something important changes: at each listed time, the end
 * of each count-up and Rabat midnight. The progress ring and bars move on a minute tick
 * that never wakes the phone. Both alarms are re-armed after every update.
 */
public class PrayerWidget extends AppWidgetProvider {

    static final String ACTION_REFRESH = "com.ptimes.rabat.action.REFRESH";

    /** XLARGE is the large layout with a bigger countdown, for 5-column and tablet grids. */
    private enum Size { SMALL, WIDE, LARGE, XLARGE }

    private static final int[] COLS = {
            R.id.w_col_0, R.id.w_col_1, R.id.w_col_2, R.id.w_col_3, R.id.w_col_4, R.id.w_col_5};
    private static final int[] COL_NAMES = {
            R.id.w_col_name_0, R.id.w_col_name_1, R.id.w_col_name_2,
            R.id.w_col_name_3, R.id.w_col_name_4, R.id.w_col_name_5};
    private static final int[] COL_TIMES = {
            R.id.w_col_time_0, R.id.w_col_time_1, R.id.w_col_time_2,
            R.id.w_col_time_3, R.id.w_col_time_4, R.id.w_col_time_5};
    private static final int[] DOTS = {
            R.id.w_dot_0, R.id.w_dot_1, R.id.w_dot_2, R.id.w_dot_3, R.id.w_dot_4, R.id.w_dot_5};

    /** Drawn once per update and shared by every size, so RemoteViews ships it once. */
    private static final int RING_DP = 56;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        updateAll(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        manager.updateAppWidget(id, build(context, manager, id));
    }

    @Override
    public void onDisabled(Context context) {
        updateAll(context); // cancels the refresh alarm if no widget of either kind is left
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (action == null) return;
        switch (action) {
            case PrayerAlerts.ACTION_ALERT:
                PrayerAlerts.onAlarm(context, intent);
                break;
            case Reminders.ACTION_REMINDER:
                Reminders.onAlarm(context, intent);
                break;
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_TIME_CHANGED:
            case Intent.ACTION_TIMEZONE_CHANGED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
            case AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED:
                // Alarms are lost on reboot and stale after clock changes.
                PrayerAlerts.schedule(context);
                Reminders.schedule(context);
                HijriUpdate.schedule(context);
                updateAll(context);
                break;
            case ACTION_REFRESH:
            case Intent.ACTION_LOCALE_CHANGED:
                updateAll(context);
                break;
        }
    }

    /** Redraws both widget kinds (this one and {@link DualWidget}) and re-arms the shared alarm. */
    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, PrayerWidget.class));
        int[] dualIds = manager.getAppWidgetIds(new ComponentName(context, DualWidget.class));
        for (int id : ids) manager.updateAppWidget(id, build(context, manager, id));
        for (int id : dualIds) manager.updateAppWidget(id, DualWidget.build(context, manager, id));
        if (ids.length + dualIds.length > 0) {
            scheduleNext(context);
        } else {
            AlarmManager alarms = context.getSystemService(AlarmManager.class);
            alarms.cancel(refreshIntent(context));
            alarms.cancel(tickIntent(context));
        }
    }

    /** Time-of-day background, reading direction and tap-to-open, shared by both widget kinds. */
    static void decorate(Context context, RemoteViews views, Instant now) {
        views.setInt(R.id.widget_root, "setBackgroundResource", WidgetArt.background(context, now));
        // The launcher lays us out in its own language; force the app's reading direction.
        views.setInt(R.id.widget_root, "setLayoutDirection",
                AppSettings.isArabic(context) ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }

    /** Points a Chronometer at {@code target}: counting down to it, or up from it with {@code since}. */
    static void setChrono(RemoteViews views, int id, Instant target, Instant now, boolean since) {
        long base = SystemClock.elapsedRealtime() + Duration.between(now, target).toMillis();
        views.setChronometerCountDown(id, !since);
        views.setChronometer(id, base, since ? PrayerData.CHRONO_FORMAT_SINCE : PrayerData.CHRONO_FORMAT, true);
    }

    private static RemoteViews build(Context base, AppWidgetManager manager, int id) {
        Context context = AppSettings.wrap(base);
        Instant now = PrayerData.now();
        // For 30 minutes after a prayer's time the widget counts up from it ("since adhan").
        PrayerData.Next current = PrayerData.current(context, now);
        PrayerData.Next next = current != null ? current : PrayerData.next(context, now);
        boolean since = current != null;
        // Ring and bars: through the 30 minutes after the adhan, otherwise towards the next time.
        float progress = next == null ? 0f : since ? WidgetArt.since(next, now) : WidgetArt.until(context, next, now);
        Bitmap ring = WidgetArt.ring(context, RING_DP, progress);

        if (Build.VERSION.SDK_INT >= 31) {
            Map<SizeF, RemoteViews> sizes = new ArrayMap<>();
            sizes.put(new SizeF(100f, 40f), fill(context, Size.SMALL, next, since, now, progress, ring));
            sizes.put(new SizeF(220f, 40f), fill(context, Size.WIDE, next, since, now, progress, ring));
            sizes.put(new SizeF(250f, 130f), fill(context, Size.LARGE, next, since, now, progress, ring));
            sizes.put(new SizeF(330f, 150f), fill(context, Size.XLARGE, next, since, now, progress, ring));
            return new RemoteViews(sizes);
        }
        Bundle options = manager.getAppWidgetOptions(id);
        int width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
        int height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
        Size size = width >= 330 && height >= 150 ? Size.XLARGE
                : width == 0 || (width >= 250 && height >= 130) ? Size.LARGE
                : width >= 220 ? Size.WIDE : Size.SMALL;
        return fill(context, size, next, since, now, progress, ring);
    }

    /** {@code next} is the prayer shown; with {@code since} it began under 30 minutes ago and counts up. */
    private static RemoteViews fill(Context context, Size size, PrayerData.Next next, boolean since,
                                    Instant now, float progress, Bitmap ring) {
        boolean xl = size == Size.XLARGE;
        if (xl) size = Size.LARGE;
        int layout = size == Size.LARGE ? R.layout.widget_large
                : size == Size.WIDE ? R.layout.widget_wide : R.layout.widget_small;
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        if (xl) {
            // "−1:27:54" at full size would squeeze "Maghrib" out; the short count-up can be bigger.
            views.setTextViewTextSize(R.id.w_chrono, TypedValue.COMPLEX_UNIT_SP, since ? 46 : 40);
            views.setTextViewTextSize(R.id.w_next_name, TypedValue.COMPLEX_UNIT_SP, 22);
            if (Build.VERSION.SDK_INT >= 31) {
                views.setViewLayoutWidth(R.id.w_ring_box, 64, TypedValue.COMPLEX_UNIT_DIP);
                views.setViewLayoutHeight(R.id.w_ring_box, 64, TypedValue.COMPLEX_UNIT_DIP);
                views.setViewLayoutWidth(R.id.w_icon, 30, TypedValue.COMPLEX_UNIT_DIP);
                views.setViewLayoutHeight(R.id.w_icon, 30, TypedValue.COMPLEX_UNIT_DIP);
            }
        }
        decorate(context, views, now);
        if (size == Size.SMALL) {
            views.setProgressBar(R.id.w_bar, WidgetArt.MAX, WidgetArt.bar(progress), false);
        } else {
            views.setImageViewBitmap(R.id.w_ring, ring);
        }

        if (size != Size.SMALL) {
            views.setTextViewText(R.id.w_remaining, context.getString(since ? R.string.since_adhan : R.string.remaining));
        }

        if (next == null) {
            views.setTextViewText(R.id.w_next_name, context.getString(R.string.no_data));
            views.setImageViewResource(R.id.w_icon, R.drawable.ic_crescent);
            views.setChronometer(R.id.w_chrono, SystemClock.elapsedRealtime(), null, false);
            if (size != Size.SMALL) views.setTextViewText(R.id.w_next_sub, "");
            return views;
        }

        String name = PrayerData.name(context, next.index);
        String time = PrayerData.formatTime(context, next.time());
        views.setImageViewResource(R.id.w_icon, PrayerData.icon(next.index));
        setChrono(views, R.id.w_chrono, next.time(), now, since);

        if (size == Size.SMALL) {
            views.setTextViewText(R.id.w_next_name, name + " · " + time);
            return views;
        }

        views.setTextViewText(R.id.w_next_name, name);
        views.setTextViewText(R.id.w_next_sub, context.getString(R.string.at_time, time));

        if (size == Size.WIDE) return views;

        boolean isToday = next.day.date.equals(PrayerData.today(context));
        String hijri = Hijri.formatShort(context, next.day.date);
        String header = context.getString(R.string.city) + " · "
                + (isToday ? "" : context.getString(R.string.tomorrow) + " · ")
                + PrayerData.formatDate(context, "EEE d MMM", next.day.date)
                + (hijri != null ? " · " + hijri : "");
        views.setTextViewText(R.id.w_header, header);

        int text = context.getColor(R.color.text);
        int muted = context.getColor(R.color.text_muted);
        int faint = context.getColor(R.color.text_faint);
        int gold = context.getColor(R.color.gold);
        for (int i = 0; i < PrayerData.COUNT; i++) {
            boolean active = i == next.index;
            boolean past = !active && next.day.times[i].isBefore(now);
            views.setTextViewText(COL_NAMES[i], PrayerData.name(context, i));
            views.setTextViewText(COL_TIMES[i], PrayerData.formatTime(context, next.day.times[i]));
            views.setInt(COLS[i], "setBackgroundResource", active ? R.drawable.bg_widget_col_active : 0);
            views.setTextColor(COL_NAMES[i], active ? gold : past ? faint : muted);
            views.setTextColor(COL_TIMES[i], active ? gold : past ? faint : text);
            views.setImageViewResource(DOTS[i], active ? R.drawable.widget_dot_active
                    : past ? R.drawable.widget_dot_past : R.drawable.widget_dot_future);
        }
        views.setProgressBar(R.id.w_timeline, WidgetArt.MAX, WidgetArt.bar(WidgetArt.timeline(next.day, now)), false);
        return views;
    }

    private static PendingIntent refreshIntent(Context context) {
        Intent intent = new Intent(context, PrayerWidget.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Same refresh, separate alarm: the minute tick that moves rings and bars along. */
    private static PendingIntent tickIntent(Context context) {
        Intent intent = new Intent(context, PrayerWidget.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(context, 2, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void scheduleNext(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        long at = PrayerData.toRealMillis(PrayerData.nextChange(context, PrayerData.now())) + 1000;
        PendingIntent pi = refreshIntent(context);
        boolean exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms();
        if (exact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
        // Not a wakeup alarm: while the phone sleeps nobody sees the widget, so it waits for
        // the screen to come on and redraws then.
        long tick = System.currentTimeMillis() + 60_000;
        if (exact) {
            alarms.setExact(AlarmManager.RTC, tick, tickIntent(context));
        } else {
            alarms.setWindow(AlarmManager.RTC, tick, 60_000, tickIntent(context));
        }
    }
}
