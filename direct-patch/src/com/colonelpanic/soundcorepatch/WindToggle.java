package com.colonelpanic.soundcorepatch;

import android.util.Log;

final class WindToggle {
  enum Mode {
    READ,
    TOGGLE,
    ON,
    OFF
  }

  interface Device {
    void listen(Listener listener) throws Exception;

    void refresh() throws Exception;

    void write(boolean enabled) throws Exception;

    void close() throws Exception;
  }

  interface Listener {
    void state(boolean success, boolean enabled);

    void acknowledged(boolean success);
  }

  interface Result {
    void finish(Outcome outcome);
  }

  /**
   * {@code state} is {@code completed}, {@code not_sent} (nothing was written), {@code failed}
   * (definitely unchanged), {@code uncertain} (a write may have taken effect), {@code busy}, or
   * {@code needs_configuration}. {@code enabled} is the last confirmed setting, or null.
   */
  static final class Outcome {
    final String state;
    final Boolean enabled;
    final String message;

    Outcome(String state, Boolean enabled, String message) {
      this.state = state;
      this.enabled = enabled;
      this.message = message;
    }

    boolean success() {
      return "completed".equals(state);
    }
  }

  private final Device device;
  private final Mode mode;
  private final Result result;
  private int stage;
  private boolean target;
  private Boolean known;

  WindToggle(Device device, Mode mode, Result result) {
    this.device = device;
    this.mode = mode;
    this.result = result;
  }

  void start() {
    try {
      stage = 1;
      device.listen(
          new Listener() {
            public void state(boolean success, boolean enabled) {
              receive(success, enabled);
            }

            public void acknowledged(boolean success) {
              if (stage != 2) return;
              if (!success) {
                finish("failed", known, "Earbuds rejected the wind reduction change.");
                return;
              }
              stage = 3;
              try {
                device.refresh();
              } catch (Exception error) {
                finish("uncertain", null, "Wind reduction change was not confirmed.");
              }
            }
          });
      device.refresh();
    } catch (Exception error) {
      finish("not_sent", null, "Wind reduction unavailable: " + error.getMessage());
    }
  }

  private void receive(boolean success, boolean enabled) {
    if (stage != 1 && stage != 3) return;
    if (!success) {
      if (stage == 1) finish("not_sent", null, "Could not read wind reduction from the earbuds.");
      else finish("uncertain", null, "Wind reduction change was not confirmed.");
      return;
    }
    if (stage == 3) {
      if (enabled == target) finish("completed", enabled, describe(enabled));
      else finish("failed", enabled, "Wind reduction change was not applied.");
      return;
    }
    known = enabled;
    if (mode == Mode.READ) {
      finish("completed", enabled, describe(enabled));
      return;
    }
    target = mode == Mode.TOGGLE ? !enabled : mode == Mode.ON;
    if (target == enabled) {
      finish("completed", enabled, "Wind reduction already " + (enabled ? "on" : "off"));
      return;
    }
    stage = 2;
    try {
      device.write(target);
    } catch (Exception error) {
      finish("uncertain", known, "Wind reduction change may not have been sent.");
    }
  }

  private static String describe(boolean enabled) {
    return "Wind reduction " + (enabled ? "on" : "off");
  }

  void timeout() {
    if (stage == 1) finish("not_sent", null, "Earbuds did not respond. Nothing was changed.");
    else finish("uncertain", null, "Earbuds did not confirm the wind reduction change.");
  }

  private void finish(String state, Boolean enabled, String message) {
    if (stage == 4) return;
    stage = 4;
    try {
      device.close();
    } catch (Exception error) {
      Log.w("SoundcoreActions", "Could not remove wind reduction listener", error);
    }
    result.finish(new Outcome(state, enabled, message));
  }
}
