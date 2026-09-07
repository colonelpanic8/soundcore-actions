package com.colonelpanic.soundcorepatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@SuppressWarnings("deprecation")
public final class SettingsActivity extends Activity {
  private int ink;
  private int muted;
  private int surface;
  private int background;
  private final int accent = Color.rgb(26, 102, 220);
  private LinearLayout page;
  private boolean home = true;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    boolean dark =
        (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    ink = Color.parseColor(dark ? "#F4F7FC" : "#172339");
    muted = Color.parseColor(dark ? "#AAB8CE" : "#59697F");
    surface = Color.parseColor(dark ? "#1B293E" : "#FFFFFF");
    background = Color.parseColor(dark ? "#101B2C" : "#F1F5FB");
    showHome();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private LinearLayout column() {
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    return layout;
  }

  private void startPage(String title, String subtitle, boolean isHome) {
    home = isHome;
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(background);
    scroll.setFitsSystemWindows(true);
    page = column();
    page.setPadding(dp(24), dp(22), dp(24), dp(36));
    android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
    holder.addView(
        page,
        new android.widget.FrameLayout.LayoutParams(
            Math.min(dp(720), getResources().getDisplayMetrics().widthPixels),
            -2,
            android.view.Gravity.CENTER_HORIZONTAL));
    scroll.addView(holder, new ScrollView.LayoutParams(-1, -2));
    scroll.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          view.setPadding(
              insets.getSystemWindowInsetLeft(),
              insets.getSystemWindowInsetTop(),
              insets.getSystemWindowInsetRight(),
              insets.getSystemWindowInsetBottom());
          return insets;
        });
    setContentView(scroll);
    if (!isHome) button(page, "‹  Mappings", this::showHome);
    text(page, title, 30, ink, true);
    text(page, subtitle, 16, muted, false);
    gap(page, 16);
  }

  private TextView text(LinearLayout parent, String content, int size, int color, boolean bold) {
    TextView view = new TextView(this);
    view.setText(content);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setPadding(0, dp(5), 0, dp(5));
    if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
    return view;
  }

  private void gap(LinearLayout parent, int height) {
    View view = new View(this);
    parent.addView(view, new LinearLayout.LayoutParams(1, dp(height)));
  }

  private LinearLayout card(LinearLayout parent) {
    LinearLayout card = column();
    card.setPadding(dp(20), dp(16), dp(20), dp(16));
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(surface);
    shape.setCornerRadius(dp(18));
    card.setBackground(shape);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(14);
    parent.addView(card, params);
    return card;
  }

  private Button button(LinearLayout parent, String label, Runnable action) {
    Button button = new Button(this);
    button.setText(label);
    button.setAllCaps(false);
    button.setTextColor(accent);
    button.setMinHeight(dp(48));
    button.setOnClickListener(view -> action.run());
    parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
    return button;
  }

  private void showHome() {
    startPage("Soundcore Actions", "Choose what Soundcore does when an event arrives.", true);
    LinearLayout control = card(page);
    Switch enabled = new Switch(this);
    enabled.setText("Custom mappings enabled");
    enabled.setTextSize(17);
    enabled.setTextColor(ink);
    enabled.setChecked(Rules.enabled(this));
    enabled.setPadding(0, dp(8), 0, dp(8));
    enabled.setOnCheckedChangeListener(
        (view, checked) -> {
          Rules.setEnabled(this, checked);
          showHome();
        });
    control.addView(enabled, new LinearLayout.LayoutParams(-1, -2));
    text(
        control,
        enabled.isChecked()
            ? "Only mapped events are replaced. Everything else keeps its original behavior."
            : "All events currently use their original Soundcore behavior. Your mappings are"
                + " saved.",
        14,
        muted,
        false);
    for (String key : Rules.keys(this, "rule:")) {
      int split = key.indexOf(':');
      String kind = key.substring(0, split);
      String name = key.substring(split + 1);
      Rules.Action action = Rules.stored(this, kind, name);
      if (action == null) continue;
      LinearLayout mapping = card(page);
      text(mapping, kindLabel(kind).toUpperCase(java.util.Locale.ROOT), 11, muted, true);
      text(mapping, Rules.title(name), 21, ink, true);
      text(mapping, "→  " + action.label, 19, accent, true);
      text(mapping, actionDescription(action.type), 14, muted, false);
      button(mapping, "Edit mapping", () -> edit(kind, name));
    }
    button(page, "+ Add a mapping", this::chooseTriggerSource);
    button(page, "Recent events", () -> chooseTrigger("recent"));
    button(
        page,
        "Open Soundcore",
        () -> {
          Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
          if (intent != null) {
            intent.setClassName(
                getPackageName(), "com.oceanwing.soundcore.activity.WelcomeActivity");
            startActivity(intent);
          }
        });
    gap(page, 12);
    text(
        page,
        "Maps Soundcore app events. Controls handled entirely by the earbuds, or events delivered"
            + " inside an already-running component, may not appear here.",
        13,
        muted,
        false);
  }

  private static String kindLabel(String kind) {
    if ("service".equals(kind)) return "Background service";
    if ("receiver".equals(kind)) return "Broadcast event";
    return "App screen";
  }

  private static String actionDescription(String type) {
    switch (type) {
      case "app":
        return "Open an app";
      case "link":
        return "Open a link";
      case "intent":
        return "Launch an Android intent";
      default:
        return "Send a broadcast to an automation app";
    }
  }

  private void chooseTriggerSource() {
    new AlertDialog.Builder(this)
        .setTitle("Choose what to replace")
        .setItems(
            new String[] {
              "Anka assistant",
              "Real-time translation",
              "Face-to-face translation",
              "Recent events",
              "Browse all app screens",
              "Background events (advanced)"
            },
            (dialog, choice) -> {
              if (choice < 3)
                edit("activity", new String[] {Rules.ANKA, Rules.REALTIME, Rules.FACE}[choice]);
              else chooseTrigger(choice == 3 ? "recent" : choice == 4 ? "activity" : "background");
            })
        .show();
  }

  private static final class Trigger {
    final String kind;
    final String name;

    Trigger(String kind, String name) {
      this.kind = kind;
      this.name = name;
    }
  }

  private List<Trigger> triggers(String mode) {
    List<Trigger> items = new ArrayList<>();
    if ("recent".equals(mode)) {
      java.util.Map<String, Long> history = Rules.events(this);
      for (String key : history.keySet()) {
        int split = key.indexOf(':');
        items.add(new Trigger(key.substring(0, split), key.substring(split + 1)));
      }
      items.sort(
          (a, b) ->
              Long.compare(
                  history.getOrDefault(b.kind + ":" + b.name, 0L),
                  history.getOrDefault(a.kind + ":" + a.name, 0L)));
      return items;
    }
    try {
      PackageInfo info =
          getPackageManager()
              .getPackageInfo(
                  getPackageName(),
                  PackageManager.GET_ACTIVITIES
                      | PackageManager.GET_SERVICES
                      | PackageManager.GET_RECEIVERS);
      if ("activity".equals(mode)) addComponents(items, "activity", info.activities);
      else {
        addComponents(items, "service", info.services);
        addComponents(items, "receiver", info.receivers);
      }
    } catch (PackageManager.NameNotFoundException impossible) {
      throw new IllegalStateException(impossible);
    }
    items.sort(Comparator.comparing(item -> Rules.title(item.name), String.CASE_INSENSITIVE_ORDER));
    return items;
  }

  private void addComponents(List<Trigger> items, String kind, ComponentInfo[] components) {
    if (components == null) return;
    for (ComponentInfo component : components) {
      if (component.name.startsWith("com.colonelpanic.soundcorepatch.")) continue;
      if (component instanceof ActivityInfo && ((ActivityInfo) component).targetActivity != null)
        continue;
      items.add(new Trigger(kind, component.name));
    }
  }

  private void chooseTrigger(String mode) {
    startPage(
        "recent".equals(mode) ? "Recent events" : "Choose a trigger",
        "recent".equals(mode)
            ? "Use a Soundcore feature, then return here to find its event."
            : "Select the Soundcore event whose original behavior you want to replace.",
        false);
    if ("background".equals(mode))
      text(
          page,
          "Advanced: replacing a service or receiver can stop the function it provides, including"
              + " device connectivity. Android may restrict opening apps from background events."
              + " Running services must restart before a new mapping applies.",
          14,
          muted,
          false);
    EditText search = field(page, "Search events", "", "Anka, translation, controls…");
    LinearLayout list = column();
    page.addView(list);
    List<Trigger> all = triggers(mode);
    java.util.Map<String, Long> history = Rules.events(this);
    Runnable populate =
        () -> {
          list.removeAllViews();
          String query = search.getText().toString().toLowerCase(java.util.Locale.ROOT);
          int shown = 0;
          for (Trigger item : all) {
            if (!(Rules.title(item.name) + " " + item.name)
                .toLowerCase(java.util.Locale.ROOT)
                .contains(query)) continue;
            if (shown++ >= 80) {
              text(
                  list,
                  "More events match. Refine your search to find a specific event.",
                  14,
                  muted,
                  false);
              break;
            }
            LinearLayout row = card(list);
            text(row, Rules.title(item.name), 18, ink, true);
            String detail = kindLabel(item.kind);
            if ("recent".equals(mode))
              detail +=
                  " · "
                      + DateUtils.getRelativeTimeSpanString(
                          history.getOrDefault(item.kind + ":" + item.name, 0L));
            text(row, detail, 13, muted, false);
            button(row, "Use this event", () -> edit(item.kind, item.name));
            button(
                row,
                "Event details",
                () ->
                    new AlertDialog.Builder(this)
                        .setTitle(Rules.title(item.name))
                        .setMessage(item.name)
                        .setPositiveButton("Close", null)
                        .show());
          }
          if (list.getChildCount() == 0)
            text(
                list,
                "No matching events. Try a Soundcore feature and return to Recent events.",
                16,
                muted,
                false);
        };
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            populate.run();
          }

          public void afterTextChanged(Editable s) {}
        });
    populate.run();
  }

  private EditText field(LinearLayout parent, String title, String value, String hint) {
    text(parent, title, 14, muted, true);
    EditText input = new EditText(this);
    input.setTextColor(ink);
    input.setHintTextColor(muted);
    input.setTextSize(16);
    input.setSingleLine(true);
    input.setText(value);
    input.setHint(hint);
    input.setPadding(dp(4), dp(10), dp(4), dp(10));
    parent.addView(input, new LinearLayout.LayoutParams(-1, -2));
    return input;
  }

  private void edit(String kind, String name) {
    Rules.Action saved = Rules.stored(this, kind, name);
    Rules.Action initial = saved == null ? Rules.Action.paseo() : saved;
    startPage(
        Rules.title(name),
        "When this "
            + kindLabel(kind).toLowerCase(java.util.Locale.ROOT)
            + " is requested, do this instead.",
        false);
    LinearLayout form = card(page);
    text(form, "Action", 14, muted, true);
    String[] types = {"app", "link", "intent", "broadcast"};
    Spinner type = new Spinner(this);
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {
              "Open an app", "Open a link", "Android intent", "Broadcast to an automation app"
            });
    type.setAdapter(adapter);
    form.addView(type, new LinearLayout.LayoutParams(-1, dp(56)));
    for (int i = 0; i < types.length; i++) if (types[i].equals(initial.type)) type.setSelection(i);
    EditText label =
        field(form, "Display name", initial.label, "What should this action be called?");
    LinearLayout packageGroup = column();
    form.addView(packageGroup);
    EditText packageName =
        field(
            packageGroup,
            "App",
            initial.packageName,
            "Choose an app, or leave empty for the default handler");
    button(packageGroup, "Choose installed app", () -> pickApp(packageName, label));
    LinearLayout valueGroup = column();
    form.addView(valueGroup);
    EditText value = field(valueGroup, "Link or intent URI", initial.value, "paseo://live-voice");
    TextView help = text(form, "", 13, muted, false);
    type.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            valueGroup.setVisibility(position == 0 ? View.GONE : View.VISIBLE);
            help.setText(
                position == 0
                    ? "Choose the installed app to open when this event arrives."
                    : position == 1
                        ? "The app field is optional for links. Choose an app to send the link to a"
                            + " specific handler."
                        : "Use an Android intent URI, for example"
                            + " intent:#Intent;action=com.example.ACTION;end. The receiving app"
                            + " must accept that intent.");
          }

          public void onNothingSelected(AdapterView<?> parent) {}
        });
    TextView error = text(form, "", 14, Color.rgb(181, 47, 54), false);
    java.util.function.Supplier<Rules.Action> read =
        () ->
            new Rules.Action(
                types[type.getSelectedItemPosition()],
                label.getText().toString().trim(),
                packageName.getText().toString().trim(),
                value.getText().toString().trim());
    button(
        form,
        "Save mapping",
        () -> {
          Rules.Action action = read.get();
          try {
            if (action.label.isEmpty())
              throw new IllegalArgumentException("Give this action a display name.");
            ActionRunner.intent(this, action);
            Rules.put(this, kind, name, action);
            Toast.makeText(this, "Mapping saved", Toast.LENGTH_SHORT).show();
            showHome();
          } catch (Exception invalid) {
            error.setText(invalid.getMessage());
          }
        });
    button(form, "Test action", () -> ActionRunner.run(this, read.get()));
    button(
        page,
        "Use original Soundcore behavior",
        () -> {
          Rules.put(this, kind, name, null);
          showHome();
        });
    button(
        page,
        "Paseo Live Voice preset",
        () -> {
          Rules.Action preset = Rules.Action.paseo();
          type.setSelection(1);
          label.setText(preset.label);
          packageName.setText(preset.packageName);
          value.setText(preset.value);
        });
    button(
        page,
        "Event details",
        () ->
            new AlertDialog.Builder(this)
                .setTitle(Rules.title(name))
                .setMessage(name)
                .setPositiveButton("Close", null)
                .show());
  }

  private void pickApp(EditText packageName, EditText label) {
    Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    List<ResolveInfo> apps = getPackageManager().queryIntentActivities(launcher, 0);
    apps.removeIf(item -> getPackageName().equals(item.activityInfo.packageName));
    apps.sort(
        Comparator.comparing(
            item -> item.loadLabel(getPackageManager()).toString(), String.CASE_INSENSITIVE_ORDER));
    String[] names = new String[apps.size()];
    for (int i = 0; i < apps.size(); i++)
      names[i] = apps.get(i).loadLabel(getPackageManager()).toString();
    new AlertDialog.Builder(this)
        .setTitle("Choose an app")
        .setItems(
            names,
            (dialog, index) -> {
              packageName.setText(apps.get(index).activityInfo.packageName);
              label.setText(names[index]);
            })
        .show();
  }

  @Override
  public void onBackPressed() {
    if (home) super.onBackPressed();
    else showHome();
  }
}
