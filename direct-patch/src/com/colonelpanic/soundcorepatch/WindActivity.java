package com.colonelpanic.soundcorepatch;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Invisible entry point for apps that can only launch activities, such as EVA's declarative
 * packages. Starts the wind command in the background and finishes at once; the result is shown as
 * a toast.
 */
public final class WindActivity extends Activity {
  public static final String ACTION = "com.colonelpanic.soundcoreactions.SET_WIND_REDUCTION";

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    String requested = getIntent().getStringExtra(WindToggleReceiver.EXTRA_STATE);
    WindToggle.Mode mode = WindToggleReceiver.mode(requested);
    if (mode == null)
      Toast.makeText(
              this,
              "Unknown wind reduction state \"" + requested + "\". Use on, off, or toggle.",
              Toast.LENGTH_LONG)
          .show();
    else
      WindToggleReceiver.run(this, mode, WindToggleReceiver.DEFAULT_BUDGET_MILLIS, outcome -> {});
    finish();
  }
}
