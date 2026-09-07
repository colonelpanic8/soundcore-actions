package com.colonelpanic.soundcorepatch;

import android.content.Context;
import android.os.Handler;
import java.lang.reflect.Proxy;

final class WindDevice implements WindToggle.Device {
  private final Object manager;
  private final Class<?> base;
  private final Class<?> events;
  private final ClassLoader loader;
  private final Handler handler;
  private final String address;
  private Object listener;

  WindDevice(Context context, Handler handler) throws Exception {
    this.handler = handler;
    loader = context.getClassLoader();
    Class<?> type = loader.loadClass("com.oceanwing.devicecmd.manager.cmmbt2.Cmm2BtDeviceManager");
    manager = type.getField("y").get(null);
    if (manager == null)
      throw new IllegalStateException("Connect Liberty 5 Pro in Soundcore first.");
    base = loader.loadClass("com.oceanwing.devicecmd.manager.BaseBtEventCallback");
    events = loader.loadClass("com.oceanwing.devicecmd.manager.cmmbt2.Cmm2BtEventCallback");
    address = identity();
  }

  private static Object call(Object object, String method) throws Exception {
    return object.getClass().getMethod(method).invoke(object);
  }

  private String identity() throws Exception {
    Object widget = call(manager, "l");
    if (widget == null || !"D1203".equals(call(widget, "getProductCode")))
      throw new IllegalStateException("Connect Liberty 5 Pro in Soundcore first.");
    String mac = (String) call(widget, "getMacAddress");
    if (mac == null || mac.isEmpty())
      throw new IllegalStateException("No connected earbud address.");
    return mac;
  }

  private void checkDevice() throws Exception {
    if (!address.equals(identity()))
      throw new IllegalStateException("The active earbuds changed. Try again.");
  }

  public void listen(WindToggle.Listener target) throws Exception {
    listener =
        Proxy.newProxyInstance(
            loader,
            new Class<?>[] {base, events},
            (proxy, method, args) -> {
              if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("equals")) return proxy == args[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                return "Wind reduction listener";
              }
              if (method.getName().equals("getDeviceInfoCallback")) {
                boolean success = Boolean.TRUE.equals(args[0]);
                boolean enabled = false;
                try {
                  checkDevice();
                  enabled = (Boolean) call(call(args[1], "getAncModel"), "isWindNoiseSuppression");
                } catch (Exception error) {
                  success = false;
                }
                final boolean ok = success, state = enabled;
                handler.post(() -> target.state(ok, state));
              } else if (method.getName().equals("setFunSwitchCallback")
                  && ((Integer) args[1]) == 11) {
                boolean success = Boolean.TRUE.equals(args[0]);
                handler.post(() -> target.acknowledged(success));
              }
              return method.getReturnType() == boolean.class ? false : null;
            });
    manager.getClass().getMethod("Q", base).invoke(manager, listener);
  }

  public void refresh() throws Exception {
    checkDevice();
    call(manager, "r");
  }

  public void write(boolean enabled) throws Exception {
    checkDevice();
    manager.getClass().getMethod("D5", int.class, boolean.class).invoke(manager, 11, enabled);
  }

  public void close() throws Exception {
    if (listener != null) manager.getClass().getMethod("T", base).invoke(manager, listener);
    listener = null;
  }
}
