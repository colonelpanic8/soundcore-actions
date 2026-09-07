package com.colonelpanic.soundcorepatch;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

public final class Rules {
  static final String REALTIME = "com.soundcore.translation.translating.realtime.RealtimeActivity";
  static final String FACE = "com.soundcore.translation.translating.TranslatingFaceToFaceActivity";
  static final String ANKA = "com.oceanwing.soundcore.activity.a3874.AIChatActivity";

  private Rules() {}

  private static android.util.AtomicFile configFile(Context context) {
    return new android.util.AtomicFile(
        new java.io.File(context.getFilesDir(), "soundcore-actions.json"));
  }

  private static synchronized JSONObject config(Context context) {
    android.util.AtomicFile file = configFile(context);
    try {
      return new JSONObject(new String(file.readFully(), java.nio.charset.StandardCharsets.UTF_8));
    } catch (java.io.FileNotFoundException missing) {
      JSONObject root = new JSONObject();
      JSONObject rules = new JSONObject();
      try {
        root.put("enabled", true).put("rules", rules);
        SharedPreferences previous =
            context.getSharedPreferences("soundcore_actions", Context.MODE_PRIVATE);
        if (previous.contains("initialized")) {
          root.put("enabled", previous.getBoolean("enabled", true));
          for (Map.Entry<String, ?> entry : previous.getAll().entrySet()) {
            if (entry.getKey().startsWith("rule:"))
              rules.put(entry.getKey().substring(5), new JSONObject((String) entry.getValue()));
          }
        } else {
          for (String name : new String[] {REALTIME, FACE, ANKA})
            rules.put("activity:" + name, new JSONObject(Action.paseo().encode()));
        }
        save(context, root);
        return root;
      } catch (JSONException error) {
        throw new IllegalStateException("Could not initialize mappings", error);
      }
    } catch (java.io.IOException | JSONException error) {
      throw new IllegalStateException("Could not read mappings", error);
    }
  }

  private static void save(Context context, JSONObject config) {
    android.util.AtomicFile file = configFile(context);
    java.io.FileOutputStream stream = null;
    try {
      stream = file.startWrite();
      stream.write(config.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      file.finishWrite(stream);
    } catch (java.io.IOException error) {
      if (stream != null) file.failWrite(stream);
      throw new IllegalStateException("Could not save mappings", error);
    }
  }

  static boolean enabled(Context context) {
    return config(context).optBoolean("enabled", true);
  }

  static synchronized void setEnabled(Context context, boolean enabled) {
    JSONObject config = config(context);
    try {
      config.put("enabled", enabled);
    } catch (JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
    save(context, config);
  }

  static Action stored(Context context, String kind, String name) {
    JSONObject rules = config(context).optJSONObject("rules");
    JSONObject value = rules == null ? null : rules.optJSONObject(kind + ":" + name);
    return Action.decode(value == null ? null : value.toString());
  }

  static Action get(Context context, String kind, String name) {
    JSONObject root = config(context);
    JSONObject rules = root.optJSONObject("rules");
    if (!root.optBoolean("enabled", true) || rules == null) return null;
    JSONObject action = rules.optJSONObject(kind + ":" + name);
    return Action.decode(action == null ? null : action.toString());
  }

  static synchronized void put(Context context, String kind, String name, Action action) {
    JSONObject config = config(context);
    try {
      JSONObject rules = config.getJSONObject("rules");
      if (action == null) rules.remove(kind + ":" + name);
      else rules.put(kind + ":" + name, new JSONObject(action.encode()));
    } catch (JSONException error) {
      throw new IllegalStateException(error);
    }
    save(context, config);
  }

  private static java.io.File eventDirectory(Context context) {
    java.io.File dir = new java.io.File(context.getFilesDir(), "soundcore-action-events");
    if (!dir.isDirectory() && !dir.mkdirs())
      throw new IllegalStateException("Could not create event history");
    return dir;
  }

  private static JSONObject readEvents(android.util.AtomicFile file) {
    try {
      return new JSONObject(new String(file.readFully(), java.nio.charset.StandardCharsets.UTF_8));
    } catch (java.io.IOException | JSONException absent) {
      return new JSONObject();
    }
  }

  static synchronized void record(Context context, String kind, String name) {
    String process = android.app.Application.getProcessName();
    android.util.AtomicFile file =
        new android.util.AtomicFile(
            new java.io.File(
                eventDirectory(context), Integer.toHexString(process.hashCode()) + ".json"));
    JSONObject events = readEvents(file);
    java.io.FileOutputStream stream = null;
    try {
      events.put(kind + ":" + name, System.currentTimeMillis());
      List<String> keys = new ArrayList<>();
      events.keys().forEachRemaining(keys::add);
      keys.sort((a, b) -> Long.compare(events.optLong(a), events.optLong(b)));
      for (int i = 0; i < keys.size() - 60; i++) events.remove(keys.get(i));
      stream = file.startWrite();
      stream.write(events.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      file.finishWrite(stream);
    } catch (java.io.IOException | JSONException error) {
      if (stream != null) file.failWrite(stream);
      android.util.Log.w("SoundcoreActions", "Could not save event history", error);
    }
  }

  static Map<String, Long> events(Context context) {
    Map<String, Long> all = new java.util.HashMap<>();
    java.io.File[] files = eventDirectory(context).listFiles((dir, name) -> name.endsWith(".json"));
    if (files != null)
      for (java.io.File file : files) {
        JSONObject events = readEvents(new android.util.AtomicFile(file));
        events
            .keys()
            .forEachRemaining(
                key -> all.put(key, Math.max(events.optLong(key), all.getOrDefault(key, 0L))));
      }
    return all;
  }

  static List<String> keys(Context context, String prefix) {
    List<String> keys = new ArrayList<>();
    if ("seen:".equals(prefix)) keys.addAll(events(context).keySet());
    else {
      JSONObject rules = config(context).optJSONObject("rules");
      if (rules != null) rules.keys().forEachRemaining(keys::add);
    }
    Collections.sort(keys);
    return keys;
  }

  static String title(String name) {
    if (REALTIME.equals(name)) return "Real-time translation";
    if (FACE.equals(name)) return "Face-to-face translation";
    if (ANKA.equals(name)) return "Anka assistant";
    if (name.endsWith(".TwsCustomUIActivity")) return "Earbud controls";
    if (name.endsWith(".SoundCoreMainActivity")) return "Soundcore home";
    if (name.endsWith(".WelcomeActivity")) return "Soundcore startup";
    String simple =
        name.substring(name.lastIndexOf('.') + 1).replaceAll("(Activity|Service|Receiver)$", "");
    return simple.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ').trim();
  }

  static final class Action {
    final String type;
    final String label;
    final String packageName;
    final String value;

    Action(String type, String label, String packageName, String value) {
      this.type = type;
      this.label = label;
      this.packageName = packageName;
      this.value = value;
    }

    static Action paseo() {
      return new Action("link", "Paseo Live Voice", "sh.paseo.assembly", "paseo://live-voice");
    }

    String encode() {
      try {
        return new JSONObject()
            .put("type", type)
            .put("label", label)
            .put("package", packageName)
            .put("value", value)
            .toString();
      } catch (JSONException impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    static Action decode(String encoded) {
      if (encoded == null) return null;
      try {
        JSONObject obj = new JSONObject(encoded);
        return new Action(
            obj.getString("type"),
            obj.getString("label"),
            obj.optString("package"),
            obj.optString("value"));
      } catch (JSONException invalid) {
        return null;
      }
    }
  }
}
