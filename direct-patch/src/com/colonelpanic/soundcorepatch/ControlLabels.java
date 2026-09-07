package com.colonelpanic.soundcorepatch;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Shows the configured action name in place of the vendor's Anka and AI translation labels.
 *
 * <p>The vendor renders those labels in the resumed activity and in separate popup windows such as
 * the gesture function picker, so every window owned by the resumed activity is watched.
 */
final class ControlLabels implements Application.ActivityLifecycleCallbacks {
  private static final long SCAN_INTERVAL_MS = 200;
  private static final Set<Application> installed = Collections.newSetFromMap(new WeakHashMap<>());
  private final Map<View, ViewTreeObserver.OnGlobalLayoutListener> listeners = new WeakHashMap<>();
  private final Map<TextView, Label> originals = new WeakHashMap<>();
  private final Map<Activity, Runnable> scans = new WeakHashMap<>();
  private final Handler handler = new Handler(Looper.getMainLooper());

  static synchronized void install(Application shell) {
    Context context = shell.getApplicationContext();
    Application app = context instanceof Application ? (Application) context : shell;
    if (installed.add(app)) app.registerActivityLifecycleCallbacks(new ControlLabels());
  }

  private static final class Label {
    final String original;
    final String rendered;

    Label(String original, String rendered) {
      this.original = original;
      this.rendered = rendered;
    }
  }

  static Map<String, String> replacements(Context context) {
    Map<String, String> replacements = new HashMap<>();
    Rules.Action anka = Rules.get(context, "activity", Rules.ANKA);
    if (anka != null) {
      for (String id :
          new String[] {
            "key_anka",
            "community_anka",
            "anka_main_title",
            "anka_title",
            "translation_anker_voice_assistant"
          }) add(context, replacements, id, anka.label);
    }
    Rules.Action real = Rules.get(context, "activity", Rules.REALTIME);
    Rules.Action face = Rules.get(context, "activity", Rules.FACE);
    if (real != null || face != null) {
      String label =
          real != null && face != null && real.label.equals(face.label)
              ? real.label
              : "Custom translation action";
      add(context, replacements, "fanyi_ai_translation", label);
    }
    return replacements;
  }

  @Override
  public void onActivityResumed(Activity activity) {
    if (activity.getClass().getName().startsWith("com.colonelpanic.soundcorepatch.")) return;
    stop(activity);
    Map<String, String> replacements = replacements(activity);
    WeakReference<Activity> reference = new WeakReference<>(activity);
    Runnable scan =
        new Runnable() {
          @Override
          public void run() {
            Activity current = reference.get();
            if (current == null || current.isDestroyed() || current.isFinishing()) return;
            for (View root : windows(current)) observe(root, replacements);
            handler.postDelayed(this, SCAN_INTERVAL_MS);
          }
        };
    scans.put(activity, scan);
    scan.run();
  }

  private static ArrayList<View> windows(Activity activity) {
    ArrayList<View> roots = new ArrayList<>();
    roots.add(activity.getWindow().getDecorView());
    if (Build.VERSION.SDK_INT >= 29) {
      for (View root : WindowInspector.getGlobalWindowViews())
        if (belongsTo(root, activity)) roots.add(root);
    }
    return roots;
  }

  private static boolean belongsTo(View root, Activity activity) {
    View decor = activity.getWindow().getDecorView();
    if (root == decor) return true;
    if (decor.getApplicationWindowToken() != null
        && decor.getApplicationWindowToken().equals(root.getApplicationWindowToken())) return true;
    Context context = root.getContext();
    while (context != activity && context instanceof ContextWrapper) {
      Context base = ((ContextWrapper) context).getBaseContext();
      if (base == context) break;
      context = base;
    }
    return context == activity;
  }

  private void observe(View root, Map<String, String> replacements) {
    if (listeners.containsKey(root)) return;
    WeakReference<View> reference = new WeakReference<>(root);
    ViewTreeObserver.OnGlobalLayoutListener listener =
        () -> {
          View view = reference.get();
          if (view != null) update(view, replacements);
        };
    listeners.put(root, listener);
    root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
    update(root, replacements);
  }

  private void stop(Activity activity) {
    Runnable scan = scans.remove(activity);
    if (scan != null) handler.removeCallbacks(scan);
    for (View root : new ArrayList<>(listeners.keySet()))
      if (root != null && belongsTo(root, activity)) detach(root);
  }

  private static void add(
      Context context, Map<String, String> labels, String resource, String value) {
    int id = context.getResources().getIdentifier(resource, "string", context.getPackageName());
    if (id != 0) labels.put(context.getString(id), value);
  }

  void update(View view, Map<String, String> replacements) {
    if (view instanceof TextView && !(view instanceof EditText)) {
      TextView text = (TextView) view;
      String current = text.getText().toString();
      Label previous = originals.get(text);
      String original =
          previous != null && previous.rendered.equals(current) ? previous.original : current;
      String rendered = replacements.getOrDefault(original, original);
      if (!current.equals(rendered)) text.setText(rendered);
      if (!original.equals(rendered)) originals.put(text, new Label(original, rendered));
      else originals.remove(text);
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) update(group.getChildAt(i), replacements);
    }
  }

  private void detach(View root) {
    ViewTreeObserver.OnGlobalLayoutListener old = listeners.remove(root);
    if (old != null && root.getViewTreeObserver().isAlive())
      root.getViewTreeObserver().removeOnGlobalLayoutListener(old);
  }

  @Override
  public void onActivityDestroyed(Activity activity) {
    stop(activity);
  }

  @Override
  public void onActivityCreated(Activity activity, Bundle state) {}

  @Override
  public void onActivityStarted(Activity activity) {}

  @Override
  public void onActivityPaused(Activity activity) {
    stop(activity);
  }

  @Override
  public void onActivityStopped(Activity activity) {}

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
}
