package com.ptimes.rabat;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Rabat prayer times, read from the bundled CSV.
 *
 * Each CSV row carries Morocco's UTC offset for that day, and its times are written in it.
 * Times become Instants for countdowns, and are shown and grouped into days using the
 * row's own offset rather than the phone's time zone rules: Morocco moved back to UTC+0
 * in September 2026, and phones with older time zone data would otherwise be an hour off.
 */
public final class PrayerData {

    /** Only used outside the table's date range. */
    private static final ZoneId RABAT = ZoneId.of("Africa/Casablanca");

    public static final int FAJR = 0;
    public static final int SUNRISE = 1;
    public static final int DHUHR = 2;
    public static final int ASR = 3;
    public static final int MAGHRIB = 4;
    public static final int ISHA = 5;
    public static final int COUNT = 6;

    private static final String CSV = "rabat_prayer_times.csv";

    private static Map<LocalDate, Day> days;

    private PrayerData() {}

    public static final class Day {
        public final LocalDate date;
        public final ZoneOffset offset;
        public final Instant[] times;

        Day(LocalDate date, ZoneOffset offset, Instant[] times) {
            this.date = date;
            this.offset = offset;
            this.times = times;
        }
    }

    public static final class Next {
        public final Day day;
        public final int index;

        Next(Day day, int index) {
            this.day = day;
            this.index = index;
        }

        public Instant time() {
            return day.times[index];
        }
    }

    private static synchronized Map<LocalDate, Day> days(Context context) {
        if (days != null) return days;
        Map<LocalDate, Day> map = new HashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                context.getApplicationContext().getAssets().open(CSV), StandardCharsets.UTF_8))) {
            in.readLine(); // header
            String line;
            while ((line = in.readLine()) != null) {
                String[] f = line.trim().split(",");
                if (f.length < 9) continue;
                // Date,Day,Fajr,Sunrise,Dhuhr,Asr,Maghrib,Isha,Timezone
                LocalDate date = LocalDate.parse(f[0]);
                ZoneOffset offset = ZoneOffset.ofHours(Integer.parseInt(f[8].trim().substring(3)));
                Instant[] times = new Instant[COUNT];
                for (int i = 0; i < COUNT; i++) {
                    times[i] = date.atTime(LocalTime.parse(f[2 + i])).toInstant(offset);
                }
                map.put(date, new Day(date, offset, times));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + CSV, e);
        }
        days = map;
        return days;
    }

    /** Morocco's UTC offset at {@code t}, from the table (transitions fall at night, never near a prayer). */
    private static ZoneOffset offsetAt(Context context, Instant t) {
        Day day = days(context).get(t.atOffset(ZoneOffset.UTC).toLocalDate());
        return day != null ? day.offset : RABAT.getRules().getOffset(t);
    }

    /** The Moroccan calendar date at {@code t}. */
    static LocalDate dateOf(Context context, Instant t) {
        return t.atOffset(offsetAt(context, t)).toLocalDate();
    }

    public static LocalDate today(Context context) {
        return dateOf(context, now());
    }

    /** Shift applied to the clock in debug builds only, to check time-dependent screens. */
    private static volatile long debugOffsetMs;

    /** The current time; always use this rather than Instant.now(). */
    public static Instant now() {
        return Instant.now().plusMillis(debugOffsetMs);
    }

    /** Debug builds: pretend it's {@code epochMs} (0 restores the real clock) until the process ends. */
    static void setDebugNow(Context context, long epochMs) {
        if ((context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        debugOffsetMs = epochMs == 0 ? 0 : epochMs - System.currentTimeMillis();
    }

    /** {@code t} on the real clock, for scheduling alarms. */
    public static long toRealMillis(Instant t) {
        return t.toEpochMilli() - debugOffsetMs;
    }

    /** The day's times, or null when the file has no data for it. */
    public static Day day(Context context, LocalDate date) {
        return days(context).get(date);
    }

    /**
     * What the countdowns point at: the next listed time, sunrise included (it ends Fajr's
     * time), or null when the data runs out.
     */
    public static Next next(Context context, Instant now) {
        return next(context, now, true);
    }

    /** The next of the five prayers, sunrise skipped; for alerts. */
    public static Next nextPrayer(Context context, Instant now) {
        return next(context, now, false);
    }

    private static Next next(Context context, Instant now, boolean withSunrise) {
        LocalDate date = dateOf(context, now);
        for (int d = 0; d < 2; d++) {
            Day day = day(context, date.plusDays(d));
            if (day == null) continue;
            for (int i = 0; i < COUNT; i++) {
                if ((withSunrise || i != SUNRISE) && day.times[i].isAfter(now)) return new Next(day, i);
            }
        }
        return null;
    }

    /** How long after each prayer's time we count up instead of counting down to the next. */
    public static final long SINCE_WINDOW_SECONDS = 30 * 60;

    /** The prayer whose time began less than 30 minutes ago, or null. Shown with a count-up. */
    public static Next current(Context context, Instant now) {
        LocalDate date = dateOf(context, now);
        for (int d = 0; d < 2; d++) {
            Day day = day(context, date.minusDays(d));
            if (day == null) continue;
            for (int i = COUNT - 1; i >= 0; i--) {
                if (i == SUNRISE || day.times[i].isAfter(now)) continue;
                return now.isBefore(day.times[i].plusSeconds(SINCE_WINDOW_SECONDS)) ? new Next(day, i) : null;
            }
        }
        return null;
    }

    /** The latest listed time (sunrise included) at or before {@code now}, for the progress ring. */
    public static Instant previous(Context context, Instant now) {
        Next last = last(context, now);
        return last != null ? last.time() : null;
    }

    /** The listed time (sunrise included) whose period we are in, or null. */
    public static Next last(Context context, Instant now) {
        LocalDate date = dateOf(context, now);
        for (int d = 0; d < 2; d++) {
            Day day = day(context, date.minusDays(d));
            if (day == null) continue;
            for (int i = COUNT - 1; i >= 0; i--) {
                if (!day.times[i].isAfter(now)) return new Next(day, i);
            }
        }
        return null;
    }

    /** When anything shown on the widget next changes: any listed time, or Rabat midnight. */
    public static Instant nextChange(Context context, Instant now) {
        LocalDate date = dateOf(context, now);
        Day tomorrow = day(context, date.plusDays(1));
        Instant best = tomorrow != null
                ? tomorrow.date.atStartOfDay().toInstant(tomorrow.offset)
                : date.plusDays(1).atStartOfDay(RABAT).toInstant();
        Day day = day(context, date);
        if (day != null) {
            for (int i = 0; i < COUNT; i++) {
                Instant t = day.times[i];
                if (t.isAfter(now) && t.isBefore(best)) best = t;
                // The end of the 30-minute count-up also changes what's shown.
                Instant end = t.plusSeconds(SINCE_WINDOW_SECONDS);
                if (i != SUNRISE && end.isAfter(now) && end.isBefore(best)) best = end;
            }
        }
        return best;
    }

    public static String formatTime(Context context, Instant t) {
        String pattern = AppSettings.is24Hour(context) ? "HH:mm" : "h:mm a";
        return DateTimeFormatter.ofPattern(pattern, AppSettings.locale(context))
                .format(t.atOffset(offsetAt(context, t)));
    }

    public static String formatDate(Context context, String pattern, LocalDate date) {
        return DateTimeFormatter.ofPattern(pattern, AppSettings.locale(context)).format(date);
    }

    /**
     * Countdown as "−1:05:09" or "−05:09", matching the widget's Chronometer. The minus
     * keeps it from reading as a time of day next to the prayer times.
     */
    public static String formatCountdown(long seconds) {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (h > 0) return String.format(Locale.ROOT, "%s%d:%02d:%02d", MINUS, h, m, s);
        return String.format(Locale.ROOT, "%s%02d:%02d", MINUS, m, s);
    }

    /** Count-up since a prayer's time, "+12:05", matching the widget's Chronometer. */
    public static String formatElapsed(long seconds) {
        if (seconds < 0) seconds = 0;
        return String.format(Locale.ROOT, "+%02d:%02d", seconds / 60, seconds % 60);
    }

    /** Prefix for the widget's Chronometer format, so both countdowns look the same. */
    public static final String CHRONO_FORMAT = "−%s";
    public static final String CHRONO_FORMAT_SINCE = "+%s";
    private static final String MINUS = "−";

    public static String name(Context context, int index) {
        return context.getResources().getStringArray(R.array.prayer_names)[index];
    }

    private static final int[] ICONS = {
            R.drawable.ic_fajr, R.drawable.ic_sunrise, R.drawable.ic_dhuhr,
            R.drawable.ic_asr, R.drawable.ic_maghrib, R.drawable.ic_isha};

    public static int icon(int index) {
        return ICONS[index];
    }

    public static String arabicName(Context context, int index) {
        return context.getResources().getStringArray(R.array.prayer_names_ar)[index];
    }
}
