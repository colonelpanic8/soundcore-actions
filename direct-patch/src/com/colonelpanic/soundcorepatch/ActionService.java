package com.colonelpanic.soundcorepatch;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public final class ActionService extends Service {
  String source;

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    ActionRunner.trigger(this, "service", source);
    stopSelf(startId);
    return START_NOT_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    ActionRunner.trigger(this, "service", source);
    return null;
  }
}
