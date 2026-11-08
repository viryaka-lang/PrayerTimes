# Publishing Rabat Prayer Times as a private Google Play internal test

> **Not needed just to use it in your car.** The USB-installed app shows on the car
> screen once Android Auto → ⋮ → Developer settings has **Unknown sources** ticked and
> **Application mode = Developer** (tested in the Desktop Head Unit, see
> `android-auto-screen.jpg`). It still won't be listed in the phone's *Customize launcher*,
> only in the car's app grid. Use this guide only if you later want to install from Play.

Goal: install the app from Google Play so Android Auto accepts it. An internal test is
visible only to the Google accounts you list; nothing is public.

## What's in this folder

| File | Use |
|---|---|
| `rabat-prayer-times-1.1.aab` | The signed release to upload (versionCode 2) |
| `icon-512.png` | App icon (512×512) |
| `feature-1024x500.png` | Feature graphic |
| `screenshot-1..4.png` | Phone screenshots (1080×2160) |
| `PRIVACY_POLICY.md` | Privacy policy text to publish at a public URL |

The upload key is `../keystore/upload.jks`, and its passwords are in `../keystore.properties`.
**Back up both files somewhere safe and never share them.** Every future update must be
signed with this key. (With Play App Signing, Google can reset a lost upload key, but it
takes days.)

## 1. Developer account (one time)

1. Go to <https://play.google.com/console/signup> and create a **personal** developer
   account with the Google account you use on your phone.
2. Pay the one-time US$25 fee and finish identity verification (this can take a few days).

## 2. Create the app

1. Play Console → **Create app**.
2. App name: `Rabat Prayer Times` · Default language: English (or Arabic) · **App** ·
   **Free**. Accept the declarations → **Create app**.

## 3. Privacy policy URL

Play needs a public link. Quickest options:
- **Google Sites** (sites.google.com): new site → paste `PRIVACY_POLICY.md` → Publish.
- Or a public GitHub Gist / GitHub Pages page.

First replace `[your contact email]` in the policy with an address you're happy to publish.

## 4. App content (Policy → App content)

Answer each section:

- **Privacy policy:** paste the URL from step 3.
- **Ads:** No, the app does not contain ads.
- **App access:** All functionality is available without special access.
- **Content rating:** start the questionnaire → category *Reference, News, or Educational*
  (or *Other*) → answer **No** to everything → submit. Expect "Everyone / 3+".
- **Target audience:** 18 and over (avoids the Families program; nothing in the app is
  age-restricted).
- **Data safety:** *Does your app collect or share any of the required user data types?*
  → **No**. (True: its only internet use is downloading the public Hijri month table from
  GitHub, sending no user data.) Data is encrypted in transit: **Yes** (HTTPS).
- **Government app:** No. **Financial features:** None. **Health:** No. **News:** No.
- **Advertising ID:** No.
- **Exact alarms:** the app now declares `USE_EXACT_ALARM` for on-time prayer alerts.
  Play asks you to justify it ("alarm/reminder app: prayer time alerts"). If Play rejects
  it, remove that line from `AndroidManifest.xml`, drop `maxSdkVersion` from
  `SCHEDULE_EXACT_ALARM`, and rebuild; users then enable Settings → Exact timing.
- **Notifications:** prayer alerts use `POST_NOTIFICATIONS`; nothing leaves the phone.

## 5. Store listing (Grow → Store presence → Main store listing)

- **Short description** (≤80):
  `Rabat prayer times with a live countdown, widget and Arabic interface.`
- **Full description:**

  ```
  Prayer times for Rabat, Morocco, always in Moroccan time, working offline.

  • Big live countdown to the next prayer
  • Today's Fajr, Sunrise, Dhuhr, Asr, Maghrib and Isha at a glance
  • Home screen widget in several sizes with a large countdown
  • Arabic-only or English interface, right-to-left layout in Arabic
  • 12- or 24-hour clock
  • Android Auto screen with the next prayer and today's times
  • Moroccan Hijri dates, updated automatically when online
  • No ads, no tracking; works offline

  أوقات الصلاة لمدينة الرباط بالتوقيت المغربي، مع عدّ تنازلي وودجت وواجهة عربية كاملة.
  ```
- **App icon:** `icon-512.png` · **Feature graphic:** `feature-1024x500.png`
- **Phone screenshots:** `screenshot-1.png` … `screenshot-4.png`
- **Category:** Lifestyle · **Contact email:** your address.

## 6. Turn on Android Auto for the app

Play Console → **Test and release → Advanced settings → Form factors** → **Add form
factor → Android Auto** → accept the Android for Cars terms.

## 7. Internal test release

1. **Test and release → Testing → Internal testing → Create new release.**
2. When asked about app signing, keep **Use Google-generated key** (Play App Signing).
3. Upload `rabat-prayer-times-1.1.aab`. Release name: `1.1`. Notes: `First test`.
4. **Next → Save and publish** (internal releases are usually available within minutes).
5. **Testers** tab → create an email list with your own Gmail address → save.
6. Copy the **opt-in link**, open it on your phone (signed in with that Gmail), tap
   **Accept invite**, then **Download it on Google Play**.

## 8. Install on your phone

The Play build is signed with Google's key, so it can't replace the one installed over
USB. Uninstall the current app first (Settings → Apps → Rabat Prayer Times → Uninstall),
then install from the Play link. Afterwards set Arabic again in the app's Settings, re-add
the widget, and turn on **Exact widget timing**.

Then open Android Auto → **Customize launcher**: *Rabat Prayer Times* should be listed.

## If Android Auto still doesn't show it

- Google reviews Android Auto apps, and internal testing may not skip that for the
  Android Auto form factor. Check Play Console → **Publishing overview** for a pending
  review or a rejection notice.
- Prayer times aren't an official Android for Cars category; the app registers as
  **POI** (points of interest). Google may reject that for this app. If so, the app can't
  be distributed for Android Auto in its current form.

## Future updates

1. Raise `versionCode` (and `versionName`) in `app/build.gradle.kts`.
2. Build: `gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
3. Internal testing → Create new release → upload → publish.
