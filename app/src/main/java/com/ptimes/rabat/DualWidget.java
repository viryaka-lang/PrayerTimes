package com.ptimes.rabat;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.time.Instant;
import java.util.Map;

/**
 * Compact "Now & next" widget: for 30 minutes after a prayer a gold pill counts up from it
 * (its bar filling as the 30 minutes pass), while below it the next event (sunrise included)
 * counts down above a bar filling towards it. Outside that window the pill is hidden and the
 * countdown gets the room.
 *
 * Refreshing and alarms are shared with {@link PrayerWidget#updateAll}.
 */
public class DualWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        PrayerWidget.updateAll(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        manager.updateAppWidget(id, build(context, manager, id));
    }

    @Override
    public void onDisabled(Context context) {
        PrayerWidget.updateAll(context); // cancels the shared alarm if no widget is left
    }

    static RemoteViews build(Context base, AppWidgetManager manager, int id) {
        Context context = AppSettings.wrap(base);
        Instant now = PrayerData.now();
        PrayerData.Next current = PrayerData.current(context, now);
        PrayerData.Next next = PrayerData.next(context, now);

        if (Build.VERSION.SDK_INT >= 31) {
            Map<SizeF, RemoteViews> sizes = new ArrayMap<>();
            sizes.put(new SizeF(100f, 40f), fill(context, false, current, next, now));
            sizes.put(new SizeF(100f, 105f), fill(context, true, current, next, now));
            return new RemoteViews(sizes);
        }
        int height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
        return fill(context, height == 0 || height >= 105, current, next, now);
    }

    private static RemoteViews fill(Context context, boolean stacked, PrayerData.Next current,
                                    PrayerData.Next next, Instant now) {
        RemoteViews views = new RemoteViews(context.getPackageName(),
                stacked ? R.layout.widget_dual : R.layout.widget_dual_row);
        PrayerWidget.decorate(context, views, now);

        views.setViewVisibility(R.id.d_since_box, current != null ? View.VISIBLE : View.GONE);
        if (current != null) {
            views.setImageViewResource(R.id.d_since_icon, PrayerData.icon(current.index));
            PrayerWidget.setChrono(views, R.id.d_since_chrono, current.time(), now, true);
            if (stacked) {
                views.setTextViewText(R.id.d_since_name, PrayerData.name(context, current.index));
                views.setProgressBar(R.id.d_since_bar, WidgetArt.MAX,
                        WidgetArt.bar(WidgetArt.since(current, now)), false);
            }
        }

        if (next == null) {
            views.setTextViewText(R.id.d_next_name, context.getString(R.string.no_data));
            views.setChronometer(R.id.d_next_chrono, SystemClock.elapsedRealtime(), null, false);
            views.setProgressBar(R.id.d_next_bar, WidgetArt.MAX, 0, false);
            return views;
        }
        // In one row the pill sits beside the name: the time would be cut, so the name stands alone.
        String nextName = PrayerData.name(context, next.index);
        views.setTextViewText(R.id.d_next_name, !stacked && current != null ? nextName
                : nextName + " · " + PrayerData.formatTime(context, next.time()));
        PrayerWidget.setChrono(views, R.id.d_next_chrono, next.time(), now, false);
        views.setProgressBar(R.id.d_next_bar, WidgetArt.MAX, WidgetArt.bar(WidgetArt.until(context, next, now)), false);
        if (stacked) {
            views.setImageViewResource(R.id.d_next_icon, PrayerData.icon(next.index));
            views.setTextViewText(R.id.d_next_caption, context.getString(R.string.remaining));
            // With the pill hidden the countdown grows, but "−4:41:04" must still fit 2 cells.
            boolean hours = java.time.Duration.between(now, next.time()).toHours() >= 1;
            views.setTextViewTextSize(R.id.d_next_chrono, TypedValue.COMPLEX_UNIT_SP,
                    current != null ? 28 : hours ? 30 : 36);
        }
        return views;
    }
}
