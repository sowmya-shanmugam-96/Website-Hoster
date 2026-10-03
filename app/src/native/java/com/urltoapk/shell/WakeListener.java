package com.urltoapk.shell;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import androidx.webkit.JavaScriptReplyProxy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Android's own speech recogniser, for a page that wants to listen for a spoken phrase.
 *
 * The WebView exposes the Web Speech API but has no speech service behind it, so a page's
 * own recogniser fails at once. This does the listening natively instead and hands every
 * phrase it hears to the page; the page decides what counts (a wake word, say).
 *
 *   NativePush.postMessage('{"type":"speech-start"}')  -> starts listening, replies speech-ready
 *   NativePush.postMessage('{"type":"speech-stop"}')   -> stops and lets go of the microphone
 *
 * Replies: {type: "speech-ready"}, {type: "speech-result", texts: [best, alternatives...]},
 * and {type: "speech-error", error: "not-allowed" | "unavailable"}, after which it has stopped.
 *
 * A recogniser session ends after each phrase or a stretch of quiet, so "listening" is really
 * restarting it every time it ends, until told to stop. It also stops on its own when the
 * activity goes to the background (and picks up again on return) and when the page navigates.
 */
final class WakeListener implements RecognitionListener {
    interface Host {
        void requestSpeechPermission();
    }

    // Short, so a word said just after a phrase ends is not lost in the gap.
    private static final long RESTART_MS = 250;
    // Sessions that fail this soon after starting are not the recogniser's usual end after
    // quiet; this many in a row and it gives up rather than spin on the microphone all day.
    private static final long FAST_FAILURE_MS = 1000;
    private static final int MAX_FAST_FAILURES = 8;

    private final Activity activity;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable restart = this::listen;

    private SpeechRecognizer recognizer;
    private boolean onDevice;
    // The on-device engine can be present but lack the language; after that, use the
    // regular one for the rest of the app's life.
    private boolean onDeviceFailed;
    private JavaScriptReplyProxy reply;
    private boolean wanted;
    private boolean paused;
    private long startedAt;
    private int fastFailures;

    WakeListener(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    // ---- Called by the activity, always on the main thread ---------------------------------

    void start(JavaScriptReplyProxy reply) {
        this.reply = reply;
        wanted = true;
        fastFailures = 0;
        if (!SpeechRecognizer.isRecognitionAvailable(activity) && !onDeviceAvailable()) {
            fail("unavailable");
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            host.requestSpeechPermission();
            return;
        }
        send("speech-ready", null, null);
        listen();
    }

    void onPermissionResult(boolean granted) {
        if (!wanted) return;
        if (!granted) {
            fail("not-allowed");
            return;
        }
        send("speech-ready", null, null);
        listen();
    }

    void stop() {
        wanted = false;
        handler.removeCallbacks(restart);
        destroyRecognizer();
    }

    void pause() {
        paused = true;
        handler.removeCallbacks(restart);
        destroyRecognizer();
    }

    void resume() {
        paused = false;
        if (wanted && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            listen();
        }
    }

    // ---- Listening ------------------------------------------------------------------------

    private boolean onDeviceAvailable() {
        return Build.VERSION.SDK_INT >= 31 && !onDeviceFailed
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity);
    }

    private void listen() {
        handler.removeCallbacks(restart);
        if (!wanted || paused) return;
        if (recognizer == null) {
            // On-device where the phone has it: no network round trip, and on most phones no
            // start beep every few seconds, which the regular recogniser can play.
            if (Build.VERSION.SDK_INT >= 31 && onDeviceAvailable()) {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(activity);
                onDevice = true;
            } else {
                recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
                onDevice = false;
            }
            recognizer.setRecognitionListener(this);
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
        startedAt = SystemClock.elapsedRealtime();
        try {
            recognizer.startListening(intent);
        } catch (RuntimeException e) {
            destroyRecognizer();
            onError(SpeechRecognizer.ERROR_CLIENT);
        }
    }

    private void destroyRecognizer() {
        if (recognizer == null) return;
        try {
            recognizer.cancel();
            recognizer.destroy();
        } catch (RuntimeException ignored) {
            // Already gone.
        }
        recognizer = null;
    }

    private void fail(String error) {
        stop();
        send("speech-error", error, null);
    }

    @Override
    public void onResults(Bundle results) {
        fastFailures = 0;
        ArrayList<String> texts = results == null ? null
                : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (texts != null && !texts.isEmpty()) send("speech-result", null, texts);
        if (wanted && !paused) handler.postDelayed(restart, RESTART_MS);
    }

    @Override
    public void onError(int error) {
        if (!wanted || paused) return;
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            fail("not-allowed");
            return;
        }
        // 12 and 13 are ERROR_LANGUAGE_NOT_SUPPORTED and ERROR_LANGUAGE_UNAVAILABLE (API 31):
        // the on-device engine without an Indian English pack. Fall back to the regular one.
        if (onDevice && (error == 12 || error == 13)) {
            onDeviceFailed = true;
            destroyRecognizer();
            handler.post(restart);
            return;
        }
        // A busy or disconnected recogniser is not coming back on its own; build a new one.
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT
                || error == 11) {
            destroyRecognizer();
        }
        // Silence and mumbling end a session in the ordinary way and are not failures.
        boolean ordinary = error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
        if (!ordinary && SystemClock.elapsedRealtime() - startedAt < FAST_FAILURE_MS) {
            fastFailures++;
        } else {
            fastFailures = 0;
        }
        if (fastFailures >= MAX_FAST_FAILURES) {
            fail("unavailable");
            return;
        }
        handler.postDelayed(restart, RESTART_MS * (1 + fastFailures));
    }

    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onPartialResults(Bundle partialResults) { }
    @Override public void onEvent(int eventType, Bundle params) { }

    // ---- To the page ----------------------------------------------------------------------

    private void send(String type, String error, ArrayList<String> texts) {
        if (reply == null) return;
        JSONObject message = new JSONObject();
        try {
            message.put("type", type);
            if (error != null) message.put("error", error);
            if (texts != null) message.put("texts", new JSONArray(texts));
        } catch (JSONException e) {
            return;
        }
        try {
            reply.postMessage(message.toString());
        } catch (RuntimeException ignored) {
            // The page that asked has gone.
        }
    }
}
