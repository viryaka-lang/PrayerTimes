package com.ptimes.rabat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;

import java.time.Duration;
import java.time.Instant;

/** Graphics shared by the widgets: time-of-day backgrounds, progress values and the drawn ring. */
final class WidgetArt {

    /** Progress bars and the timeline run from 0 to this. */
    static final int MAX = 1000;

    /** Indexed by the listed time whose period we are in: dawn, morning, midday, afternoon, dusk, night. */
    private static final int[] BACKGROUNDS = {
            R.drawable.bg_widget_fajr, R.drawable.bg_widget_sunrise, R.drawable.bg_widget_dhuhr,
            R.drawable.bg_widget_asr, R.drawable.bg_widget_maghrib, R.drawable.bg_widget_isha};

    private WidgetArt() {}

    /** The background tinted for the part of the day we are in. */
    static int background(Context context, Instant now) {
        PrayerData.Next last = PrayerData.last(context, now);
        return BACKGROUNDS[last != null ? last.index : PrayerData.ISHA];
    }

    /** How far we are into the 30 minutes after {@code current}'s time, 0..1. */
    static float since(PrayerData.Next current, Instant now) {
        return fraction(current.time(), current.time().plusSeconds(PrayerData.SINCE_WINDOW_SECONDS), now);
    }

    /** How far we are from the previous listed time to {@code next}, 0..1. */
    static float until(Context context, PrayerData.Next next, Instant now) {
        Instant previous = PrayerData.previous(context, now);
        return previous == null ? 0f : fraction(previous, next.time(), now);
    }

    /** Where {@code now} sits on the day's timeline, one equal step between each listed time. */
    static float timeline(PrayerData.Day day, Instant now) {
        if (!now.isAfter(day.times[0])) return 0f;
        for (int i = 0; i < PrayerData.COUNT - 1; i++) {
            if (now.isBefore(day.times[i + 1])) {
                return (i + fraction(day.times[i], day.times[i + 1], now)) / (PrayerData.COUNT - 1);
            }
        }
        return 1f;
    }

    static int bar(float fraction) {
        return Math.round(fraction * MAX);
    }

    private static float fraction(Instant from, Instant to, Instant now) {
        long total = Duration.between(from, to).toMillis();
        if (total <= 0) return 1f;
        float f = (float) Duration.between(from, now).toMillis() / total;
        return Math.max(0f, Math.min(1f, f));
    }

    /** The app's progress ring (track, brightening gold arc, glowing tip) as a bitmap for RemoteViews. */
    static Bitmap ring(Context context, int sizeDp, float progress) {
        float dp = context.getResources().getDisplayMetrics().density;
        int px = Math.round(sizeDp * dp);
        float stroke = 3.5f * dp;
        float dotRadius = 3.6f * dp;
        float inset = dotRadius + 2.5f * dp; // room for the glow
        int gold = context.getColor(R.color.gold);

        Bitmap bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        RectF oval = new RectF(inset, inset, px - inset, px - inset);
        float cx = oval.centerX(), cy = oval.centerY(), r = oval.width() / 2;

        Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(context.getColor(R.color.divider));
        canvas.drawOval(oval, track);

        Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(stroke);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setShader(new SweepGradient(cx, cy, new int[]{context.getColor(R.color.gold_dark), gold},
                new float[]{0f, Math.max(progress, 0.01f)}));

        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(gold);
        dot.setShadowLayer(2.5f * dp, 0, 0, gold);

        float sweep = 360f * progress;
        canvas.rotate(-90, cx, cy); // start at 12 o'clock
        if (sweep > 0.5f) canvas.drawArc(oval, 0, sweep, false, arc);
        double a = Math.toRadians(sweep);
        canvas.drawCircle(cx + (float) (r * Math.cos(a)), cy + (float) (r * Math.sin(a)), dotRadius, dot);
        return bitmap;
    }
}
