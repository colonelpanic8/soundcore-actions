package com.colonelpanic.soundcorepatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

public final class WindToggleReceiver extends BroadcastReceiver {
  public static final String ACTION = "com.colonelpanic.soundcoreactions.TOGGLE_WIND_REDUCTION";
  public static final String EXTRA_STATE = "state";
  static final long DEFAULT_BUDGET_MILLIS = 8000;
  private static final long CONNECT_RETRY_MILLIS = 500;
  private static final long COMMAND_MILLIS = 3000;
  private static final Handler handler = new Handler(Looper.getMainLooper());
  private static boolean busy;

  @Override
  public void onReceive(Context context, Intent intent) {
    if (!ACTION.equals(intent.getAction())) return;
    String state = intent.getStringExtra(EXTRA_STATE);
    WindToggle.Mode mode = mode(state);
    if (mode == null) {
      Log.w("SoundcoreActions", "Ignoring wind reduction broadcast with state " + state);
      return;
    }
    boolean ordered = isOrderedBroadcast();
    PendingResult pending = goAsync();
    run(
        context,
        mode,
        DEFAULT_BUDGET_MILLIS,
        outcome -> {
          if (ordered) {
            pending.setResultCode(outcome.success() ? -1 : 0);
            pending.setResultData(outcome.message);
          }
          pending.finish();
        });
  }

  /** Parses the {@code state} extra; absent means toggle, and an unknown value is null. */
  static WindToggle.Mode mode(String state) {
    if (state == null || state.equals("toggle")) return WindToggle.Mode.TOGGLE;
    if (state.equals("on")) return WindToggle.Mode.ON;
    if (state.equals("off")) return WindToggle.Mode.OFF;
    return null;
  }

  static void run(Context context, WindToggle.Result callback) {
    run(context, WindToggle.Mode.TOGGLE, DEFAULT_BUDGET_MILLIS, callback);
  }

  /** Runs one wind reduction command, reporting within {@code budgetMillis}. */
  static void run(
      Context context, WindToggle.Mode mode, long budgetMillis, WindToggle.Result callback) {
    Context app = context.getApplicationContext();
    long deadline = SystemClock.elapsedRealtime() + budgetMillis;
    handler.post(
        () -> {
          if (busy) {
            callback.finish(
                new WindToggle.Outcome(
                    "busy", null, "A wind reduction command is already running."));
            return;
          }
          busy = true;
          attempt(
              app,
              mode,
              deadline,
              outcome -> {
                busy = false;
                Log.i("SoundcoreActions", outcome.message);
                Toast.makeText(app, outcome.message, Toast.LENGTH_SHORT).show();
                callback.finish(outcome);
              });
        });
  }

  /** Soundcore may still be reconnecting after a cold start, so wait for it within the budget. */
  private static void attempt(
      Context app, WindToggle.Mode mode, long deadline, WindToggle.Result result) {
    WindDevice device;
    try {
      device = new WindDevice(app, handler);
    } catch (Exception error) {
      if (deadline - SystemClock.elapsedRealtime() > CONNECT_RETRY_MILLIS + COMMAND_MILLIS) {
        handler.postDelayed(() -> attempt(app, mode, deadline, result), CONNECT_RETRY_MILLIS);
        return;
      }
      Log.w("SoundcoreActions", "Wind command could not start", error);
      result.finish(
          new WindToggle.Outcome(
              "needs_configuration",
              null,
              "Wind reduction unavailable. Open Soundcore with Liberty 5 Pro connected and try"
                  + " again."));
      return;
    }
    final Runnable[] timeout = new Runnable[1];
    WindToggle toggle =
        new WindToggle(
            device,
            mode,
            outcome -> {
              handler.removeCallbacks(timeout[0]);
              result.finish(outcome);
            });
    timeout[0] = toggle::timeout;
    handler.postDelayed(timeout[0], Math.max(0, deadline - SystemClock.elapsedRealtime()));
    toggle.start();
  }
}
