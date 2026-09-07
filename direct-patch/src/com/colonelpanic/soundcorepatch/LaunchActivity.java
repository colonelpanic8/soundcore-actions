package com.colonelpanic.soundcorepatch;

import android.app.Activity;
import android.os.Bundle;

public final class LaunchActivity extends Activity {
  String source;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    if (source != null) ActionRunner.trigger(this, "activity", source);
    finish();
  }
}
