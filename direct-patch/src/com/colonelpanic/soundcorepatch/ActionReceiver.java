package com.colonelpanic.soundcorepatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ActionReceiver extends BroadcastReceiver {
  String source;

  @Override
  public void onReceive(Context context, Intent intent) {
    ActionRunner.trigger(context, "receiver", source);
  }
}
