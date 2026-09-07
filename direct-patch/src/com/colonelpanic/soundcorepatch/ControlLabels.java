package com.colonelpanic.soundcorepatch;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class ControlLabels implements Application.ActivityLifecycleCallbacks {
  private static final Set<Application> installed = Collections.newSetFromMap(new WeakHashMap<>());
  private final Map<View, ViewTreeObserver.OnGlobalLayoutListener> listeners = new WeakHashMap<>();
  private final Map<TextView, Label> originals = new WeakHashMap<>();

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

  @Override
  public void onActivityResumed(Activity activity) {
    if (activity.getClass().getName().startsWith("com.colonelpanic.soundcorepatch.")) return;
    View root = activity.getWindow().getDecorView();
    detach(root);
    Map<String, String> replacements = new HashMap<>();
    Rules.Action anka = Rules.get(activity, "activity", Rules.ANKA);
    if (anka != null) {
      for (String id : new String[] {"key_anka", "community_anka", "anka_main_title", "anka_title"})
        add(activity, replacements, id, anka.label);
    }
    Rules.Action real = Rules.get(activity, "activity", Rules.REALTIME);
    Rules.Action face = Rules.get(activity, "activity", Rules.FACE);
    if (real != null || face != null) {
      String label =
          real != null && face != null && real.label.equals(face.label)
              ? real.label
              : "Custom translation action";
      add(activity, replacements, "fanyi_ai_translation", label);
    }
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

  private static void add(
      Context context, Map<String, String> labels, String resource, String value) {
    int id = context.getResources().getIdentifier(resource, "string", context.getPackageName());
    if (id != 0) labels.put(context.getString(id), value);
  }

  private void update(View view, Map<String, String> replacements) {
    if (view instanceof TextView && !(view instanceof android.widget.EditText)) {
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
    detach(activity.getWindow().getDecorView());
  }

  @Override
  public void onActivityCreated(Activity activity, Bundle state) {}

  @Override
  public void onActivityStarted(Activity activity) {}

  @Override
  public void onActivityPaused(Activity activity) {}

  @Override
  public void onActivityStopped(Activity activity) {}

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
}
