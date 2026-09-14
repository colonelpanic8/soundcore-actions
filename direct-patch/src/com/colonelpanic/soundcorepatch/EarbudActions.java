package com.colonelpanic.soundcorepatch;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import java.lang.reflect.Proxy;

final class EarbudActions implements Application.ActivityLifecycleCallbacks {
  private static final String TAG = "SoundcoreActions";
  private static final AnkaTrigger trigger = new AnkaTrigger(SystemClock::elapsedRealtime);
  private static EarbudActions installed;
  private final Application context;
  private final ClassLoader loader;
  private final AnkaTrigger dispatch;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private Object manager;
  private Object listener;
  private Class<?> callbackType;
  private int startedActivities;
  private String lastError;

  EarbudActions(Application app, ClassLoader loader, AnkaTrigger dispatch) {
    context = app;
    this.loader = loader;
    this.dispatch = dispatch;
    app.registerActivityLifecycleCallbacks(this);
    handler.post(this::connect);
  }

  static synchronized void install(Application shell) {
    if (installed != null
        || !"com.oceanwing.soundcore".equals(shell.getPackageName())
        || !shell.getPackageName().equals(Application.getProcessName())) return;
    Context app = shell.getApplicationContext();
    Application application = app instanceof Application ? (Application) app : shell;
    installed = new EarbudActions(application, application.getClassLoader(), trigger);
  }

  static boolean dispatchActivity(String source) {
    return !Rules.ANKA.equals(source) || trigger.accept(false);
  }

  private void connect() {
    try {
      Class<?> type =
          loader.loadClass("com.oceanwing.devicecmd.manager.cmmbt2.Cmm2BtDeviceManager");
      Object current = type.getField("y").get(null);
      if (current != null) {
        if (current != manager) {
          detach();
          callbackType = loader.loadClass("com.oceanwing.devicecmd.manager.BaseBtEventCallback");
          Class<?> events =
              loader.loadClass("com.oceanwing.devicecmd.manager.cmmbt2.Cmm2BtEventCallback");
          listener =
              Proxy.newProxyInstance(
                  loader,
                  new Class<?>[] {callbackType, events},
                  (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                      if (method.getName().equals("equals")) return proxy == args[0];
                      if (method.getName().equals("hashCode"))
                        return System.identityHashCode(proxy);
                      return "Soundcore Actions earbud listener";
                    }
                    if (method.getName().equals("getAIChatStartCmdCallback")) {
                      boolean success = Boolean.TRUE.equals(args[0]);
                      boolean start = Boolean.TRUE.equals(args[1]);
                      Log.i(TAG, "Earbud Anka event: success=" + success + ", start=" + start);
                      handler.post(() -> anka(current, success, start));
                    } else if (method.getName().equals("getAudioRecordCmdCallback")) {
                      Log.i(
                          TAG,
                          "Earbud translation event: success="
                              + args[0]
                              + ", start="
                              + args[1]
                              + ", action="
                              + args[2]);
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                  });
          manager = current;
          Log.i(TAG, "Listening for earbud action events");
        }
        // Q is idempotent; the vendor clears its listener list during reconnection.
        type.getMethod("Q", callbackType).invoke(manager, listener);
      }
      lastError = null;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
      String message = error.toString();
      if (!message.equals(lastError)) Log.w(TAG, "Earbud listener unavailable: " + message);
      lastError = message;
    } finally {
      handler.postDelayed(this::connect, 3000);
    }
  }

  private void anka(Object sourceManager, boolean success, boolean start) {
    if (!success || !start || sourceManager != manager) return;
    try {
      Object device = manager.getClass().getMethod("l").invoke(manager);
      if (device == null
          || !"D1203".equals(device.getClass().getMethod("getProductCode").invoke(device))) return;
      Rules.Action action = Rules.get(context, "activity", Rules.ANKA);
      if (action == null) return;
      if (!"wind".equals(action.type)
          && !"broadcast".equals(action.type)
          && startedActivities == 0
          && !Settings.canDrawOverlays(context)) {
        Log.w(
            TAG,
            "Anka action needs background launch permission; enable it in soundcore (actions)");
        return;
      }
      if (dispatch.accept(true)) {
        Log.i(TAG, "Dispatching Anka mapping from earbud event");
        ActionRunner.run(context, action);
      }
    } catch (ReflectiveOperationException | RuntimeException error) {
      Log.e(TAG, "Could not handle earbud Anka event", error);
    }
  }

  private void detach() {
    if (manager == null) return;
    try {
      manager.getClass().getMethod("T", callbackType).invoke(manager, listener);
    } catch (ReflectiveOperationException | RuntimeException error) {
      Log.w(TAG, "Could not remove old earbud listener", error);
    }
  }

  void close() {
    handler.removeCallbacksAndMessages(null);
    detach();
    manager = null;
    context.unregisterActivityLifecycleCallbacks(this);
  }

  public void onActivityStarted(Activity activity) {
    startedActivities++;
    EarbudService.sync(context);
  }

  public void onActivityStopped(Activity activity) {
    startedActivities = Math.max(0, startedActivities - 1);
  }

  public void onActivityCreated(Activity activity, Bundle state) {}

  public void onActivityResumed(Activity activity) {}

  public void onActivityPaused(Activity activity) {}

  public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

  public void onActivityDestroyed(Activity activity) {}
}
