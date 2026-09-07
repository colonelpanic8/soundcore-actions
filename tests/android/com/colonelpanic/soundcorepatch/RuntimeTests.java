package com.colonelpanic.soundcorepatch;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import com.colonelpanic.soundcoretest.Fixtures;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class RuntimeTests extends Instrumentation {
  private int checks;

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    Context context = getTargetContext();
    boolean wasEnabled = Rules.enabled(context);
    String[] kinds = {"activity", "service", "receiver"};
    String[] names = {
      Fixtures.Screen.class.getName(),
      Fixtures.Background.class.getName(),
      Fixtures.Receiver.class.getName()
    };
    Rules.Action[] previous = new Rules.Action[names.length];
    for (int i = 0; i < names.length; i++) previous[i] = Rules.stored(context, kinds[i], names[i]);
    int code = Activity.RESULT_CANCELED;
    try {
      Rules.setEnabled(context, true);
      Intent link = ActionRunner.intent(context, Rules.Action.paseo());
      check(Intent.ACTION_VIEW.equals(link.getAction()), "Link action");
      check("paseo://live-voice".equals(link.getDataString()), "Link data");
      check("sh.paseo.assembly".equals(link.getPackage()), "Link package");
      Rules.Action explicit =
          new Rules.Action(
              "intent",
              "Example",
              "",
              "intent:#Intent;action=example.ACTION;S.message=hello;i.count=7;end");
      Intent parsed = ActionRunner.intent(context, explicit);
      check("hello".equals(parsed.getStringExtra("message")), "String intent extra");
      check(parsed.getIntExtra("count", 0) == 7, "Integer intent extra");
      try {
        ActionRunner.intent(context, new Rules.Action("link", "Bad", "", "no-scheme"));
        throw new AssertionError("Invalid link accepted");
      } catch (IllegalArgumentException expected) {
        checks++;
      }
      try {
        ActionRunner.intent(
            context,
            new Rules.Action(
                "intent",
                "Loop",
                "",
                "intent:#Intent;component=com.oceanwing.soundcore/com.colonelpanic.soundcorepatch.LaunchActivity;end"));
        throw new AssertionError("Recursive target accepted");
      } catch (IllegalArgumentException expected) {
        checks++;
      }
      for (int i = 0; i < kinds.length; i++) Rules.put(context, kinds[i], names[i], explicit);
      check(
          Rules.stored(context, "activity", names[0]).value.equals(explicit.value),
          "Mapping persistence");
      ComponentFactory factory = new ComponentFactory();
      ClassLoader loader = getClass().getClassLoader();
      check(
          activity(factory, loader, names[0]) instanceof LaunchActivity,
          "Arbitrary activity intercepted");
      check(
          factory.instantiateService(loader, names[1], new Intent()) instanceof ActionService,
          "Arbitrary service intercepted");
      check(
          factory.instantiateReceiver(loader, names[2], new Intent()) instanceof ActionReceiver,
          "Arbitrary receiver intercepted");
      Rules.setEnabled(context, false);
      check(
          activity(factory, loader, names[0]) instanceof Fixtures.Screen,
          "Global disable restores original activity");
      check(
          factory.instantiateService(loader, names[1], new Intent()) instanceof Fixtures.Background,
          "Global disable restores original service");
      check(
          factory.instantiateReceiver(loader, names[2], new Intent()) instanceof Fixtures.Receiver,
          "Global disable restores original receiver");
      Rules.setEnabled(context, true);
      Rules.put(context, "activity", names[0], null);
      check(
          activity(factory, loader, names[0]) instanceof Fixtures.Screen,
          "Removing a mapping restores original behavior");
      broadcast(context);
      result.putString("stream", "Passed " + checks + " runtime assertions\n");
      code = Activity.RESULT_OK;
    } catch (Throwable failure) {
      result.putString("stream", android.util.Log.getStackTraceString(failure));

    } finally {
      for (int i = 0; i < kinds.length; i++) Rules.put(context, kinds[i], names[i], previous[i]);
      Rules.setEnabled(context, wasEnabled);
    }
    finish(code, result);
  }

  private Activity activity(ComponentFactory factory, ClassLoader loader, String name)
      throws Exception {
    Activity[] result = new Activity[1];
    Exception[] error = new Exception[1];
    runOnMainSync(
        () -> {
          try {
            result[0] = factory.instantiateActivity(loader, name, new Intent());
          } catch (Exception failure) {
            error[0] = failure;
          }
        });
    if (error[0] != null) throw error[0];
    return result[0];
  }

  @SuppressWarnings("deprecation")
  private void broadcast(Context context) throws Exception {
    String action = "com.colonelpanic.soundcoreactions.TEST";
    CountDownLatch received = new CountDownLatch(1);
    BroadcastReceiver receiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context ignored, Intent intent) {
            if ("payload".equals(intent.getStringExtra("message"))) received.countDown();
          }
        };
    if (Build.VERSION.SDK_INT >= 33)
      context.registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
    else context.registerReceiver(receiver, new IntentFilter(action));
    try {
      ActionRunner.run(
          context,
          new Rules.Action(
              "broadcast",
              "Test broadcast",
              context.getPackageName(),
              "intent:#Intent;action=" + action + ";S.message=payload;end"));
      check(
          received.await(3, TimeUnit.SECONDS),
          "Broadcast reaches its intended receiver with extras");
    } finally {
      context.unregisterReceiver(receiver);
    }
  }

  private void check(boolean condition, String name) {
    if (!condition) throw new AssertionError(name);
    checks++;
  }
}
