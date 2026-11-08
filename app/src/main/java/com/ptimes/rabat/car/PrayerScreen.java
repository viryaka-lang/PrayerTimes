package com.ptimes.rabat.car;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarColor;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.ForegroundCarColorSpan;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.ptimes.rabat.AppSettings;
import com.ptimes.rabat.PrayerData;
import com.ptimes.rabat.R;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Android Auto screen: the next prayer and countdown in the header, the day's six
 * times below with the next one marked. Redraws once a minute while visible.
 */
public class PrayerScreen extends Screen {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };

    PrayerScreen(@NonNull CarContext carContext) {
        super(carContext);
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(@NonNull LifecycleOwner owner) {
                handler.postDelayed(tick, 60_000 - System.currentTimeMillis() % 60_000);
            }

            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                handler.removeCallbacks(tick);
            }
        });
    }

    @NonNull
    @Override
    @SuppressWarnings("deprecation") // setTitle keeps working on older Android Auto hosts.
    public Template onGetTemplate() {
        Context context = AppSettings.wrap(getCarContext());
        boolean arabic = AppSettings.isArabic(context);
        Instant now = PrayerData.now();
        // For 30 minutes after a prayer's time, show it with the time elapsed since adhan.
        PrayerData.Next current = PrayerData.current(context, now);
        PrayerData.Next next = current != null ? current : PrayerData.next(context, now);

        if (next == null) {
            return new MessageTemplate.Builder(context.getString(R.string.no_data_long))
                    .setTitle(context.getString(R.string.app_name))
                    .setHeaderAction(Action.APP_ICON)
                    .build();
        }

        String title = PrayerData.name(context, next.index) + " "
                + PrayerData.formatTime(context, next.time()) + " · ";
        if (current != null) {
            long minutes = Duration.between(current.time(), now).getSeconds() / 60;
            title += "+" + minutes + " " + context.getString(R.string.minutes_short) + " "
                    + context.getString(R.string.since_adhan);
        } else {
            // Round up so the countdown never reads "0:00" before the time arrives.
            long minutes = (Duration.between(now, next.time()).getSeconds() + 59) / 60;
            title += String.format(Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60) + " "
                    + context.getString(R.string.remaining);
        }

        CarIcon crescent = new CarIcon.Builder(
                IconCompat.createWithResource(context, R.drawable.ic_crescent)).build();
        CarColor gold = CarColor.createCustom(
                context.getColor(R.color.gold), context.getColor(R.color.gold_dark));

        ItemList.Builder list = new ItemList.Builder();
        for (int i = 0; i < PrayerData.COUNT; i++) {
            boolean active = i == next.index;
            String rowTitle = PrayerData.name(context, i);
            if (!arabic) rowTitle += "  ·  " + PrayerData.arabicName(context, i);
            Row.Builder row = new Row.Builder().setTitle(rowTitle);
            String time = PrayerData.formatTime(context, next.day.times[i]);
            if (active) {
                SpannableString text = new SpannableString(
                        time + "  ·  " + context.getString(current != null ? R.string.now_badge : R.string.next_badge));
                text.setSpan(ForegroundCarColorSpan.create(gold), 0, text.length(),
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                row.addText(text).setImage(crescent);
            } else {
                row.addText(time);
            }
            list.addItem(row.build());
        }

        return new ListTemplate.Builder()
                .setTitle(title)
                .setHeaderAction(Action.APP_ICON)
                .setSingleList(list.build())
                .build();
    }
}
