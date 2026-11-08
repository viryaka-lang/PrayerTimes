package com.ptimes.rabat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

public class MainActivity extends BaseActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onTick();
            // Align to the next whole second so the countdown ticks evenly.
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    private final View[] rows = new View[PrayerData.COUNT];

    private TextView todayDate, nextName, nextNameAr, nextTime, countdown, heroLabel, remainingLabel;
    private TextView dayTitle, daySubtitle, noData, backToToday, todayHijri, dayHijri;
    private ImageView nextIcon;
    private View prevDay, nextDay, ringCenter;
    private CountdownRingView ring;
    private LinearLayout rowBox;

    private boolean arabic;
    private LocalDate today;
    private LocalDate selected;
    private PrayerData.Next next;
    /** Set during the 30 minutes after a prayer's time; the hero then counts up from it. */
    private PrayerData.Next current;
    private Instant previous;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Debug builds only: `adb shell am start -S -n com.ptimes.rabat/.MainActivity --el debug_now_ms <epoch ms>`
        if (getIntent().hasExtra("debug_now_ms")) {
            PrayerData.setDebugNow(this, getIntent().getLongExtra("debug_now_ms", 0));
        }
        setContentView(R.layout.activity_main);
        padForSystemBars(findViewById(R.id.root));
        arabic = AppSettings.isArabic(this);

        todayDate = findViewById(R.id.today_date);
        nextIcon = findViewById(R.id.next_icon);
        nextName = findViewById(R.id.next_name);
        nextNameAr = findViewById(R.id.next_name_ar);
        nextTime = findViewById(R.id.next_time);
        countdown = findViewById(R.id.countdown);
        heroLabel = findViewById(R.id.hero_label);
        remainingLabel = findViewById(R.id.remaining_label);
        ring = findViewById(R.id.ring);
        ringCenter = findViewById(R.id.ring_center);
        dayTitle = findViewById(R.id.day_title);
        daySubtitle = findViewById(R.id.day_subtitle);
        todayHijri = findViewById(R.id.today_hijri);
        dayHijri = findViewById(R.id.day_hijri);
        prevDay = findViewById(R.id.prev_day);
        nextDay = findViewById(R.id.next_day);
        noData = findViewById(R.id.no_data);
        backToToday = findViewById(R.id.back_to_today);
        rowBox = findViewById(R.id.rows);

        nextNameAr.setVisibility(arabic ? View.GONE : View.VISIBLE);

        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < PrayerData.COUNT; i++) {
            rows[i] = inflater.inflate(R.layout.row_prayer, rowBox, false);
            ((ImageView) rows[i].findViewById(R.id.icon)).setImageResource(PrayerData.icon(i));
            ((TextView) rows[i].findViewById(R.id.name)).setText(PrayerData.name(this, i));
            ((TextView) rows[i].findViewById(R.id.name_ar))
                    .setText(arabic ? "" : PrayerData.arabicName(this, i));
            rowBox.addView(rows[i]);
        }

        prevDay.setOnClickListener(v -> step(v, -1));
        nextDay.setOnClickListener(v -> step(v, 1));
        findViewById(R.id.day_label_box).setOnClickListener(this::goToToday);
        backToToday.setOnClickListener(this::goToToday);
        findViewById(R.id.settings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        enableSwipe();

        today = PrayerData.today(this);
        selected = today;

        // Alerts and reminders start on, so ask for notifications once, on first launch.
        if (savedInstanceState == null && !PrayerAlerts.canNotify(this)
                && AppSettings.anyNotificationsEnabled(this) && AppSettings.firstNotifyRequest(this)) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isRecreating()) return;
        refresh();
        PrayerWidget.updateAll(this);
        PrayerAlerts.schedule(this);
        Reminders.schedule(this);
        HijriUpdate.schedule(this);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    /** Horizontal flings on the list move a day, following the reading direction. */
    @SuppressLint("ClickableViewAccessibility")
    private void enableSwipe() {
        float minDistance = 60 * getResources().getDisplayMetrics().density;
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null) return false;
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) < minDistance || Math.abs(dx) < Math.abs(e2.getY() - e1.getY())) {
                    return false;
                }
                boolean towardStart = arabic ? dx > 0 : dx < 0; // content moves toward the start edge
                View target = towardStart ? nextDay : prevDay;
                if (target.isEnabled()) step(rowBox, towardStart ? 1 : -1);
                return true;
            }
        });
        rowBox.setOnTouchListener((v, event) -> detector.onTouchEvent(event));
    }

    private void step(View source, int days) {
        source.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        showDay(selected.plusDays(days), days);
    }

    private void goToToday(View source) {
        if (selected.equals(today)) return;
        source.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        showDay(today, selected.isBefore(today) ? 1 : -1);
    }

    private void onTick() {
        Instant now = PrayerData.now();
        boolean sinceOver = current != null
                && !now.isBefore(current.time().plusSeconds(PrayerData.SINCE_WINDOW_SECONDS));
        if (!PrayerData.today(this).equals(today) || sinceOver
                || (next != null && !now.isBefore(next.time()))) {
            refresh();
            return;
        }
        updateCountdown(now);
    }

    /** Recomputes "today" and the next prayer, then redraws everything. */
    private void refresh() {
        LocalDate newToday = PrayerData.today(this);
        if (!newToday.equals(today) && selected.equals(today)) selected = newToday;
        today = newToday;
        Instant now = PrayerData.now();
        next = PrayerData.next(this, now);
        current = PrayerData.current(this, now);
        previous = PrayerData.previous(this, now);
        PrayerData.Next focus = focus();

        todayDate.setText(PrayerData.formatDate(this, "EEEE d MMMM yyyy", today));
        showHijri(todayHijri, today);
        heroLabel.setText(current != null ? R.string.prayer_now
                : next != null && next.index == PrayerData.SUNRISE ? R.string.fajr_ends : R.string.next_prayer);
        remainingLabel.setText(current != null ? R.string.since_adhan : R.string.remaining);

        if (focus == null) {
            nextIcon.setImageResource(R.drawable.ic_crescent);
            nextName.setText(R.string.no_data);
            nextNameAr.setText("");
            nextTime.setVisibility(View.GONE);
            countdown.setText("--:--");
            ring.setProgress(0);
        } else {
            nextIcon.setImageResource(PrayerData.icon(focus.index));
            nextName.setText(PrayerData.name(this, focus.index));
            nextNameAr.setText(PrayerData.arabicName(this, focus.index));
            nextTime.setVisibility(View.VISIBLE);
            nextTime.setText(getString(R.string.at_time, PrayerData.formatTime(this, focus.time())));
            updateCountdown(now);
        }
        showDay(selected, 0);
    }

    /** The Hijri date under a Gregorian one, hidden outside the Hijri table. */
    private void showHijri(TextView view, LocalDate date) {
        String hijri = Hijri.format(this, date);
        view.setText(hijri);
        view.setVisibility(hijri != null ? View.VISIBLE : View.GONE);
    }

    /** What the hero shows: the prayer that just began (count-up), else the next one. */
    private PrayerData.Next focus() {
        return current != null ? current : next;
    }

    private void updateCountdown(Instant now) {
        if (current != null) {
            long elapsed = Duration.between(current.time(), now).getSeconds();
            String text = PrayerData.formatElapsed(elapsed);
            countdown.setText(text);
            ringCenter.setContentDescription(getString(R.string.since_description,
                    PrayerData.name(this, current.index), text));
            ring.setProgress((float) elapsed / PrayerData.SINCE_WINDOW_SECONDS);
            return;
        }
        if (next == null) return;
        long seconds = Duration.between(now, next.time()).getSeconds();
        String text = PrayerData.formatCountdown(seconds);
        countdown.setText(text);
        ringCenter.setContentDescription(getString(R.string.countdown_description,
                PrayerData.name(this, next.index), text));
        if (previous != null) {
            long span = Duration.between(previous, next.time()).getSeconds();
            ring.setProgress(span <= 0 ? 0 : 1f - (float) seconds / span);
        }
    }

    /** Shows {@code date}; {@code direction} slides the list in from that side (0 = no animation). */
    private void showDay(LocalDate date, int direction) {
        selected = date;

        if (date.equals(today)) {
            dayTitle.setText(R.string.today);
        } else if (date.equals(today.plusDays(1))) {
            dayTitle.setText(R.string.tomorrow);
        } else if (date.equals(today.minusDays(1))) {
            dayTitle.setText(R.string.yesterday);
        } else {
            dayTitle.setText(PrayerData.formatDate(this, "EEEE", date));
        }
        daySubtitle.setText(PrayerData.formatDate(this, "d MMMM yyyy", date));
        showHijri(dayHijri, date);
        backToToday.setVisibility(date.equals(today) ? View.GONE : View.VISIBLE);

        setNavEnabled(prevDay, PrayerData.day(this, date.minusDays(1)) != null);
        setNavEnabled(nextDay, PrayerData.day(this, date.plusDays(1)) != null);

        PrayerData.Day day = PrayerData.day(this, date);
        rowBox.setVisibility(day == null ? View.GONE : View.VISIBLE);
        noData.setVisibility(day == null ? View.VISIBLE : View.GONE);
        if (day == null) return;

        int text = getColor(R.color.text);
        int muted = getColor(R.color.text_muted);
        int onGold = getColor(R.color.on_gold);
        Instant now = PrayerData.now();
        PrayerData.Next focus = focus();
        for (int i = 0; i < PrayerData.COUNT; i++) {
            View row = rows[i];
            TextView name = row.findViewById(R.id.name);
            TextView nameAr = row.findViewById(R.id.name_ar);
            TextView time = row.findViewById(R.id.time);
            ImageView icon = row.findViewById(R.id.icon);
            String timeText = PrayerData.formatTime(this, day.times[i]);
            time.setText(timeText);

            boolean active = focus != null && focus.day.date.equals(date) && focus.index == i;
            boolean past = !active && day.times[i].isBefore(now);
            row.setBackgroundResource(active ? R.drawable.bg_row_active : 0);
            TextView badge = row.findViewById(R.id.badge);
            badge.setVisibility(active ? View.VISIBLE : View.GONE);
            badge.setText(current != null ? R.string.now_badge : R.string.next_badge);
            name.setTextColor(active ? onGold : text);
            time.setTextColor(active ? onGold : text);
            nameAr.setTextColor(active ? onGold : muted);
            icon.setImageTintList(ColorStateList.valueOf(active ? onGold : muted));
            row.setAlpha(past ? 0.42f : 1f);
            row.setContentDescription(getString(
                    !active ? R.string.row_description
                            : current != null ? R.string.row_description_now : R.string.row_description_next,
                    PrayerData.name(this, i), timeText));
        }

        if (direction != 0) {
            float shift = 32 * getResources().getDisplayMetrics().density * direction * (arabic ? -1 : 1);
            rowBox.setAlpha(0f);
            rowBox.setTranslationX(shift);
            rowBox.animate().alpha(1f).translationX(0f).setDuration(220).start();
        }
    }

    private static void setNavEnabled(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.3f);
    }
}
