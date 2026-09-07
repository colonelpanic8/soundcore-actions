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

  private static final class FakeWind implements WindToggle.Device {
    WindToggle.Listener listener;
    int reads, writes, closes;
    boolean written;
    boolean failWrite;

    public void listen(WindToggle.Listener value) {
      listener = value;
    }

    public void refresh() {
      reads++;
    }

    public void write(boolean value) {
      writes++;
      written = value;
      if (failWrite) throw new IllegalStateException("disconnected");
    }

    public void close() {
      closes++;
    }
  }

  private void testWind() {
    for (boolean initial : new boolean[] {false, true}) {
      FakeWind device = new FakeWind();
      int[] results = {0};
      WindToggle toggle =
          new WindToggle(
              device,
              (success, message) -> {
                check(success, "Wind confirmed");
                results[0]++;
              });
      toggle.start();
      check(device.reads == 1 && device.writes == 0, "Read before write");
      device.listener.state(true, initial);
      check(device.writes == 1 && device.written != initial, "Toggle fresh state");
      device.listener.state(true, initial);
      check(device.writes == 1, "Ignore unrelated reads while awaiting acknowledgement");
      device.listener.acknowledged(true);
      check(device.reads == 2 && results[0] == 0, "Acknowledgement requires readback");
      device.listener.state(true, !initial);
      toggle.timeout();
      device.listener.acknowledged(true);
      check(results[0] == 1 && device.closes == 1, "Complete and clean up exactly once");
    }
    for (int failure = 0; failure < 5; failure++) {
      FakeWind device = new FakeWind();
      int[] results = {0};
      WindToggle toggle =
          new WindToggle(
              device,
              (success, message) -> {
                check(!success, "Failure cannot report success");
                results[0]++;
              });
      toggle.start();
      if (failure == 0) device.listener.state(false, false);
      else if (failure == 1) toggle.timeout();
      else {
        device.failWrite = failure == 2;
        device.listener.state(true, false);
        if (failure == 3) device.listener.acknowledged(false);
        if (failure == 4) {
          device.listener.acknowledged(true);
          device.listener.state(true, false);
        }
      }
      check(results[0] == 1 && device.closes == 1, "Failure cleans up listener");
      if (failure < 2) check(device.writes == 0, "No write without fresh state");
    }
  }

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
      testWind();
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
