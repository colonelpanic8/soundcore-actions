package com.colonelpanic.soundcorepatch;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import com.colonelpanic.soundcoretest.Fixtures;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

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

  private interface MainTask {
    void run() throws Exception;
  }

  private void main(MainTask task) throws Exception {
    Throwable[] failure = new Throwable[1];
    runOnMainSync(
        () -> {
          try {
            task.run();
          } catch (Throwable error) {
            failure[0] = error;
          }
        });
    if (failure[0] instanceof Exception) throw (Exception) failure[0];
    if (failure[0] != null) throw new AssertionError(failure[0]);
  }

  private static boolean await(BooleanSupplier condition) throws InterruptedException {
    for (int i = 0; i < 50; i++) {
      if (condition.getAsBoolean()) return true;
      Thread.sleep(100);
    }
    return condition.getAsBoolean();
  }

  private static String text(TextView view) {
    return view.getText().toString();
  }

  private void testLabels() throws Exception {
    Context context = getTargetContext();
    ControlLabels labels = new ControlLabels();
    String anka = "Anka Voice Assistant";
    Map<String, String> mapped = new HashMap<>();
    mapped.put(anka, "Paseo Live Voice");
    mapped.put("AI Translation", "Google Live Translate");
    Map<String, String> remapped = Collections.singletonMap(anka, "Calculator");
    main(
        () -> {
          LinearLayout root = new LinearLayout(context);
          LinearLayout nested = new LinearLayout(context);
          TextView assistant = new TextView(context);
          TextView translation = new TextView(context);
          TextView other = new TextView(context);
          EditText editable = new EditText(context);
          assistant.setText(anka);
          translation.setText("AI Translation");
          other.setText("Volume Up");
          editable.setText(anka);
          nested.addView(translation);
          root.addView(assistant);
          root.addView(nested);
          root.addView(other);
          root.addView(editable);
          labels.update(root, mapped);
          check("Paseo Live Voice".equals(text(assistant)), "Mapped label replaced");
          check("Google Live Translate".equals(text(translation)), "Nested label replaced");
          check("Volume Up".equals(text(other)), "Unrelated label untouched");
          check(anka.equals(text(editable)), "Editable text untouched");
          labels.update(root, remapped);
          check("Calculator".equals(text(assistant)), "Remapping replaces from the original");
          check("AI Translation".equals(text(translation)), "Removed mapping restores original");
          assistant.setText("Play/Pause");
          labels.update(root, remapped);
          check("Play/Pause".equals(text(assistant)), "Recycled row keeps its new text");
          assistant.setText(anka);
          labels.update(root, remapped);
          check("Calculator".equals(text(assistant)), "Rebound row replaced again");
          labels.update(root, Collections.emptyMap());
          check(anka.equals(text(assistant)), "Disabling restores original");
          labels.update(root, Collections.emptyMap());
          check(anka.equals(text(assistant)), "Restored label stays stable");
        });
  }

  private void testLiveLabels() throws Exception {
    Context context = getTargetContext();
    int id =
        context
            .getResources()
            .getIdentifier("translation_anker_voice_assistant", "string", context.getPackageName());
    check(id != 0, "Test resources include the vendor label");
    String original = context.getString(id);
    Rules.Action previous = Rules.stored(context, "activity", Rules.ANKA);
    Rules.put(context, "activity", Rules.ANKA, new Rules.Action("app", "Custom Voice", "", ""));
    check(
        "Custom Voice".equals(ControlLabels.replacements(context).get(original)),
        "Replacement map resolves the vendor resource");
    ControlLabels.install((Application) context.getApplicationContext());
    Activity screen =
        startActivitySync(
            new Intent(context, Fixtures.Screen.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    TextView[] views = new TextView[3];
    PopupWindow[] popup = new PopupWindow[1];
    Dialog[] dialog = new Dialog[1];
    try {
      main(
          () -> {
            for (int i = 0; i < views.length; i++) {
              views[i] = new TextView(screen);
              views[i].setText(original);
            }
            screen.setContentView(views[0]);
            popup[0] =
                new PopupWindow(
                    views[1],
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            popup[0].showAtLocation(screen.getWindow().getDecorView(), Gravity.BOTTOM, 0, 0);
            dialog[0] = new Dialog(screen);
            dialog[0].setContentView(views[2]);
            dialog[0].show();
          });
      check(
          await(
              () ->
                  "Custom Voice".equals(text(views[0]))
                      && "Custom Voice".equals(text(views[1]))
                      && "Custom Voice".equals(text(views[2]))),
          "Live labels replaced in the activity, a popup window, and a dialog");
      Rules.setEnabled(context, false);
      main(
          () -> {
            callActivityOnPause(screen);
            callActivityOnResume(screen);
          });
      check(
          await(
              () ->
                  original.equals(text(views[0]))
                      && original.equals(text(views[1]))
                      && original.equals(text(views[2]))),
          "Disabling mappings restores live labels in every window");
    } finally {
      Rules.setEnabled(context, true);
      Rules.put(context, "activity", Rules.ANKA, previous);
      main(
          () -> {
            if (popup[0] != null) popup[0].dismiss();
            if (dialog[0] != null) dialog[0].dismiss();
            screen.finish();
          });
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
      testLabels();
      Rules.setEnabled(context, true);
      testLiveLabels();
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
