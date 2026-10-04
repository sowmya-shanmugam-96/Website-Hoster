package com.urltoapk.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Self-update from this repo's GitHub Releases, for a sideloaded app with no store to update
 * it. On when the build was made with auto_update (R.string.updateRepo is then "owner/repo").
 *
 * Each release of this app carries an update.json ({versionCode, versionName}) next to the
 * APK. The newest release tagged for this package (R.string.updateTagPrefix) is compared with
 * the installed versionCode, and a newer APK is downloaded and handed to PackageInstaller.
 * Android refuses an update signed with a different key, so only this repo's builds install.
 *
 * Android 12+ lets an app update itself without asking once it is its own installer of record,
 * i.e. from the second self-update on. Until then, and on older Android, the system asks the
 * user to confirm (the first time also to allow "install unknown apps" for this app). A silent
 * install closes the app, so it waits until the app is off screen; one that needs confirming
 * happens while the app is open, or as a notification when it isn't.
 */
final class Updater {
    private static final String TAG = "Updater";
    // Unauthenticated GitHub API calls are limited to 60 an hour per IP; this is far below.
    private static final long CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L;
    private static final String PREFS = "updater";
    private static final String CHANNEL_ID = "updates";
    private static final int NOTIFICATION_ID = 0x5550;
    static final String ACTION_STATUS = "com.urltoapk.shell.UPDATE_STATUS";
    static final String EXTRA_VERSION = "versionCode";

    // One thread, so a check, a download and an install never overlap.
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private Updater() {}

    static boolean enabled(Context context) {
        return !TextUtils.isEmpty(context.getString(R.string.updateRepo));
    }

    /**
     * Looks for a newer build, at most every few hours, downloads it, and installs it if that
     * can happen now. Called when the app comes on screen and when a push arrives, so a phone
     * that only ever receives pushes still updates.
     */
    static void maybeCheck(Context context) {
        if (!enabled(context)) return;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = prefs(app);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("lastCheck", 0) < CHECK_INTERVAL_MS) return;
        prefs.edit().putLong("lastCheck", now).apply();
        EXECUTOR.execute(() -> {
            try {
                download(app);
                install(app, WebAppActivity.isOnScreen(), true);
            } catch (Exception e) {
                Log.w(TAG, "update check failed", e);
            }
        });
    }

    /** The app left the screen: the moment to install an update that doesn't need asking. */
    static void onBackground(Context context) {
        if (!enabled(context)) return;
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                install(app, false, false);
            } catch (Exception e) {
                Log.w(TAG, "update install failed", e);
            }
        });
    }

    // ---- Finding and downloading ----

    private static void download(Context app) throws Exception {
        SharedPreferences prefs = prefs(app);
        long installed = installedVersion(app);
        JSONObject update = latestRelease(app);
        long version = update == null ? 0 : update.optLong("versionCode", 0);
        if (version <= installed || version == prefs.getLong("failedVersion", 0)) {
            clearDownload(app);
            return;
        }
        File apk = apkFile(app, version);
        if (prefs.getLong("readyVersion", 0) == version && apk.exists()) return;

        clearDownload(app);
        File partial = new File(apk.getPath() + ".part");
        try (InputStream in = open(update.getString("apkUrl")).getInputStream();
                OutputStream out = new FileOutputStream(partial)) {
            copy(in, out);
        }
        if (!partial.renameTo(apk)) throw new IOException("could not save " + apk);
        prefs.edit().putLong("readyVersion", version)
                .putString("readyName", update.optString("versionName")).apply();
    }

    /** update.json of the newest release for this package, plus its "apkUrl"; null if none. */
    private static JSONObject latestRelease(Context app) throws Exception {
        String repo = app.getString(R.string.updateRepo);
        String prefix = app.getString(R.string.updateTagPrefix);
        // Newest first. Other apps built from the same repo share the list, hence the prefix.
        JSONArray releases = new JSONArray(read(
                "https://api.github.com/repos/" + repo + "/releases?per_page=50"));
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue;
            if (!release.optString("tag_name").startsWith(prefix)) continue;
            String manifest = null;
            String apk = null;
            JSONArray assets = release.optJSONArray("assets");
            for (int j = 0; assets != null && j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                String name = asset.optString("name");
                if (name.equals("update.json")) manifest = asset.optString("browser_download_url");
                else if (name.endsWith(".apk")) apk = asset.optString("browser_download_url");
            }
            // The newest release of this app decides; one built without auto_update has no
            // update.json and so offers nothing.
            if (manifest == null || apk == null) return null;
            return new JSONObject(read(manifest)).put("apkUrl", apk);
        }
        return null;
    }

    // ---- Installing ----

    /**
     * @param onScreen  whether the app is in front of the user right now
     * @param mayPrompt whether a system confirmation may be shown (or posted) for this attempt
     */
    private static void install(Context app, boolean onScreen, boolean mayPrompt)
            throws IOException {
        SharedPreferences prefs = prefs(app);
        long version = prefs.getLong("readyVersion", 0);
        File apk = apkFile(app, version);
        if (version == 0 || !apk.exists()) return;
        if (version <= installedVersion(app)) {
            clearDownload(app);
            return;
        }
        boolean silent = canInstallSilently(app);
        // A silent install kills the running app: never in the middle of someone using it.
        if (silent && onScreen) return;
        if (!silent && !mayPrompt) return;

        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk);
                    OutputStream out = session.openWrite("update.apk", 0, apk.length())) {
                copy(in, out);
                session.fsync(out);
            }
            Intent status = new Intent(app, UpdateReceiver.class)
                    .setAction(ACTION_STATUS)
                    .putExtra(EXTRA_VERSION, version);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            // The installer fills in the result extras, so this one has to be mutable.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent callback = PendingIntent.getBroadcast(app, sessionId, status, flags);
            session.commit(callback.getIntentSender());
        } catch (IOException | RuntimeException e) {
            installer.abandonSession(sessionId);
            throw e;
        }
    }

    /** Android 12+, and this app installed the copy that's running (an earlier self-update). */
    private static boolean canInstallSilently(Context app) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        try {
            String installer = app.getPackageManager()
                    .getInstallSourceInfo(app.getPackageName()).getInstallingPackageName();
            return app.getPackageName().equals(installer);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The system needs the user to confirm: show it now if the app is open, else notify. */
    static void askUser(Context context, Intent confirm) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (WebAppActivity.isOnScreen()) {
            context.startActivity(confirm);
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "App updates",
                NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent tap = PendingIntent.getActivity(context, NOTIFICATION_ID, confirm,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String name = prefs(context).getString("readyName", "");
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(context.getColor(R.color.themeColor))
                .setContentTitle("Update ready")
                .setContentText(TextUtils.isEmpty(name)
                        ? "Tap to install the new version"
                        : "Tap to install version " + name)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }

    /** An install failed for a reason other than the user saying no: don't retry that build. */
    static void onFailed(Context context, long version, String message) {
        Log.w(TAG, "update to " + version + " failed: " + message);
        prefs(context).edit().putLong("failedVersion", version).apply();
        clearDownload(context);
    }

    // ---- Helpers ----

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @SuppressWarnings("deprecation")
    private static long installedVersion(Context app) {
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return Long.MAX_VALUE;
        }
    }

    private static File apkFile(Context app, long version) {
        File dir = new File(app.getCacheDir(), "update");
        dir.mkdirs();
        return new File(dir, "update-" + version + ".apk");
    }

    private static void clearDownload(Context app) {
        File[] files = new File(app.getCacheDir(), "update").listFiles();
        if (files != null) for (File f : files) f.delete();
        prefs(app).edit().remove("readyVersion").remove("readyName").apply();
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "UrlToApk-Updater");
        conn.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code + " for " + url);
        return conn;
    }

    private static String read(String url) throws IOException {
        try (InputStream in = open(url).getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
    }
}
