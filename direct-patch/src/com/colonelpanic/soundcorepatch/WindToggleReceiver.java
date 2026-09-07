package com.colonelpanic.soundcorepatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

public final class WindToggleReceiver extends BroadcastReceiver {
  public static final String ACTION = "com.colonelpanic.soundcoreactions.TOGGLE_WIND_REDUCTION";
  private static final Handler handler = new Handler(Looper.getMainLooper());
  private static boolean busy;

  @Override
  public void onReceive(Context context, Intent intent) {
    if (!ACTION.equals(intent.getAction())) return;
    boolean ordered = isOrderedBroadcast();
    PendingResult pending = goAsync();
    run(
        context,
        (success, message) -> {
          if (ordered) {
            pending.setResultCode(success ? -1 : 0);
            pending.setResultData(message);
          }
          pending.finish();
        });
  }

  static void run(Context context, WindToggle.Result callback) {
    Context app = context.getApplicationContext();
    handler.post(
        () -> {
          if (busy) {
            callback.finish(false, "A wind reduction command is already running.");
            return;
          }
          busy = true;
          final Runnable[] timeout = new Runnable[1];
          WindToggle.Result result =
              (success, message) -> {
                if (timeout[0] != null) handler.removeCallbacks(timeout[0]);
                busy = false;
                Log.i("SoundcoreActions", message);
                Toast.makeText(app, message, Toast.LENGTH_SHORT).show();
                callback.finish(success, message);
              };
          try {
            WindToggle toggle = new WindToggle(new WindDevice(app, handler), result);
            timeout[0] = toggle::timeout;
            handler.postDelayed(timeout[0], 8000);
            toggle.start();
          } catch (Exception error) {
            Log.w("SoundcoreActions", "Wind command could not start", error);
            result.finish(
                false,
                "Wind reduction unavailable. Connect Liberty 5 Pro in Soundcore and try again.");
          }
        });
  }
}
