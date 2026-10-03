package com.urltoapk.shell;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Set;

/**
 * Native mode: the site in a full-screen WebView, with push delivered by Firebase.
 *
 * The page talks to the app through a "NativePush" object that only the app's own origin can
 * see. It posts JSON messages and gets a JSON "state" reply:
 *
 *   NativePush.postMessage('{"type":"state"}')   -> current permission and push token
 *   NativePush.postMessage('{"type":"enable"}')  -> asks for notification permission first
 *
 * and the reply is {type: "state", available, permission: "granted"|"denied"|"default",
 * token, error}. The page sends the token to its server, which pushes through Firebase.
 */
public class WebAppActivity extends Activity {
    static final String EXTRA_URL = "com.urltoapk.shell.URL";

    private static final int REQUEST_FILE = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;
    private static final int REQUEST_MICROPHONE = 3;
    private static final int REQUEST_STORAGE = 4;

    // Runs at document start on the app's origin. A download of a blob: or data: URL never
    // reaches the DownloadListener in a usable form (the page usually revokes the blob right
    // after clicking), so the page's own Blob is captured and handed to the app instead.
    private static final String DOWNLOAD_SHIM = "(function () {\n"
            + "  if (!window.NativePush || window.__nativeDownloads) return;\n"
            + "  window.__nativeDownloads = true;\n"
            + "  var blobs = new Map();\n"
            + "  var create = URL.createObjectURL;\n"
            + "  URL.createObjectURL = function (obj) {\n"
            + "    var url = create.apply(URL, arguments);\n"
            + "    if (obj instanceof Blob) {\n"
            + "      blobs.set(url, obj);\n"
            + "      setTimeout(function () { blobs.delete(url); }, 120000);\n"
            + "    }\n"
            + "    return url;\n"
            + "  };\n"
            + "  function send(name, blob) {\n"
            + "    var reader = new FileReader();\n"
            + "    reader.onload = function () {\n"
            + "      NativePush.postMessage(JSON.stringify({type: 'download', name: name,\n"
            + "        mime: blob.type || '', data: reader.result}));\n"
            + "    };\n"
            + "    reader.readAsDataURL(blob);\n"
            + "  }\n"
            + "  function handle(a) {\n"
            + "    if (!a || !a.hasAttribute('download')) return false;\n"
            + "    var href = a.href || '';\n"
            + "    var name = a.getAttribute('download') || 'download';\n"
            + "    if (href.indexOf('blob:') === 0 && blobs.has(href)) {\n"
            + "      send(name, blobs.get(href));\n"
            + "      return true;\n"
            + "    }\n"
            + "    if (href.indexOf('data:') === 0) {\n"
            + "      fetch(href).then(function (r) { return r.blob(); })\n"
            + "        .then(function (b) { send(name, b); });\n"
            + "      return true;\n"
            + "    }\n"
            + "    return false;\n"
            + "  }\n"
            + "  var click = HTMLAnchorElement.prototype.click;\n"
            + "  HTMLAnchorElement.prototype.click = function () {\n"
            + "    if (handle(this)) return;\n"
            + "    return click.apply(this, arguments);\n"
            + "  };\n"
            + "  document.addEventListener('click', function (e) {\n"
            + "    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;\n"
            + "    if (handle(a)) e.preventDefault();\n"
            + "  }, true);\n"
            + "})();";

    private WebView webView;
    private Uri startUri;
    private String origin;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingMediaRequest;
    private JavaScriptReplyProxy pendingEnableReply;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startUri = Uri.parse(getString(R.string.launchUrl));
        origin = originOf(startUri);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.themeColor));
        webView = new WebView(this);
        webView.setBackgroundColor(getColor(R.color.backgroundColor));
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        applySystemBars(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // The page may start an alarm sound on load (e.g. opened from a notification).
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setUserAgentString(settings.getUserAgentString() + " UrlToApk/1");
        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebViewClient(new AppWebViewClient());
        webView.setWebChromeClient(new AppChromeClient());
        webView.setDownloadListener(this::download);
        installBridge();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(urlFromIntent(getIntent()));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.hasExtra(EXTRA_URL) || intent.getData() != null) {
            webView.loadUrl(urlFromIntent(intent));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ---- Navigation -----------------------------------------------------------------------

    private String urlFromIntent(Intent intent) {
        String extra = intent.getStringExtra(EXTRA_URL);
        if (!TextUtils.isEmpty(extra)) return extra;
        Uri data = intent.getData();
        if (data != null && sameSite(startUri, data)) return data.toString();
        return startUri.toString();
    }

    static boolean sameSite(Uri a, Uri b) {
        return b != null && b.getHost() != null
                && "https".equalsIgnoreCase(b.getScheme())
                && b.getHost().equalsIgnoreCase(a.getHost())
                && b.getPort() == a.getPort();
    }

    private static String originOf(Uri uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
        }
    }

    private class AppWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (sameSite(startUri, url)) return false;
            // Other sites, tel:, mailto:, whatsapp: and so on go to the phone's apps.
            openExternally(url);
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            String failedUrl = request.getUrl().toString();
            String retry = JSONObject.quote(failedUrl);
            String html = "<html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
                    + "<style>body{font-family:sans-serif;padding:32px;text-align:center;color:#333}"
                    + "button{font-size:16px;padding:12px 24px;margin-top:16px}</style></head><body>"
                    + "<h2>Can't reach " + TextUtils.htmlEncode(startUri.getHost()) + "</h2>"
                    + "<p>" + TextUtils.htmlEncode(String.valueOf(error.getDescription())) + "</p>"
                    + "<p>Check your connection (and VPN, if the site needs one).</p>"
                    + "<button onclick='location.href=" + TextUtils.htmlEncode(retry)
                    + "'>Try again</button></body></html>";
            view.loadDataWithBaseURL(null, html, "text/html", "utf-8", failedUrl);
        }
    }

    // ---- Edge-to-edge ---------------------------------------------------------------------

    private void applySystemBars(View root) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Draw behind the bars on every version (Android 15 forces it anyway) and pad the
            // content so nothing hides under the status bar, navigation bar or keyboard.
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        } else {
            getWindow().setStatusBarColor(getColor(R.color.themeColor));
            getWindow().setNavigationBarColor(getColor(R.color.themeColor));
        }
    }

    // ---- Page <-> app bridge --------------------------------------------------------------

    private void installBridge() {
        Set<String> allowed = Collections.singleton(origin);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "NativePush", allowed, this::onBridgeMessage);
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, DOWNLOAD_SHIM, allowed);
        }
    }

    private void onBridgeMessage(WebView view, WebMessageCompat message, Uri sourceOrigin,
            boolean isMainFrame, JavaScriptReplyProxy reply) {
        if (!isMainFrame || message.getData() == null) return;
        JSONObject json;
        try {
            json = new JSONObject(message.getData());
        } catch (JSONException e) {
            return;
        }
        switch (json.optString("type")) {
            case "state":
                replyState(reply, null);
                break;
            case "enable":
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    pendingEnableReply = reply;
                    requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS},
                            REQUEST_NOTIFICATIONS);
                } else {
                    replyState(reply, null);
                }
                break;
            case "download":
                saveDataUrl(json.optString("name", "download"), json.optString("mime"),
                        json.optString("data"));
                break;
            default:
                break;
        }
    }

    private String permissionState() {
        if (getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()) {
            return "granted";
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getSharedPreferences("push", MODE_PRIVATE).getBoolean("asked", false)) {
            return "default";
        }
        // Refused, or turned off in Android's settings for this app.
        return "denied";
    }

    private void replyState(JavaScriptReplyProxy reply, String error) {
        boolean available = !FirebaseApp.getApps(this).isEmpty();
        if (!available) {
            postState(reply, false, "", error != null ? error
                    : "Push isn't set up in this build of the app (no Firebase config).");
            return;
        }
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                postState(reply, true, task.getResult(), error);
            } else {
                Exception e = task.getException();
                postState(reply, true, "", "Couldn't get a push token: "
                        + (e != null ? e.getMessage() : "unknown error"));
            }
        });
    }

    private void postState(JavaScriptReplyProxy reply, boolean available, String token, String error) {
        JSONObject state = new JSONObject();
        try {
            state.put("type", "state");
            state.put("available", available);
            state.put("permission", permissionState());
            state.put("token", token == null ? "" : token);
            if (error != null) state.put("error", error);
        } catch (JSONException e) {
            return;
        }
        runOnUiThread(() -> reply.postMessage(state.toString()));
    }

    // ---- Permissions, file picker, microphone ---------------------------------------------

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQUEST_NOTIFICATIONS && pendingEnableReply != null) {
            getSharedPreferences("push", MODE_PRIVATE).edit().putBoolean("asked", true).apply();
            replyState(pendingEnableReply, null);
            pendingEnableReply = null;
        } else if (requestCode == REQUEST_MICROPHONE && pendingMediaRequest != null) {
            if (granted) {
                pendingMediaRequest.grant(new String[] {PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingMediaRequest.deny();
            }
            pendingMediaRequest = null;
        } else if (requestCode == REQUEST_STORAGE && !granted) {
            Toast.makeText(this, "Allow storage to save downloads", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                result = new Uri[data.getClipData().getItemCount()];
                for (int i = 0; i < result.length; i++) {
                    result[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                result = new Uri[] {data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    private class AppChromeClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            Intent intent = params.createIntent();
            if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            try {
                startActivityForResult(intent, REQUEST_FILE);
                return true;
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                return false;
            }
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            // Only the microphone, and only for the app's own site.
            boolean audioOnly = request.getResources().length > 0;
            for (String resource : request.getResources()) {
                if (!PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) audioOnly = false;
            }
            if (!audioOnly || !sameSite(startUri, request.getOrigin())) {
                request.deny();
                return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                request.grant(new String[] {PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                if (pendingMediaRequest != null) pendingMediaRequest.deny();
                pendingMediaRequest = request;
                requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO},
                        REQUEST_MICROPHONE);
            }
        }
    }

    // ---- Downloads ------------------------------------------------------------------------

    private boolean canWriteDownloads() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
        Toast.makeText(this, "Allow storage, then download again", Toast.LENGTH_SHORT).show();
        return false;
    }

    private void download(String url, String userAgent, String contentDisposition,
            String mimeType, long contentLength) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            // blob: and data: downloads are handled by DOWNLOAD_SHIM.
            return;
        }
        if (!canWriteDownloads()) return;
        String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
        DownloadManager.Request request = new DownloadManager.Request(uri)
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent)
                .setTitle(name)
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) request.addRequestHeader("Cookie", cookies);
        getSystemService(DownloadManager.class).enqueue(request);
        Toast.makeText(this, "Downloading " + name, Toast.LENGTH_SHORT).show();
    }

    private void saveDataUrl(String name, String mime, String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (!dataUrl.startsWith("data:") || comma < 0 || !canWriteDownloads()) return;
        byte[] bytes;
        try {
            bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return;
        }
        name = new File(name).getName();
        if (name.isEmpty()) name = "download";
        if (TextUtils.isEmpty(mime)) mime = "application/octet-stream";

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri item = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (item == null) throw new IOException("MediaStore refused the file");
                try (OutputStream out = getContentResolver().openOutputStream(item)) {
                    if (out == null) throw new IOException("Couldn't open the file");
                    out.write(bytes);
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(item, values, null, null);
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No Downloads folder");
                try (OutputStream out = new FileOutputStream(new File(dir, name))) {
                    out.write(bytes);
                }
            }
            Toast.makeText(this, "Saved " + name + " to Downloads", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Couldn't save " + name + ": " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }
}
