package com.colonelpanic.soundcorepatch;

final class WindToggle {
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
    void finish(boolean success, String message);
  }

  private final Device device;
  private final Result result;
  private int stage;
  private boolean target;

  WindToggle(Device device, Result result) {
    this.device = device;
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
                finish(false, "Earbuds rejected the wind reduction change.");
                return;
              }
              stage = 3;
              try {
                device.refresh();
              } catch (Exception error) {
                fail(error);
              }
            }
          });
      device.refresh();
    } catch (Exception error) {
      fail(error);
    }
  }

  private void receive(boolean success, boolean enabled) {
    if (stage != 1 && stage != 3) return;
    if (!success) {
      finish(false, "Could not read wind reduction from the earbuds.");
      return;
    }
    if (stage == 3) {
      finish(
          enabled == target,
          enabled == target
              ? "Wind reduction " + (enabled ? "on" : "off")
              : "Wind reduction change was not confirmed.");
      return;
    }
    target = !enabled;
    stage = 2;
    try {
      device.write(target);
    } catch (Exception error) {
      fail(error);
    }
  }

  void timeout() {
    finish(false, "Earbuds did not respond. Connect them in Soundcore and try again.");
  }

  private void fail(Exception error) {
    finish(false, "Wind reduction unavailable: " + error.getMessage());
  }

  private void finish(boolean success, String message) {
    if (stage == 4) return;
    stage = 4;
    try {
      device.close();
    } catch (Exception error) {
      success = false;
      message = "Could not finish wind reduction command.";
    }
    result.finish(success, message);
  }
}
