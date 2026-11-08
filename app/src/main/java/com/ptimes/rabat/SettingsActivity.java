package com.ptimes.rabat;

import android.Manifest;
import android.app.AlarmManager;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;

public class SettingsActivity extends BaseActivity {

    private static final int REQUEST_NOTIFY = 1;
    /** For reminders or alerts already on: only refreshes what's shown. */
    private static final int REQUEST_ASK = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        padForSystemBars(findViewById(R.id.root));
        findViewById(R.id.back).setOnClickListener(v -> finish());

        RadioGroup lang = findViewById(R.id.lang_group);
        switch (AppSettings.language(this)) {
            case AppSettings.LANG_EN: lang.check(R.id.lang_en); break;
            case AppSettings.LANG_AR: lang.check(R.id.lang_ar); break;
            default: lang.check(R.id.lang_system);
        }
        lang.setOnCheckedChangeListener((group, id) -> {
            String value = id == R.id.lang_en ? AppSettings.LANG_EN
                    : id == R.id.lang_ar ? AppSettings.LANG_AR : AppSettings.LANG_SYSTEM;
            if (value.equals(AppSettings.language(this))) return;
            AppSettings.setLanguage(this, value);
            PrayerWidget.updateAll(this);
            recreate(); // redraw this screen in the new language and direction
        });

        RadioGroup clock = findViewById(R.id.clock_group);
        switch (AppSettings.clock(this)) {
            case AppSettings.CLOCK_24: clock.check(R.id.clock_24); break;
            case AppSettings.CLOCK_12: clock.check(R.id.clock_12); break;
            default: clock.check(R.id.clock_system);
        }
        clock.setOnCheckedChangeListener((group, id) -> {
            AppSettings.setClock(this, id == R.id.clock_24 ? AppSettings.CLOCK_24
                    : id == R.id.clock_12 ? AppSettings.CLOCK_12 : AppSettings.CLOCK_SYSTEM);
            PrayerWidget.updateAll(this);
        });

        findViewById(R.id.alerts_row).setOnClickListener(v -> {
            if (AppSettings.alertsEnabled(this) && !PrayerAlerts.canNotify(this)) {
                askToNotify();
            } else if (AppSettings.alertsEnabled(this)) {
                setAlerts(false);
            } else if (PrayerAlerts.canNotify(this)) {
                setAlerts(true);
            } else {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFY);
            }
        });

        ViewGroup reminders = findViewById(R.id.reminders_box);
        for (int k = 0; k < Reminders.COUNT; k++) {
            int kind = k;
            View row = getLayoutInflater().inflate(R.layout.row_reminder, reminders, false);
            Switch toggle = row.findViewById(R.id.reminder_switch);
            toggle.setText(Reminders.TITLES[kind]);
            ((TextView) row.findViewById(R.id.reminder_desc)).setText(Reminders.DESCRIPTIONS[kind]);
            toggle.setChecked(AppSettings.reminderEnabled(this, kind));
            toggle.setOnCheckedChangeListener((button, on) -> {
                AppSettings.setReminderEnabled(this, kind, on);
                Reminders.schedule(this);
                if (on && !PrayerAlerts.canNotify(this)) askToNotify();
                showAlertsStatus();
            });
            reminders.addView(row);
        }
        findViewById(R.id.reminders_blocked).setOnClickListener(v -> askToNotify());

        findViewById(R.id.hijri_row).setOnClickListener(v -> checkHijri());

        findViewById(R.id.add_dual_widget).setOnClickListener(v -> pinWidget(DualWidget.class));
        findViewById(R.id.add_large_widget).setOnClickListener(v -> pinWidget(PrayerWidget.class));

        findViewById(R.id.exact_row).setOnClickListener(v -> {
            if (!canScheduleExact()) {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName())));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isRecreating()) return;
        ((TextView) findViewById(R.id.exact_status))
                .setText(canScheduleExact() ? R.string.exact_on : R.string.exact_off);
        showAlertsStatus();
        showHijriStatus();
    }

    /** Looks for new Hijri dates now, off the main thread, and says what happened. */
    private void checkHijri() {
        View row = findViewById(R.id.hijri_row);
        if (!row.isEnabled()) return;
        row.setEnabled(false);
        ((TextView) findViewById(R.id.hijri_status)).setText(R.string.hijri_checking);
        new Thread(() -> {
            HijriUpdate.Result result = HijriUpdate.run(getApplicationContext());
            HijriUpdate.schedule(getApplicationContext()); // new dates can move the next check
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                row.setEnabled(true);
                showHijriStatus();
                Toast.makeText(this, result == HijriUpdate.Result.UPDATED ? R.string.hijri_result_updated
                        : result == HijriUpdate.Result.UNCHANGED ? R.string.hijri_result_same
                        : R.string.hijri_result_failed, Toast.LENGTH_SHORT).show();
            });
        }, "HijriCheck").start();
    }

    private void showHijriStatus() {
        Instant updated = HijriUpdate.lastUpdated(this);
        Instant checked = HijriUpdate.lastChecked(this);
        String status = updated != null ? getString(R.string.hijri_updated, when(updated))
                : getString(R.string.hijri_builtin);
        if (checked != null) status += " · " + getString(R.string.hijri_checked, when(checked));
        Instant next = HijriUpdate.scheduledCheck(this);
        if (next != null) status += "\n" + getString(R.string.hijri_next, when(next));
        ((TextView) findViewById(R.id.hijri_status)).setText(status + "\n" + getString(R.string.hijri_tap));
    }

    private String when(Instant t) {
        return PrayerData.formatDate(this, "d MMM", PrayerData.dateOf(this, t)) + " "
                + PrayerData.formatTime(this, t);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        // Turn alerts on even if denied, so the row offers the system settings next time.
        if (requestCode == REQUEST_NOTIFY) setAlerts(true);
        showAlertsStatus();
    }

    /** Asks for notifications, or opens the system screen once the app may no longer ask. */
    private void askToNotify() {
        if (!AppSettings.firstNotifyRequest(this) && !shouldShowRequestPermissionRationale(
                Manifest.permission.POST_NOTIFICATIONS)) {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } else {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_ASK);
        }
    }

    /** Asks the launcher to place a widget; it shows its own "Add widget?" prompt. */
    private void pinWidget(Class<?> provider) {
        AppWidgetManager manager = getSystemService(AppWidgetManager.class);
        if (manager.isRequestPinAppWidgetSupported()) {
            manager.requestPinAppWidget(new ComponentName(this, provider), null, null);
        }
    }

    private void setAlerts(boolean on) {
        AppSettings.setAlertsEnabled(this, on);
        PrayerAlerts.schedule(this);
        showAlertsStatus();
    }

    private void showAlertsStatus() {
        ((TextView) findViewById(R.id.alerts_status)).setText(
                !AppSettings.alertsEnabled(this) ? R.string.alerts_off
                        : PrayerAlerts.canNotify(this) ? R.string.alerts_on : R.string.alerts_blocked);
        boolean anyReminder = false;
        for (int k = 0; k < Reminders.COUNT; k++) anyReminder |= AppSettings.reminderEnabled(this, k);
        findViewById(R.id.reminders_blocked).setVisibility(
                anyReminder && !PrayerAlerts.canNotify(this) ? View.VISIBLE : View.GONE);
    }

    private boolean canScheduleExact() {
        return Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }
}
