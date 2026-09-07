package com.colonelpanic.soundcoretest;

import android.app.Activity;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

public final class Fixtures {
  public static final class Screen extends Activity {}

  public static final class Background extends Service {
    @Override
    public IBinder onBind(Intent intent) {
      return null;
    }
  }

  public static final class Receiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {}
  }
}
