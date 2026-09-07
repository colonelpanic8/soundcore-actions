package com.colonelpanic.soundcorepatch;

import android.app.Activity;
import android.os.Bundle;

public final class LaunchActivity extends Activity {
  String source;

  @Override
  public void onCreate(Bundle state) {
    Rules.Action action = source == null ? null : Rules.get(this, "activity", source);
    if (action != null && "wind".equals(action.type)) setTheme(android.R.style.Theme_NoDisplay);
    super.onCreate(state);
    if (action != null) ActionRunner.run(this, action);
    finish();
  }
}
