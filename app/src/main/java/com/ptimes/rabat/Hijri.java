package com.ptimes.rabat;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Hijri dates as Morocco keeps them, from a table of month starts: the latest one
 * {@link HijriUpdate} downloaded, else the copy built into the app.
 *
 * Announced months are the official starts; later ones are forecasts of the moon sighting
 * and may turn out a day off until the source repository records the announcement. A date
 * maps to the Hijri day that begins on it, as on printed Moroccan calendars.
 */
public final class Hijri {

    private static final String CSV = "morocco_hijri_months.csv";

    private static List<Month> months;

    private Hijri() {}

    private static final class Month {
        final int year;
        final LocalDate start;
        final int days;
        final String name;
        final String nameAr;

        Month(int year, LocalDate start, int days, String name, String nameAr) {
            this.year = year;
            this.start = start;
            this.days = days;
            this.name = name;
            this.nameAr = nameAr;
        }
    }

    private static synchronized List<Month> months(Context context) {
        if (months != null) return months;
        File downloaded = HijriUpdate.file(context);
        if (downloaded.exists()) {
            try {
                months = parse(new String(new AtomicFile(downloaded).readFully(), StandardCharsets.UTF_8));
                return months;
            } catch (IOException | RuntimeException e) {
                Log.w("Hijri", "downloaded table unreadable, using the built-in one", e);
            }
        }
        try {
            months = parse(bundledText(context));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + CSV, e);
        }
        return months;
    }

    /** Re-reads the table, after {@link HijriUpdate} saved a new one. */
    static synchronized void reload() {
        months = null;
    }

    /** The table built into the app. */
    static String bundledText(Context context) throws IOException {
        try (InputStream in = context.getApplicationContext().getAssets().open(CSV)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static List<Month> parse(String csv) {
        List<Month> list = new ArrayList<>();
        String[] lines = csv.split("\r?\n");
        for (int i = 1; i < lines.length; i++) { // line 0 is the header
            // hijri_year,month_no,month_name,month_name_ar,start_date (M/D/YYYY),start_weekday,days,...
            String[] f = lines[i].trim().split(",");
            if (f.length < 7) continue;
            String[] mdy = f[4].split("/");
            LocalDate start = LocalDate.of(Integer.parseInt(mdy[2]),
                    Integer.parseInt(mdy[0]), Integer.parseInt(mdy[1]));
            list.add(new Month(Integer.parseInt(f[0]), start, Integer.parseInt(f[6]), f[2], f[3]));
        }
        if (list.isEmpty()) throw new IllegalStateException("empty Hijri table");
        return list;
    }

    private static Month monthOf(Context context, LocalDate date) {
        for (Month m : months(context)) {
            if (!date.isBefore(m.start) && date.isBefore(m.start.plusDays(m.days))) return m;
        }
        return null;
    }

    /** The first day of the Hijri month containing {@code date}, or null outside the table. */
    static LocalDate monthStart(Context context, LocalDate date) {
        Month m = monthOf(context, date);
        return m == null ? null : m.start;
    }

    /** "25 Jumada al-Ula 1448 AH", or "25 جمادى الأولى 1448 هـ" in Arabic; null outside the table. */
    public static String format(Context context, LocalDate date) {
        Month m = monthOf(context, date);
        return m == null ? null
                : context.getString(R.string.hijri_date, formatShort(context, date), m.year);
    }

    /** Day and month only, "25 Jumada al-Ula", for tight spots; null outside the table. */
    public static String formatShort(Context context, LocalDate date) {
        Month m = monthOf(context, date);
        if (m == null) return null;
        long day = ChronoUnit.DAYS.between(m.start, date) + 1;
        return day + " " + (AppSettings.isArabic(context) ? m.nameAr : m.name);
    }
}
