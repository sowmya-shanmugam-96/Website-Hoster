# Website Hoster

Turns any mobile-optimised website into an installable Android APK, with the site's
web push notifications showing up as **real app notifications** (your app name and icon,
not "Chrome").

No Android Studio needed: fill in a form on GitHub Actions and download the APK.

## How it works

The APK is a **Trusted Web Activity (TWA)**, the same tech Google uses for PWAs on the Play Store.

- The app opens your URL full-screen inside the user's Chrome engine, so cookies, logins,
  service workers, camera, payments and so on all behave exactly like Chrome.
- **Notifications:** when your site's service worker calls `showNotification()`, Chrome
  *delegates* it to the app (`DelegationService`), so it appears as a notification from
  your app. Tapping it opens the app. On Android 13+ the app asks for the notification
  permission when the site calls `Notification.requestPermission()`.
- If the phone has no TWA-capable browser (rare), the app falls back to a built-in WebView.
  The site still works, but push notifications don't (WebView has no web push).

## Build an APK

1. Go to **Actions → Build APK → Run workflow**.
2. Fill in:
   | Field | Example |
   |---|---|
   | url | `https://app.example.com/` |
   | app_name | `Example` |
   | package_id | leave blank → `com.example.app.twa` (keep it **the same** for every build of the same app) |
   | theme_color | `#1E88E5` |
   | icon_url | link to a square PNG, 512px or larger (optional) |
   | version_code / version_name | increase these for each update |
3. When the run finishes, download the artifact. It contains the `.apk` **and** an `assetlinks.json`.
   The run summary shows the same JSON.

## Required: link the website to the app

Upload the generated `assetlinks.json` to your site at:

```
https://<your-domain>/.well-known/assetlinks.json
```

(served as `application/json`, no redirects). Chrome checks this file to confirm that the app owns the site.

| assetlinks.json | Result |
|---|---|
| ✅ present and matches | Full-screen app, notifications show as the app |
| ❌ missing / wrong | Browser URL bar at the top, and notifications show under Chrome |

To have one app cover several apps or keys, put multiple entries in the JSON array.

## Website requirements for notifications

The app delivers notifications that the **website** sends. The site needs standard web push:

1. A service worker that handles `push` events and calls `self.registration.showNotification(...)`.
2. A push subscription (`registration.pushManager.subscribe(...)` with your VAPID key), sent to your server.
3. A server (or a service like Firebase Cloud Messaging, OneSignal, etc.) that sends pushes to those subscriptions.

If the site already sends push notifications in Chrome on Android, it works in the app without changes.

## Use a permanent signing key (do this before sharing the app)

Without a key, every build gets a new throwaway key. That breaks updates and changes
`assetlinks.json` each time. Create a key once:

```bash
keytool -genkeypair -v -keystore release.jks -alias app -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks > keystore.b64     # macOS: base64 -i release.jks -o keystore.b64
```

Add these under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | contents of `keystore.b64` |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `app` |
| `KEY_PASSWORD` | the key password (if it's the same as the keystore password, you can leave it out) |

Back up `release.jks` somewhere safe. If you lose it, you can't update the installed app.

If you later publish to Google Play with Play App Signing, add Play's SHA-256 fingerprint
(Play Console → App integrity) to `assetlinks.json` as well.

## Build locally (optional)

Requires the Android SDK and JDK 17:

```bash
gradle :app:assembleRelease -PappUrl=https://app.example.com/ -PappName="Example"
```

Defaults are in `gradle.properties`. `python scripts/set_icon.py <url-or-file>` installs a custom icon.
