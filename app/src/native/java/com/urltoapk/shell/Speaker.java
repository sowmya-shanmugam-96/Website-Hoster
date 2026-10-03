package com.urltoapk.shell;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.webkit.JavaScriptReplyProxy;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Android's own text-to-speech, for a page that wants to say something out loud.
 *
 * The WebView exposes the Web Speech synthesiser but has no voice behind it, so a page's
 * speechSynthesis.speak() is silent. This speaks for it instead:
 *
 *   NativePush.postMessage('{"type":"speak","id":"1","text":"Done."}')  -> says it
 *   NativePush.postMessage('{"type":"speak-stop"}')                     -> stops talking
 *
 * and replies {type: "speak-done", id} once that sentence has been said, cut off or failed,
 * so the page knows when the room is quiet again. A new sentence replaces whatever was still
 * being said. The engine starts on the first request, not with the app, since most sessions
 * never need it; a sentence asked for while it starts is said once it is ready.
 */
final class Speaker {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // Waiting for their sentence to finish, by utterance id.
    private final Map<String, JavaScriptReplyProxy> replies = new HashMap<>();

    private TextToSpeech tts;
    private boolean ready;
    private String pendingId;
    private String pendingText;

    Speaker(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Main thread only. */
    void speak(String id, String text, JavaScriptReplyProxy reply) {
        if (text == null || text.trim().isEmpty()) {
            done(id, reply);
            return;
        }
        // Whoever was waiting on the sentence this one cuts off can stop waiting.
        stop();
        replies.put(id, reply);
        if (ready) {
            say(id, text);
            return;
        }
        pendingId = id;
        pendingText = text;
        if (tts == null) tts = new TextToSpeech(context, this::onInit);
    }

    /** Main thread only. */
    void stop() {
        pendingId = null;
        pendingText = null;
        if (tts != null) tts.stop();
        for (Map.Entry<String, JavaScriptReplyProxy> entry : replies.entrySet()) {
            done(entry.getKey(), entry.getValue());
        }
        replies.clear();
    }

    void shutdown() {
        stop();
        if (tts != null) tts.shutdown();
        tts = null;
        ready = false;
    }

    private void onInit(int status) {
        if (tts == null) return;
        if (status != TextToSpeech.SUCCESS) {
            // No engine on this phone; answer every caller so none waits on a voice.
            tts.shutdown();
            tts = null;
            stop();
            return;
        }
        // Indian English where the engine has it, else whatever English it does.
        int lang = tts.setLanguage(new Locale("en", "IN"));
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.ENGLISH);
        }
        // The assistant usage rather than media, so it is not the stream WakeListener mutes
        // around its restarts, and it ducks rather than stops any music playing.
        tts.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                finish(utteranceId);
            }

            @Override
            public void onStop(String utteranceId, boolean interrupted) {
                finish(utteranceId);
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onError(String utteranceId) {
                finish(utteranceId);
            }
        });
        ready = true;
        if (pendingId != null) {
            String id = pendingId;
            String text = pendingText;
            pendingId = null;
            pendingText = null;
            say(id, text);
        }
    }

    private void say(String id, String text) {
        int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), id);
        if (result != TextToSpeech.SUCCESS) finish(id);
    }

    // Progress callbacks arrive on the engine's thread.
    private void finish(String id) {
        handler.post(() -> {
            JavaScriptReplyProxy reply = replies.remove(id);
            if (reply != null) done(id, reply);
        });
    }

    private static void done(String id, JavaScriptReplyProxy reply) {
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "speak-done");
            msg.put("id", id == null ? "" : id);
        } catch (JSONException e) {
            return;
        }
        reply.postMessage(msg.toString());
    }
}
