package com.ptimes.rabat;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import android.util.JsonReader;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the Hijri month table current without an app update: around each month's 29th, when
 * the next month is announced (see {@link #nextCheck}), and when online, downloads the month
 * list from the HijriCalendarMaghrib repository on GitHub and stores it for {@link Hijri},
 * which falls back to the copy built into the app. Settings can also check on demand.
 *
 * Only the "months" array at the start of calendar.json is read; the download stops there.
 * The ETag makes unchanged checks cost one tiny request.
 */
public class HijriUpdate extends JobService {

    private static final String TAG = "HijriUpdate";
    static final String URL =
            "https://raw.githubusercontent.com/viryaka-lang/HijriCalendarMaghrib/main/data/calendar.json";
    /** The downloaded table, in the same format as the bundled asset. */
    static final String FILE = "hijri_months.csv";

    /** Same ID as the old daily job, so scheduling this replaces it on updated installs. */
    private static final int JOB_NEXT = 1;
    private static final int JOB_NOW = 2;

    private static final String PREFS = "hijri_update";
    private static final String KEY_NEXT = "next";
    private static final String KEY_ETAG = "etag";
    private static final String KEY_CHECKED = "checked";
    private static final String KEY_UPDATED = "updated";

    public enum Result { UPDATED, UNCHANGED, FAILED }

    private static final String[] ARABIC = {"محرم", "صفر", "ربيع الأول", "ربيع الآخر",
            "جمادى الأولى", "جمادى الآخرة", "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة"};

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** When the last check finished (with or without news), or null. */
    static Instant lastChecked(Context context) {
        long ms = prefs(context).getLong(KEY_CHECKED, 0);
        return ms == 0 ? null : Instant.ofEpochMilli(ms);
    }

    /** When new dates were last downloaded, or null while the built-in ones are in use. */
    static Instant lastUpdated(Context context) {
        long ms = prefs(context).getLong(KEY_UPDATED, 0);
        return ms == 0 || !file(context).exists() ? null : Instant.ofEpochMilli(ms);
    }

    static File file(Context context) {
        return new File(context.getFilesDir(), FILE);
    }

    /**
     * The next time worth checking. Morocco announces each month on the evening of the 29th,
     * so: that evening at 21:00, and again the next morning at 10:00 in case the repository is
     * updated late. About two checks a month; past the end of the table, once a month.
     */
    static Instant nextCheck(Context context, Instant now) {
        LocalDate today = PrayerData.dateOf(context, now);
        LocalDate start = Hijri.monthStart(context, today);
        for (int i = 0; i < 2 && start != null; i++) {
            LocalDate day29 = start.plusDays(28);
            Instant evening = at(context, day29, LocalTime.of(21, 0));
            Instant morning = at(context, day29.plusDays(1), LocalTime.of(10, 0));
            if (evening.isAfter(now)) return evening;
            if (morning.isAfter(now)) return morning;
            // 30 days on is always inside the next month, whether this one has 29 or 30.
            start = Hijri.monthStart(context, start.plusDays(30));
        }
        return now.plus(30, ChronoUnit.DAYS);
    }

    /** {@code time} on {@code date} in Morocco, using the prayer table's UTC offset. */
    private static Instant at(Context context, LocalDate date, LocalTime time) {
        PrayerData.Day day = PrayerData.day(context, date);
        ZoneOffset offset = day != null ? day.offset : ZoneId.of("Africa/Casablanca").getRules()
                .getOffset(date.atTime(time));
        return date.atTime(time).toInstant(offset);
    }

    /**
     * Arms the next check (kept across reboots), plus one right away if the app never checked.
     * Cheap to call often: the job is only replaced when its time changes.
     */
    static void schedule(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        ComponentName service = new ComponentName(context, HijriUpdate.class);
        Instant now = PrayerData.now();
        Instant next = nextCheck(context, now);
        long realNext = PrayerData.toRealMillis(next);
        SharedPreferences prefs = prefs(context);
        if (jobs.getPendingJob(JOB_NEXT) == null || prefs.getLong(KEY_NEXT, 0) != realNext) {
            jobs.schedule(new JobInfo.Builder(JOB_NEXT, service)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(Math.max(0, realNext - System.currentTimeMillis()))
                    .setBackoffCriteria(TimeUnit.HOURS.toMillis(1), JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPersisted(true)
                    .build());
            prefs.edit().putLong(KEY_NEXT, realNext).apply();
        }
        if (lastChecked(context) == null && jobs.getPendingJob(JOB_NOW) == null) {
            jobs.schedule(new JobInfo.Builder(JOB_NOW, service)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build());
        }
    }

    /** When the next automatic check is due. */
    static Instant scheduledCheck(Context context) {
        long ms = prefs(context).getLong(KEY_NEXT, 0);
        return ms == 0 ? null : Instant.ofEpochMilli(ms);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            Result result = run(this);
            // Retry later with backoff if the server couldn't be reached.
            jobFinished(params, result == Result.FAILED);
            // Then arm the following check (not from inside the job: same ID would stop it).
            if (result != Result.FAILED) {
                prefs(this).edit().remove(KEY_NEXT).apply();
                schedule(this);
            }
        }, TAG).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    /** Checks for new dates now; blocking, so call it off the main thread. */
    static synchronized Result run(Context context) {
        SharedPreferences prefs = prefs(context);
        File file = file(context);
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(URL).openConnection();
            http.setConnectTimeout(15_000);
            http.setReadTimeout(30_000);
            http.setRequestProperty("User-Agent", "RabatPrayerTimes-Android");
            String etag = prefs.getString(KEY_ETAG, null);
            if (etag != null && file.exists()) http.setRequestProperty("If-None-Match", etag);

            int code = http.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                prefs.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply();
                return Result.UNCHANGED;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP " + code);
                return Result.FAILED;
            }
            String csv;
            try (JsonReader json = new JsonReader(
                    new InputStreamReader(http.getInputStream(), StandardCharsets.UTF_8))) {
                csv = toCsv(readMonths(json));
            }

            String current = file.exists() ? read(file) : Hijri.bundledText(context);
            boolean changed = !csv.equals(current.replace("\r", ""));
            if (changed) write(file, csv);
            SharedPreferences.Editor edit = prefs.edit()
                    .putString(KEY_ETAG, http.getHeaderField("ETag"))
                    .putLong(KEY_CHECKED, System.currentTimeMillis());
            if (changed) edit.putLong(KEY_UPDATED, System.currentTimeMillis());
            edit.apply();
            if (changed) {
                Hijri.reload();
                PrayerWidget.updateAll(context);
            }
            Log.i(TAG, changed ? "new dates saved" : "no change");
            return changed ? Result.UPDATED : Result.UNCHANGED;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "check failed", e);
            return Result.FAILED;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static final class Month {
        int year, number, length;
        String name, start, status;
        final Map<String, Double> chance = new HashMap<>();
    }

    /** Reads the "months" array (the first key) and stops; the rest of the file isn't needed. */
    private static List<Month> readMonths(JsonReader json) throws IOException {
        json.beginObject();
        while (json.hasNext()) {
            if (!json.nextName().equals("months")) {
                json.skipValue();
                continue;
            }
            List<Month> months = new ArrayList<>();
            json.beginArray();
            while (json.hasNext()) {
                Month m = new Month();
                json.beginObject();
                while (json.hasNext()) {
                    switch (json.nextName()) {
                        case "hy": m.year = json.nextInt(); break;
                        case "hm": m.number = json.nextInt(); break;
                        case "len": m.length = json.nextInt(); break;
                        case "name": m.name = json.nextString(); break;
                        case "start": m.start = json.nextString(); break;
                        case "status": m.status = json.nextString(); break;
                        case "dist":
                            json.beginObject();
                            while (json.hasNext()) m.chance.put(json.nextName(), json.nextDouble());
                            json.endObject();
                            break;
                        default: json.skipValue();
                    }
                }
                json.endObject();
                months.add(m);
            }
            return months;
        }
        throw new IOException("no months in calendar.json");
    }

    /**
     * The table as the app's CSV, after checking it holds together: each month 29 or 30 days
     * and starting the day after the previous one ends. A broken download is never used.
     */
    private static String toCsv(List<Month> months) throws IOException {
        if (months.size() < 12) throw new IOException("only " + months.size() + " months");
        StringBuilder csv = new StringBuilder(
                "hijri_year,month_no,month_name,month_name_ar,start_date,start_weekday,days,status,start_probability\n");
        LocalDate expected = null;
        for (Month m : months) {
            LocalDate start = LocalDate.parse(m.start);
            if (m.number < 1 || m.number > 12 || (m.length != 29 && m.length != 30)
                    || (expected != null && !start.equals(expected)) || m.name == null) {
                throw new IOException("inconsistent month " + m.year + "-" + m.number);
            }
            expected = start.plusDays(m.length);
            Double chance = "official".equals(m.status) ? null : m.chance.get(m.start);
            String weekday = start.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);
            csv.append(m.year).append(',').append(m.number).append(',').append(m.name).append(',')
                    .append(ARABIC[m.number - 1]).append(',')
                    .append(start.getMonthValue()).append('/').append(start.getDayOfMonth()).append('/')
                    .append(start.getYear()).append(',').append(weekday).append(',').append(m.length)
                    .append(',').append(m.status).append(',')
                    .append(chance == null ? "1" : new BigDecimal(chance).setScale(4, RoundingMode.HALF_UP)
                            .stripTrailingZeros().toPlainString()).append('\n');
        }
        return csv.toString();
    }

    private static String read(File file) throws IOException {
        return new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8);
    }

    private static void write(File file, String text) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream out = atomic.startWrite();
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(out);
        } catch (IOException e) {
            atomic.failWrite(out);
            throw e;
        }
    }
}
