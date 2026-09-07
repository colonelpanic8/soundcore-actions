package com.colonelpanic.soundcorepatch;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;
import java.net.URISyntaxException;

final class ActionRunner {
  private ActionRunner() {}

  static Intent intent(Context context, Rules.Action action) throws URISyntaxException {
    if ("wind".equals(action.type))
      return new Intent(WindToggleReceiver.ACTION).setClass(context, WindToggleReceiver.class);
    Intent intent;
    switch (action.type) {
      case "app":
        intent = context.getPackageManager().getLaunchIntentForPackage(action.packageName);
        if (intent == null)
          throw new IllegalArgumentException("That app is not installed or has no launch screen.");
        break;
      case "link":
        Uri uri = Uri.parse(action.value);
        if (uri.getScheme() == null)
          throw new IllegalArgumentException("Enter a complete link, including its scheme.");
        intent = new Intent(Intent.ACTION_VIEW, uri);
        break;
      case "intent":
      case "broadcast":
        if (!action.value.startsWith("intent:"))
          throw new IllegalArgumentException("Use an intent: URI, including #Intent;…;end.");
        intent = Intent.parseUri(action.value, Intent.URI_INTENT_SCHEME);
        break;
      default:
        throw new IllegalArgumentException("Unknown action type.");
    }
    if (!action.packageName.isEmpty()) intent.setPackage(action.packageName);
    if (intent.getComponent() != null
        && intent.getComponent().getClassName().startsWith("com.colonelpanic.soundcorepatch.")) {
      throw new IllegalArgumentException(
          "Choose an action outside Soundcore Actions to avoid a loop.");
    }
    return intent;
  }

  static void run(Context context, Rules.Action action) {
    if ("wind".equals(action.type)) {
      WindToggleReceiver.run(context, (success, message) -> {});
      return;
    }
    try {
      Intent intent = intent(context, action);
      if ("broadcast".equals(action.type)) context.sendBroadcast(intent);
      else {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
      }
      Log.i("SoundcoreActions", "Dispatched custom " + action.type + " action");
    } catch (RuntimeException | URISyntaxException error) {
      Toast.makeText(context, "Action could not run: " + error.getMessage(), Toast.LENGTH_LONG)
          .show();
      Log.e("SoundcoreActions", "Custom action failed", error);
    }
  }

  static void trigger(Context context, String kind, String source) {
    Rules.Action action = Rules.get(context, kind, source);
    if (action != null) run(context, action);
  }
}
