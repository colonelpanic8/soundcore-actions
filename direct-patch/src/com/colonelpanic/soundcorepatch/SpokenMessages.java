package com.colonelpanic.soundcorepatch;

import android.app.Notification;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.PowerManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SpokenMessages {
  private SpokenMessages() {}

  static Message extract(
      String packageName, String notificationKey, long postTime, Notification notification) {
    if (notification == null || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0)
      return null;
    Bundle extras = notification.extras;
    if (extras == null) return null;
    CharSequence sender = null;
    CharSequence body = null;
    long messageTime = postTime;
    Parcelable[] bundled = messages(extras);
    if (bundled != null) {
      List<Notification.MessagingStyle.Message> messages =
          Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundled);
      for (int i = messages.size() - 1; i >= 0; i--) {
        Notification.MessagingStyle.Message message = messages.get(i);
        if (blank(message.getText())) continue;
        if (message.getSenderPerson() == null || blank(message.getSenderPerson().getName()))
          continue;
        body = message.getText();
        sender = message.getSenderPerson().getName();
        messageTime = message.getTimestamp();
        break;
      }
      if (blank(body)) return null;
    }
    if (blank(body)) {
      if (!Notification.CATEGORY_MESSAGE.equals(notification.category)) return null;
      sender = extras.getCharSequence(Notification.EXTRA_TITLE);
      body = extras.getCharSequence(Notification.EXTRA_TEXT);
      messageTime = postTime;
    }
    sender = clean(sender);
    body = clean(body);
    if (blank(body)) return null;
    if (blank(sender)) sender = "a contact";
    String fingerprint =
        packageName
            + '\u0000'
            + notificationKey
            + '\u0000'
            + messageTime
            + '\u0000'
            + sender
            + '\u0000'
            + body;
    return new Message(
        packageName,
        sender.toString(),
        body.toString(),
        fingerprint,
        messageTime > 0 ? messageTime : postTime);
  }

  static String speech(Message message, boolean readBody) {
    String introduction = "Message from " + message.sender + ".";
    if (!readBody) return introduction;
    String body = message.body;
    int limit = Math.min(600, android.speech.tts.TextToSpeech.getMaxSpeechInputLength() - 80);
    if (body.length() > limit) body = body.substring(0, limit) + "…";
    return introduction + " " + body;
  }

  static boolean ready(android.content.Context context) {
    PowerManager power = context.getSystemService(PowerManager.class);
    AudioManager audio = context.getSystemService(AudioManager.class);
    return power != null
        && !power.isInteractive()
        && audio != null
        && audio.getMode() == AudioManager.MODE_NORMAL
        && hasSoundcoreOutput(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS));
  }

  static boolean hasSoundcoreOutput(AudioDeviceInfo[] devices) {
    for (AudioDeviceInfo device : devices)
      if (isBluetoothOutput(device.getType()) && isSoundcoreName(device.getProductName()))
        return true;
    return false;
  }

  static boolean isBluetoothOutput(int type) {
    return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        || type == AudioDeviceInfo.TYPE_BLE_HEADSET
        || type == AudioDeviceInfo.TYPE_BLE_SPEAKER;
  }

  static boolean isSoundcoreName(CharSequence name) {
    if (name == null) return false;
    String normalized = name.toString().toLowerCase(Locale.ROOT);
    return normalized.contains("soundcore") || normalized.contains("liberty 5");
  }

  static List<String> soundcoreOutputs(android.content.Context context) {
    AudioManager audio = context.getSystemService(AudioManager.class);
    List<String> names = new ArrayList<>();
    if (audio == null) return names;
    for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
      if (isBluetoothOutput(device.getType()) && isSoundcoreName(device.getProductName()))
        names.add(device.getProductName().toString());
    return names;
  }

  private static boolean blank(CharSequence value) {
    return value == null || value.toString().trim().isEmpty();
  }

  @SuppressWarnings("deprecation")
  private static Parcelable[] messages(Bundle extras) {
    if (Build.VERSION.SDK_INT >= 33)
      return extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable.class);
    return extras.getParcelableArray(Notification.EXTRA_MESSAGES);
  }

  private static CharSequence clean(CharSequence value) {
    if (value == null) return null;
    return value.toString().replaceAll("\\s+", " ").trim();
  }

  static final class Message {
    final String packageName;
    final String sender;
    final String body;
    final String fingerprint;
    final long eventTime;

    Message(String packageName, String sender, String body, String fingerprint, long eventTime) {
      this.packageName = packageName;
      this.sender = sender;
      this.body = body;
      this.fingerprint = fingerprint;
      this.eventTime = eventTime;
    }
  }
}
