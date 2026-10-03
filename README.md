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
   | icon_url | link to a square PNG, 512px or larger, or a path in this repo like `icons/pulse.png` (optional) |
   | alarm_sound | mp3/ogg/wav: a URL or a path in this repo (optional, see [Alarm sound](#alarm-sound)) |
   | alarm_tag | notifications whose tag contains this text play the alarm sound (optional) |
   | native_push | off = Chrome (TWA, the default); on = the app's own WebView with Firebase push (see [Native mode](#native-mode-webview--firebase-push)) |
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

**The build `url` must be the address the site is actually served from.** If it only
redirects to another host (e.g. a short domain forwarding to a tunnel address), Chrome
verifies the host the app ends up on, which doesn't match the app, so the URL bar shows
and notifications stay under Chrome. Build with the final address and host
`assetlinks.json` there.

## Website requirements for notifications

The app delivers notifications that the **website** sends. The site needs standard web push:

1. A service worker that handles `push` events and calls `self.registration.showNotification(...)`.
2. A push subscription (`registration.pushManager.subscribe(...)` with your VAPID key), sent to your server.
3. A server (or a service like Firebase Cloud Messaging, OneSignal, etc.) that sends pushes to those subscriptions.

If the site already sends push notifications in Chrome on Android, it works in the app without changes.

## Alarm sound

A website can't pick a notification sound on Android 8+; the notification *channel* decides.
To make some notifications loud, the app can route them to a separate **Alarms** channel
that plays a sound bundled into the APK, even when the app is closed:

- `alarm_sound`: the audio file (mp3, ogg or wav), as a URL or a path in this repo.
- `alarm_tag`: text to look for in the notification's `tag`. The site sets the tag in its
  service worker, e.g. `registration.showNotification(title, { tag: 'whatsapp-123', ... })`
  matches `alarm_tag: whatsapp-`.

Both must be set for the feature to switch on. Everything else keeps using the default
channel and the phone's normal sound. Users can still adjust or mute the Alarms channel in
Android's app notification settings.

A channel's sound is fixed when it is first created on the phone. To change the sound for an
app that's already installed, change `ALARM_CHANNEL_ID` in `AlarmDelegationService.java`
(or reinstall the app).

This repo is public, so don't commit a sound you don't have the rights to share. Host it
somewhere private and pass the URL instead. A local `res/raw/alarm_sound.*` is git-ignored.

## Native mode (WebView + Firebase push)

The default build runs the site inside Chrome, which needs Google to fetch
`assetlinks.json` from the site. That's impossible for a site that isn't on the public
internet, such as one only reachable over a VPN or Tailscale. Native mode avoids Chrome:

- The site runs in the app's own full-screen WebView. There's no `assetlinks.json` check
  and never a URL bar.
- Push comes from **Firebase Cloud Messaging** straight to the app. It arrives even when
  Chrome and the app are closed, and the phone doesn't need the VPN to receive it (only to
  open the site).
- Web push (`PushManager`) and the page's `Notification` API don't exist in a WebView, so
  **the site has to support the app's bridge** and **its server has to send through
  Firebase**. See "What the site needs" below.
- The page's microphone (`getUserMedia({audio: true})`), file inputs and downloads (normal
  links, and `blob:`/`data:` links with a `download` attribute) work. Links to other sites
  open in the phone's browser or apps. The camera isn't granted to the page.
- `alarm_sound` / `alarm_tag` work the same way as in Chrome builds.

### One-time Firebase setup

1. In the [Firebase console](https://console.firebase.google.com/), create a project (or
   use one you already have; Analytics isn't needed).
2. **Add app → Android**, with the same package id you build with (e.g. `in.smokerings.ops`).
   Skip the SHA and the SDK steps.
3. Download `google-services.json` and save its whole contents as the repository secret
   **`GOOGLE_SERVICES_JSON`** (Settings → Secrets and variables → Actions). One file covers
   every Android app in that project; download it again after adding another app.
4. For the site's server: **Project settings → Service accounts → Generate new private
   key**. Keep that JSON on the server only. It's what lets the server send pushes.

Then run Build APK with `native_push` on. Without the secret, the build still works but
has no push (the run shows a warning).

### What the site needs

The page sees a `NativePush` object (only on the app's own origin):

```js
// Reply handler: every request gets back the current state.
NativePush.onmessage = (event) => {
  const state = JSON.parse(event.data);
  // { type: 'state', available: true, permission: 'granted' | 'denied' | 'default',
  //   token: '<FCM token>', error?: '...' }
};
NativePush.postMessage(JSON.stringify({ type: 'state' }));   // just read it
NativePush.postMessage(JSON.stringify({ type: 'enable' }));  // ask for permission first (tap only)
```

Send `token` to your server. The server sends a **data-only** FCM message
(`message.data`, all strings) through the
[FCM HTTP v1 API](https://firebase.google.com/docs/cloud-messaging/send-message):

| data key | meaning |
|---|---|
| `title`, `body` | the notification text |
| `tag` | same tag replaces the previous notification; matched against `alarm_tag` |
| `url` | page to open on tap: a path like `/?alarm=1`, or a URL on the same site |

Send with `android.priority: "HIGH"` so it shows promptly on a sleeping phone. If FCM answers
`UNREGISTERED` (404), the app was uninstalled, so drop that token.

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
