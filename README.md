# Rabat Prayer Times

Android app with the prayer times for Rabat, Morocco, from a bundled 2026–2027 table, always shown in Moroccan time. English and Arabic.

- Next prayer with a live countdown, and a count-up for 30 minutes after each adhan
- Home screen and lock screen widgets: "Now & next" and a large one with the whole day
- Prayer alerts (10 minutes before and at each prayer), also as banners in Android Auto
- Reminders: Duha (1 hour before Dhuhr), Friday's hour of response (1 hour before Maghrib) and Qiyam al-layl (1 hour before Fajr)
- Hijri dates following the Moroccan announcements, refreshed from [HijriCalendarMaghrib](https://github.com/viryaka-lang/HijriCalendarMaghrib) when a new month is announced
- Android Auto screen

Plain Java, no third-party libraries besides androidx.car.app. minSdk 26, targetSdk 36.

## Build

```
./gradlew assembleDebug
```

Release builds are signed with a key read from `keystore.properties` (not in the repo). Without that file, Gradle builds unsigned releases.

`tools/Update-HijriCalendar.ps1` refreshes the bundled Hijri table (`app/src/main/assets/morocco_hijri_months.csv`) from HijriCalendarMaghrib.
