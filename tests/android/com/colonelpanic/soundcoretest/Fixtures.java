package com.colonelpanic.soundcoretest;

import android.app.Activity;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import java.util.ArrayList;
import java.util.List;

public final class Fixtures {
  public static final class Screen extends Activity {}

  public static final class Target extends Activity {}

  public interface BaseCallback {
    boolean onPreFunAllOtherFun();
  }

  public interface EarbudCallback extends BaseCallback {
    void getAIChatStartCmdCallback(boolean success, boolean start);

    void getAudioRecordCmdCallback(boolean success, boolean start, int action);
  }

  public static final class Device {
    public String product = "D1203";

    public String getProductCode() {
      return product;
    }
  }

  public static final class EarbudManager {
    public static EarbudManager y = new EarbudManager();
    public final List<BaseCallback> listeners = new ArrayList<>();
    public final Device device = new Device();

    public Device l() {
      return device;
    }

    public void Q(BaseCallback callback) {
      if (!listeners.contains(callback)) listeners.add(callback);
    }

    public void T(BaseCallback callback) {
      listeners.remove(callback);
    }

    public void emit(boolean success, boolean start) {
      for (BaseCallback callback : new ArrayList<>(listeners))
        ((EarbudCallback) callback).getAIChatStartCmdCallback(success, start);
    }

    public void emitTranslation(boolean success, boolean start, int action) {
      for (BaseCallback callback : new ArrayList<>(listeners))
        ((EarbudCallback) callback).getAudioRecordCmdCallback(success, start, action);
    }
  }

  public static final class Background extends Service {
    @Override
    public IBinder onBind(Intent intent) {
      return null;
    }
  }

  public static final class Receiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {}
  }
}
