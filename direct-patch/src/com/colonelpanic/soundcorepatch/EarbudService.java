package com.colonelpanic.soundcorepatch;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public final class EarbudService extends Service {
  private static final String CHANNEL = "soundcore-actions-earbuds";
  static final int NOTIFICATION_ID = 0x534341;

  static boolean needed(Context context) {
    return (Rules.get(context, "activity", Rules.ANKA) != null
            || Rules.get(context, "activity", Rules.REALTIME) != null
            || Rules.get(context, "activity", Rules.FACE) != null)
        && (Build.VERSION.SDK_INT < 31
            || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED);
  }

  static void sync(Context context) {
    if (!"com.oceanwing.soundcore".equals(context.getPackageName())
        || !context.getPackageName().equals(Application.getProcessName())) return;
    update(context);
  }

  static void update(Context context) {
    Intent intent = new Intent(context, EarbudService.class);
    try {
      if (needed(context)) context.startForegroundService(intent);
      else context.stopService(intent);
    } catch (RuntimeException error) {
      Log.w("SoundcoreActions", "Could not update background earbud listener", error);
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (!needed(this)) {
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
      return START_NOT_STICKY;
    }
    NotificationManager notifications = getSystemService(NotificationManager.class);
    notifications.createNotificationChannel(
        new NotificationChannel(CHANNEL, "Earbud actions", NotificationManager.IMPORTANCE_LOW));
    PendingIntent settings =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, SettingsActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification notification =
        new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Earbud actions ready")
            .setContentText(
                "Listening for Anka and translation gestures. Tap to manage custom mappings.")
            .setContentIntent(settings)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    if (Build.VERSION.SDK_INT >= 29)
      startForeground(
          NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    else startForeground(NOTIFICATION_ID, notification);
    EarbudActions.install(getApplication());
    Log.i("SoundcoreActions", "Background earbud listener active");
    return START_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
