package com.colonelpanic.soundcorepatch;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.Person;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

  private void testSpokenMessages() {
    Context context = getTargetContext();
    long now = System.currentTimeMillis();
    Person me = new Person.Builder().setName("Me").build();
    Person sender = new Person.Builder().setName("Alice").build();
    Notification notification =
        new Notification.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setStyle(
                new Notification.MessagingStyle(me)
                    .addMessage(
                        new Notification.MessagingStyle.Message("Hello there", now, sender)))
            .build();
    SpokenMessages.Message message =
        SpokenMessages.extract("example.messages", "notification-key", now, notification);
    check(message != null, "Messaging-style notification is recognized");
    check("Alice".equals(message.sender), "Message sender extracted");
    check("Hello there".equals(message.body), "Message body extracted");
    check(
        "Message from Alice. Hello there".equals(SpokenMessages.speech(message, true)),
        "Sender and body speech");
    check(
        "Message from Alice.".equals(SpokenMessages.speech(message, false)), "Sender-only speech");

    Notification fallback =
        new Notification.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentTitle("Bob")
            .setContentText("Fallback message")
            .build();
    SpokenMessages.Message fallbackMessage =
        SpokenMessages.extract("example.messages", "fallback-key", now, fallback);
    check(
        fallbackMessage != null
            && "Bob".equals(fallbackMessage.sender)
            && "Fallback message".equals(fallbackMessage.body),
        "Message-category fallback extracted");
    fallback.flags |= Notification.FLAG_GROUP_SUMMARY;
    check(
        SpokenMessages.extract("example.messages", "summary", now, fallback) == null,
        "Group summary ignored");
    check(SpokenMessages.isSoundcoreName("soundcore Liberty 5 Pro"), "Soundcore output name");
    check(!SpokenMessages.isSoundcoreName("Phone speaker"), "Phone output rejected");
    check(
        SpokenMessages.isBluetoothOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
        "Bluetooth media output accepted");
    check(
        !SpokenMessages.isBluetoothOutput(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
        "Built-in speaker rejected");

    Rules.SpokenSettings previous = Rules.spoken(context);
    try {
      LinkedHashSet<String> packages = new LinkedHashSet<>();
      packages.add("example.messages");
      Rules.setSpokenEnabled(context, true);
      Rules.setSpokenReadBody(context, false);
      Rules.setSpokenPackages(context, packages);
      Rules.SpokenSettings stored = Rules.spoken(context);
      check(stored.enabled, "Spoken messages enabled state persisted");
      check(!stored.readBody, "Sender-only preference persisted");
      check(stored.packageNames.equals(packages), "Messaging app allowlist persisted");
    } finally {
      Rules.setSpokenEnabled(context, previous.enabled);
      Rules.setSpokenReadBody(context, previous.readBody);
      Rules.setSpokenPackages(context, previous.packageNames);
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

  private void testLauncherTasks() throws Exception {
    Context context = getTargetContext();
    Intent settingsIntent =
        Intent.makeMainActivity(new ComponentName(context, SettingsActivity.class))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    Intent soundcoreIntent =
        Intent.makeMainActivity(new ComponentName(context, Fixtures.Screen.class))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    AtomicReference<Activity> resumed = new AtomicReference<>();
    Application app = (Application) context.getApplicationContext();
    Application.ActivityLifecycleCallbacks observer =
        new Application.ActivityLifecycleCallbacks() {
          public void onActivityResumed(Activity activity) {
            resumed.set(activity);
          }

          public void onActivityCreated(Activity activity, Bundle state) {}

          public void onActivityStarted(Activity activity) {}

          public void onActivityPaused(Activity activity) {}

          public void onActivityStopped(Activity activity) {}

          public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

          public void onActivityDestroyed(Activity activity) {}
        };
    app.registerActivityLifecycleCallbacks(observer);
    Activity settings = null;
    Activity soundcore = null;
    try {
      main(() -> context.startActivity(settingsIntent));
      check(
          await(() -> resumed.get() instanceof SettingsActivity),
          "Actions launcher opens settings");
      settings = resumed.get();
      main(() -> context.startActivity(soundcoreIntent));
      check(
          await(() -> resumed.get() instanceof Fixtures.Screen),
          "Normal launcher opens its own screen after settings");
      soundcore = resumed.get();
      check(settings.getTaskId() != soundcore.getTaskId(), "Launcher entries have separate tasks");
      Activity existingSettings = settings;
      Activity existingSoundcore = soundcore;
      for (int i = 0; i < 2; i++) {
        main(() -> context.startActivity(settingsIntent));
        check(
            await(() -> resumed.get() == existingSettings),
            "Actions launcher returns to existing settings");
        main(() -> context.startActivity(soundcoreIntent));
        check(
            await(() -> resumed.get() == existingSoundcore),
            "Normal launcher returns to existing Soundcore screen");
      }
    } finally {
      app.unregisterActivityLifecycleCallbacks(observer);
      Activity finalSettings = settings;
      Activity finalSoundcore = soundcore;
      main(
          () -> {
            if (finalSettings != null) finalSettings.finish();
            if (finalSoundcore != null) finalSoundcore.finish();
          });
    }
  }

  @SuppressWarnings("deprecation")
  private void testMappedLaunch() throws Exception {
    Context context = getTargetContext();
    String source = Fixtures.Screen.class.getName();
    String event = "com.colonelpanic.soundcoreactions.MAPPED_TEST";
    java.util.concurrent.atomic.AtomicInteger deliveries =
        new java.util.concurrent.atomic.AtomicInteger();
    BroadcastReceiver receiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context ignored, Intent intent) {
            if ("mapped".equals(intent.getStringExtra("message"))) deliveries.incrementAndGet();
          }
        };
    if (Build.VERSION.SDK_INT >= 33)
      context.registerReceiver(receiver, new IntentFilter(event), Context.RECEIVER_NOT_EXPORTED);
    else context.registerReceiver(receiver, new IntentFilter(event));
    Rules.Action previous = Rules.stored(context, "activity", source);
    try {
      Rules.put(
          context,
          "activity",
          source,
          new Rules.Action(
              "broadcast",
              "Mapped broadcast",
              context.getPackageName(),
              "intent:#Intent;action=" + event + ";S.message=mapped;end"));
      for (int i = 1; i <= 2; i++) {
        int expected = i;
        main(
            () ->
                context.startActivity(
                    new Intent(context, Fixtures.Screen.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        check(
            await(() -> deliveries.get() == expected),
            "Launching a mapped activity delivers its replacement action repeatedly");
        waitForIdleSync();
      }
      Intent destination = new Intent(context, Fixtures.Target.class).putExtra("message", "mapped");
      Rules.put(
          context,
          "activity",
          source,
          new Rules.Action(
              "intent", "Mapped screen", "", destination.toUri(Intent.URI_INTENT_SCHEME)));
      for (int i = 0; i < 2; i++) {
        ActivityMonitor monitor = addMonitor(Fixtures.Target.class.getName(), null, false);
        try {
          main(
              () ->
                  context.startActivity(
                      new Intent(context, Fixtures.Screen.class)
                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
          Activity target = waitForMonitorWithTimeout(monitor, 5000);
          check(target != null, "Mapped activity opens its destination screen repeatedly");
          try {
            check(
                "mapped".equals(target.getIntent().getStringExtra("message")),
                "Mapped screen receives intent extras");
          } finally {
            main(target::finish);
            waitForIdleSync();
          }
        } finally {
          removeMonitor(monitor);
        }
      }
    } finally {
      Rules.put(context, "activity", source, previous);
      context.unregisterReceiver(receiver);
    }
  }

  private void testAnkaDeduplication() {
    long[] now = {0};
    AnkaTrigger trigger = new AnkaTrigger(() -> now[0]);
    check(trigger.accept(true), "First earbud event runs");
    check(!trigger.accept(true), "Repeated earbud packet is debounced");
    check(!trigger.accept(false), "Vendor screen cannot launch the same action twice");
    now[0] = 600;
    check(trigger.accept(true), "A subsequent earbud press can run again");
    now[0] = 2100;
    check(trigger.accept(false), "Manual screen launch works after gesture window");
    check(trigger.accept(false), "Manual navigation is not debounced");
    check(!trigger.accept(true), "A screen followed by its earbud callback runs only once");
  }

  @SuppressWarnings("deprecation")
  private void testEarbudEvents() throws Exception {
    Context context = getTargetContext();
    Application app = (Application) context.getApplicationContext();
    Rules.Action previous = Rules.stored(context, "activity", Rules.ANKA);
    String event = "com.colonelpanic.soundcoreactions.EARBUD_TEST";
    java.util.concurrent.atomic.AtomicInteger deliveries =
        new java.util.concurrent.atomic.AtomicInteger();
    BroadcastReceiver receiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context ignored, Intent intent) {
            deliveries.incrementAndGet();
          }
        };
    if (Build.VERSION.SDK_INT >= 33)
      context.registerReceiver(receiver, new IntentFilter(event), Context.RECEIVER_NOT_EXPORTED);
    else context.registerReceiver(receiver, new IntentFilter(event));
    ClassLoader loader =
        new ClassLoader(getClass().getClassLoader()) {
          @Override
          public Class<?> loadClass(String name) throws ClassNotFoundException {
            if (name.equals("com.oceanwing.devicecmd.manager.cmmbt2.Cmm2BtDeviceManager"))
              return Fixtures.EarbudManager.class;
            if (name.equals("com.oceanwing.devicecmd.manager.BaseBtEventCallback"))
              return Fixtures.BaseCallback.class;
            if (name.equals("com.oceanwing.devicecmd.manager.cmmbt2.Cmm2BtEventCallback"))
              return Fixtures.EarbudCallback.class;
            return super.loadClass(name);
          }
        };
    long[] now = {0};
    EarbudActions[] listener = new EarbudActions[1];
    Fixtures.EarbudManager manager = Fixtures.EarbudManager.y;
    try {
      Rules.put(
          context,
          "activity",
          Rules.ANKA,
          new Rules.Action(
              "broadcast",
              "Earbud test",
              context.getPackageName(),
              "intent:#Intent;action=" + event + ";end"));
      main(() -> listener[0] = new EarbudActions(app, loader, new AnkaTrigger(() -> now[0])));
      check(
          await(() -> manager.listeners.size() == 1),
          "Earbud listener attaches before a vendor screen opens");
      main(
          () -> {
            manager.emit(false, true);
            manager.emit(true, false);
          });
      waitForIdleSync();
      check(deliveries.get() == 0, "Failed and stop-recording events do not launch actions");
      main(
          () -> {
            manager.emit(true, true);
            manager.emit(true, true);
          });
      check(
          await(() -> deliveries.get() == 1),
          "Anka callback directly dispatches exactly one replacement");
      Rules.setEnabled(context, false);
      now[0] = 2000;
      main(() -> manager.emit(true, true));
      waitForIdleSync();
      check(deliveries.get() == 1, "Disabling mappings disables earbud dispatch");
      Rules.setEnabled(context, true);
      manager.device.product = "unsupported";
      main(() -> manager.emit(true, true));
      waitForIdleSync();
      check(deliveries.get() == 1, "Unsupported earbuds keep original behavior");
      manager.device.product = "D1203";
      main(manager.listeners::clear);
      check(
          await(() -> manager.listeners.size() == 1),
          "Listener recovers after vendor reconnect clears callbacks");
      main(() -> manager.emit(true, true));
      check(await(() -> deliveries.get() == 2), "Earbud dispatch works after reconnect");
      Rules.put(context, "activity", Rules.ANKA, null);
      now[0] = 4000;
      main(() -> manager.emit(true, true));
      waitForIdleSync();
      check(deliveries.get() == 2, "Removing the mapping restores original gesture behavior");
      Intent destination = new Intent(context, Fixtures.Target.class).putExtra("message", "earbud");
      Rules.put(
          context,
          "activity",
          Rules.ANKA,
          new Rules.Action(
              "intent", "Earbud screen", "", destination.toUri(Intent.URI_INTENT_SCHEME)));
      Activity screen =
          startActivitySync(
              new Intent(context, Fixtures.Screen.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      ActivityMonitor monitor = addMonitor(Fixtures.Target.class.getName(), null, false);
      try {
        now[0] = 6000;
        main(() -> manager.emit(true, true));
        Activity target = waitForMonitorWithTimeout(monitor, 5000);
        check(target != null, "Earbud callback opens a replacement app while Soundcore is visible");
        try {
          check(
              "earbud".equals(target.getIntent().getStringExtra("message")),
              "Direct earbud dispatch preserves intent extras");
        } finally {
          main(target::finish);
          check(await(target::isDestroyed), "Destination screen finishes before background check");
        }
      } finally {
        removeMonitor(monitor);
        main(screen::finish);
        check(await(screen::isDestroyed), "Source screen finishes before background check");
      }
      monitor = addMonitor(Fixtures.Target.class.getName(), null, false);
      try {
        now[0] = 8000;
        main(() -> manager.emit(true, true));
        check(
            waitForMonitorWithTimeout(monitor, 1000) == null,
            "Background app launch waits for explicit overlay permission");
      } finally {
        removeMonitor(monitor);
      }
    } finally {
      main(
          () -> {
            if (listener[0] != null) listener[0].close();
          });
      Rules.put(context, "activity", Rules.ANKA, previous);
      Rules.setEnabled(context, true);
      manager.device.product = "D1203";
      context.unregisterReceiver(receiver);
    }
    check(manager.listeners.isEmpty(), "Earbud listener detaches cleanly");
  }

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  @SuppressWarnings("deprecation")
  private android.app.ActivityManager.RunningServiceInfo earbudService() {
    for (android.app.ActivityManager.RunningServiceInfo service :
        getTargetContext()
            .getSystemService(android.app.ActivityManager.class)
            .getRunningServices(100))
      if (EarbudService.class.getName().equals(service.service.getClassName())) return service;
    return null;
  }

  @SuppressWarnings("deprecation")
  private void testEarbudTranslationEvents() throws Exception {
    Context context = getTargetContext();
    Application app = (Application) context.getApplicationContext();
    Rules.Action previousRealtime = Rules.stored(context, "activity", Rules.REALTIME);
    Rules.Action previousFace = Rules.stored(context, "activity", Rules.FACE);
    Rules.Action previousAnka = Rules.stored(context, "activity", Rules.ANKA);
    String event = "com.colonelpanic.soundcoreactions.TRANSLATION_TEST";
    java.util.concurrent.atomic.AtomicInteger deliveries =
        new java.util.concurrent.atomic.AtomicInteger();
    BroadcastReceiver receiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context ignored, Intent intent) {
            deliveries.incrementAndGet();
          }
        };
    if (Build.VERSION.SDK_INT >= 33)
      context.registerReceiver(receiver, new IntentFilter(event), Context.RECEIVER_NOT_EXPORTED);
    else context.registerReceiver(receiver, new IntentFilter(event));
    ClassLoader loader =
        new ClassLoader(getClass().getClassLoader()) {
          @Override
          public Class<?> loadClass(String name) throws ClassNotFoundException {
            if (name.equals("com.oceanwing.devicecmd.manager.cmmbt2.Cmm2BtDeviceManager"))
              return Fixtures.EarbudManager.class;
            if (name.equals("com.oceanwing.devicecmd.manager.BaseBtEventCallback"))
              return Fixtures.BaseCallback.class;
            if (name.equals("com.oceanwing.devicecmd.manager.cmmbt2.Cmm2BtEventCallback"))
              return Fixtures.EarbudCallback.class;
            return super.loadClass(name);
          }
        };
    long[] now = {0};
    EarbudActions[] listener = new EarbudActions[1];
    Fixtures.EarbudManager manager = Fixtures.EarbudManager.y;
    Rules.Action broadcast =
        new Rules.Action(
            "broadcast",
            "Translation test",
            context.getPackageName(),
            "intent:#Intent;action=" + event + ";end");
    try {
      Rules.put(context, "activity", Rules.ANKA, null);
      Rules.put(context, "activity", Rules.FACE, null);
      Rules.put(context, "activity", Rules.REALTIME, broadcast);
      main(
          () ->
              listener[0] =
                  new EarbudActions(
                      app, loader, new AnkaTrigger(() -> now[0]), new AnkaTrigger(() -> now[0])));
      check(
          await(() -> manager.listeners.size() == 1),
          "Earbud listener attaches for translation events");
      main(
          () -> {
            manager.emitTranslation(false, true, 2);
            manager.emitTranslation(true, false, 2);
          });
      waitForIdleSync();
      check(deliveries.get() == 0, "Failed and stop-recording translation events do not dispatch");
      main(
          () -> {
            manager.emitTranslation(true, true, 2);
            manager.emitTranslation(true, true, 2);
          });
      check(
          await(() -> deliveries.get() == 1),
          "Translation callback dispatches the real-time mapping exactly once");
      Rules.put(context, "activity", Rules.REALTIME, null);
      Rules.put(context, "activity", Rules.FACE, broadcast);
      now[0] = 2000;
      main(() -> manager.emitTranslation(true, true, 2));
      check(await(() -> deliveries.get() == 2), "Face-to-face mapping runs when only it is mapped");
      Rules.put(context, "activity", Rules.FACE, null);
      now[0] = 4000;
      main(() -> manager.emitTranslation(true, true, 2));
      waitForIdleSync();
      check(
          deliveries.get() == 2,
          "Removing translation mappings restores original gesture behavior");
      Rules.put(context, "activity", Rules.ANKA, broadcast);
      Rules.put(context, "activity", Rules.REALTIME, broadcast);
      now[0] = 6000;
      main(
          () -> {
            manager.emitTranslation(true, true, 2);
            manager.emit(true, true);
          });
      check(
          await(() -> deliveries.get() == 4),
          "Anka and translation gestures are debounced independently");
    } finally {
      main(
          () -> {
            if (listener[0] != null) listener[0].close();
          });
      Rules.put(context, "activity", Rules.REALTIME, previousRealtime);
      Rules.put(context, "activity", Rules.FACE, previousFace);
      Rules.put(context, "activity", Rules.ANKA, previousAnka);
      context.unregisterReceiver(receiver);
    }
    check(manager.listeners.isEmpty(), "Translation listener detaches cleanly");
  }

  private void testEarbudService() throws Exception {
    Context context = getTargetContext();
    Rules.Action previous = Rules.stored(context, "activity", Rules.ANKA);
    Rules.Action previousRealtime = Rules.stored(context, "activity", Rules.REALTIME);
    Rules.Action previousFace = Rules.stored(context, "activity", Rules.FACE);
    Rules.put(context, "activity", Rules.REALTIME, null);
    Rules.put(context, "activity", Rules.FACE, null);
    if (Build.VERSION.SDK_INT >= 31)
      getUiAutomation()
          .grantRuntimePermission(
              context.getPackageName(), android.Manifest.permission.BLUETOOTH_CONNECT);
    Activity screen =
        startActivitySync(
            new Intent(context, Fixtures.Screen.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      Rules.put(context, "activity", Rules.ANKA, Rules.Action.paseo());
      Rules.setEnabled(context, true);
      main(() -> EarbudService.update(context));
      check(
          await(() -> earbudService() != null && earbudService().foreground),
          "Earbud listener runs as a connected-device foreground service");
      main(screen::finish);
      check(await(screen::isDestroyed), "Earbud service source screen closes");
      check(
          earbudService() != null && earbudService().foreground,
          "Earbud listener remains in foreground service after leaving app");
      Rules.setEnabled(context, false);
      main(() -> EarbudService.update(context));
      check(await(() -> earbudService() == null), "Disabling mappings stops the earbud service");
      screen =
          startActivitySync(
              new Intent(context, Fixtures.Screen.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      Rules.setEnabled(context, true);
      main(() -> EarbudService.update(context));
      check(
          await(() -> earbudService() != null && earbudService().foreground),
          "Enabling mappings can restart the listener");
      Rules.put(context, "activity", Rules.ANKA, null);
      main(() -> EarbudService.update(context));
      check(await(() -> earbudService() == null), "Removing Anka stops the earbud service");
      Rules.put(context, "activity", Rules.REALTIME, Rules.Action.paseo());
      main(() -> EarbudService.update(context));
      check(
          await(() -> earbudService() != null && earbudService().foreground),
          "A translation mapping alone keeps the earbud listener running");
      Rules.put(context, "activity", Rules.REALTIME, null);
      main(() -> EarbudService.update(context));
      check(
          await(() -> earbudService() == null),
          "Removing the last earbud mapping stops the service");
    } finally {
      context.stopService(new Intent(context, EarbudService.class));
      main(screen::finish);
      Rules.put(context, "activity", Rules.ANKA, previous);
      Rules.put(context, "activity", Rules.REALTIME, previousRealtime);
      Rules.put(context, "activity", Rules.FACE, previousFace);
      Rules.setEnabled(context, true);
    }
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
      testSpokenMessages();
      testLabels();
      Rules.setEnabled(context, true);
      testLiveLabels();
      testLauncherTasks();
      testMappedLaunch();
      testAnkaDeduplication();
      testEarbudEvents();
      testEarbudTranslationEvents();
      testEarbudService();
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
