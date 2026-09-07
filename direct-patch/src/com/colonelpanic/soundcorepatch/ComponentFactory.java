package com.colonelpanic.soundcorepatch;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.util.Log;

public final class ComponentFactory extends AppComponentFactory {
  private static Application application;

  private AppComponentFactory original(ClassLoader loader) {
    try {
      return (AppComponentFactory)
          loader
              .loadClass("androidx.core.app.CoreComponentFactory")
              .getDeclaredConstructor()
              .newInstance();
    } catch (ReflectiveOperationException | LinkageError ignored) {
      return new AppComponentFactory();
    }
  }

  @Override
  public Application instantiateApplication(ClassLoader loader, String name)
      throws InstantiationException, IllegalAccessException, ClassNotFoundException {
    application = original(loader).instantiateApplication(loader, name);
    return application;
  }

  private boolean intercept(String kind, String name) {
    if (name.startsWith("com.colonelpanic.soundcorepatch.")
        || application == null
        || application.getBaseContext() == null) return false;
    try {
      Rules.record(application, kind, name);
      boolean mapped = Rules.get(application, kind, name) != null;
      if (mapped) Log.i("SoundcoreActions", "Replacing " + kind + ": " + name);
      return mapped;
    } catch (RuntimeException error) {
      Log.e("SoundcoreActions", "Could not read action mappings; using original behavior", error);
      return false;
    }
  }

  @Override
  public Activity instantiateActivity(ClassLoader loader, String name, Intent intent)
      throws InstantiationException, IllegalAccessException, ClassNotFoundException {
    if (application != null && application.getBaseContext() != null)
      ControlLabels.install(application);
    if (intercept("activity", name)) {
      LaunchActivity replacement = new LaunchActivity();
      replacement.source = name;
      return replacement;
    }
    return original(loader).instantiateActivity(loader, name, intent);
  }

  @Override
  public Service instantiateService(ClassLoader loader, String name, Intent intent)
      throws InstantiationException, IllegalAccessException, ClassNotFoundException {
    if (intercept("service", name)) {
      ActionService replacement = new ActionService();
      replacement.source = name;
      return replacement;
    }
    return original(loader).instantiateService(loader, name, intent);
  }

  @Override
  public BroadcastReceiver instantiateReceiver(ClassLoader loader, String name, Intent intent)
      throws InstantiationException, IllegalAccessException, ClassNotFoundException {
    if (intercept("receiver", name)) {
      ActionReceiver replacement = new ActionReceiver();
      replacement.source = name;
      return replacement;
    }
    return original(loader).instantiateReceiver(loader, name, intent);
  }

  @Override
  public ContentProvider instantiateProvider(ClassLoader loader, String name)
      throws InstantiationException, IllegalAccessException, ClassNotFoundException {
    return original(loader).instantiateProvider(loader, name);
  }
}
