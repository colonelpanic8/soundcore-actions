package com.colonelpanic.soundcorepatch;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

public final class MessageReaderService extends NotificationListenerService {
  private static final String TAG = "SoundcoreActions";
  private static final long MAX_AGE_MILLIS = 60_000;
  private static final int MAX_QUEUE = 8;
  private static final int MAX_SEEN = 100;

  private final Object lock = new Object();
  private final ArrayDeque<SpokenMessages.Message> queue = new ArrayDeque<>();
  private final Set<String> seen = new LinkedHashSet<>();
  private TextToSpeech textToSpeech;
  private boolean initializing;
  private boolean speaking;
  private boolean focusHeld;
  private long utteranceSequence;
  private AudioManager audioManager;
  private AudioFocusRequest focusRequest;

  private final BroadcastReceiver screenReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) stopSpeaking();
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    audioManager = getSystemService(AudioManager.class);
    IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(screenReceiver, screen, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(screenReceiver, screen);
  }

  @Override
  public void onNotificationPosted(StatusBarNotification posted) {
    Rules.SpokenSettings settings = Rules.spoken(this);
    if (!settings.enabled || !settings.packageNames.contains(posted.getPackageName())) return;
    SpokenMessages.Message message =
        SpokenMessages.extract(
            posted.getPackageName(),
            posted.getKey(),
            posted.getPostTime(),
            posted.getNotification());
    if (message == null
        || Math.abs(System.currentTimeMillis() - message.eventTime) > MAX_AGE_MILLIS
        || !SpokenMessages.ready(this)) return;
    synchronized (lock) {
      if (!seen.add(message.fingerprint)) return;
      while (seen.size() > MAX_SEEN) seen.remove(seen.iterator().next());
      while (queue.size() >= MAX_QUEUE) queue.removeFirst();
      queue.addLast(message);
      prepareSpeechLocked();
    }
  }

  private void prepareSpeechLocked() {
    if (textToSpeech != null) {
      speakNextLocked();
      return;
    }
    if (initializing) return;
    initializing = true;
    textToSpeech =
        new TextToSpeech(
            getApplicationContext(),
            status -> {
              synchronized (lock) {
                initializing = false;
                if (status != TextToSpeech.SUCCESS || textToSpeech == null) {
                  Log.w(TAG, "Could not initialize text-to-speech for spoken messages");
                  queue.clear();
                  if (textToSpeech != null) textToSpeech.shutdown();
                  textToSpeech = null;
                  return;
                }
                AudioAttributes attributes =
                    new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                textToSpeech.setAudioAttributes(attributes);
                textToSpeech.setOnUtteranceProgressListener(
                    new UtteranceProgressListener() {
                      @Override
                      public void onStart(String utteranceId) {}

                      @Override
                      public void onDone(String utteranceId) {
                        completed();
                      }

                      @Override
                      @SuppressWarnings("deprecation")
                      public void onError(String utteranceId) {
                        completed();
                      }

                      @Override
                      public void onError(String utteranceId, int errorCode) {
                        completed();
                      }
                    });
                speakNextLocked();
              }
            });
  }

  private void completed() {
    synchronized (lock) {
      speaking = false;
      speakNextLocked();
    }
  }

  private void speakNextLocked() {
    if (speaking || textToSpeech == null) return;
    if (!SpokenMessages.ready(this)) {
      queue.clear();
      releaseFocusLocked();
      return;
    }
    SpokenMessages.Message message = queue.pollFirst();
    if (message == null) {
      releaseFocusLocked();
      return;
    }
    if (!requestFocusLocked()) {
      queue.clear();
      return;
    }
    Rules.SpokenSettings settings = Rules.spoken(this);
    if (!settings.enabled || !settings.packageNames.contains(message.packageName)) {
      queue.clear();
      releaseFocusLocked();
      return;
    }
    String utteranceId = "soundcore-message-" + (++utteranceSequence);
    speaking =
        textToSpeech.speak(
                SpokenMessages.speech(message, settings.readBody),
                TextToSpeech.QUEUE_FLUSH,
                new Bundle(),
                utteranceId)
            == TextToSpeech.SUCCESS;
    if (!speaking) speakNextLocked();
  }

  private boolean requestFocusLocked() {
    if (focusHeld) return true;
    if (audioManager == null) return false;
    AudioAttributes attributes =
        new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    focusRequest =
        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(change -> {})
            .build();
    focusHeld =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    return focusHeld;
  }

  private void stopSpeaking() {
    synchronized (lock) {
      queue.clear();
      speaking = false;
      if (textToSpeech != null) textToSpeech.stop();
      releaseFocusLocked();
    }
  }

  private void releaseFocusLocked() {
    if (focusHeld && audioManager != null && focusRequest != null)
      audioManager.abandonAudioFocusRequest(focusRequest);
    focusHeld = false;
    focusRequest = null;
  }

  @Override
  public void onDestroy() {
    try {
      unregisterReceiver(screenReceiver);
    } catch (IllegalArgumentException ignored) {
    }
    synchronized (lock) {
      queue.clear();
      speaking = false;
      if (textToSpeech != null) {
        textToSpeech.stop();
        textToSpeech.shutdown();
        textToSpeech = null;
      }
      releaseFocusLocked();
    }
    super.onDestroy();
  }

  static boolean accessGranted(Context context) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    return manager != null
        && manager.isNotificationListenerAccessGranted(
            new android.content.ComponentName(context, MessageReaderService.class));
  }
}
