package com.colonelpanic.soundcorepatch;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import com.colonelpanic.eva.extension.IEvaExtension;
import com.colonelpanic.eva.extension.IEvaExtensionCallback;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Exported binder EVA discovers through {@code com.colonelpanic.eva.action.EXTENSION}. It runs in
 * Soundcore's main process, which owns the earbud connection. Binder threads only authenticate and
 * enqueue.
 */
public final class EvaExtensionService extends Service {
  private static final String TAG = "SoundcoreActions";
  private static final String EVA = "com.colonelpanic.eva";
  private static final String EVA_DEBUG = "com.colonelpanic.eva.debug";
  private static final String EVA_RELEASE_CERT =
      "688df17827dd9a002705baf0400c80f8f4650c6e87c3fc91d41f32be287f8b68";
  private static final String RELEASE_CERT =
      "6286a277fe2f2b0d8b0d5121bed231d150f1baffa6bfa4fe763adc95c6fc0640";
  private static final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));

  static {
    executor.allowCoreThreadTimeOut(true);
  }

  private EvaWind wind;

  @Override
  public void onCreate() {
    super.onCreate();
    Context app = getApplicationContext();
    wind =
        new EvaWind(
            (mode, budget, result) -> WindToggleReceiver.run(app, mode, budget, result),
            new FileJournal(new File(app.getNoBackupFilesDir(), "eva-invocations.json")),
            SystemClock::elapsedRealtime);
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  private final IEvaExtension.Stub binder =
      new IEvaExtension.Stub() {
        @Override
        public void describe(
            String requestId, String requestJson, long deadline, IEvaExtensionCallback callback) {
          if (requestId == null || callback == null) return;
          if (!trusted(Binder.getCallingUid())) {
            reply(
                callback,
                requestId,
                EvaWind.describeFailure(
                    "not_executed", "unauthorized_caller", "Caller is not authorized."));
            return;
          }
          reply(callback, requestId, EvaWind.describe());
        }

        @Override
        public void execute(
            String invocationId,
            String expectedRevision,
            String capability,
            String argumentsJson,
            long deadline,
            IEvaExtensionCallback callback) {
          if (invocationId == null || callback == null) return;
          int caller = Binder.getCallingUid();
          String name = capability == null ? "" : capability;
          if (!trusted(caller)) {
            reply(
                callback,
                invocationId,
                EvaWind.rejection(
                    invocationId, name, "unauthorized_caller", "Caller is not authorized."));
            return;
          }
          try {
            executor.execute(
                () -> {
                  String result;
                  try {
                    result =
                        wind.execute(
                            caller,
                            invocationId,
                            expectedRevision == null ? "" : expectedRevision,
                            name,
                            argumentsJson == null ? "{}" : argumentsJson,
                            deadline);
                  } catch (RuntimeException error) {
                    Log.e(TAG, "EVA wind reduction request failed", error);
                    result = EvaWind.unexpected(invocationId, name);
                  }
                  reply(callback, invocationId, result);
                });
          } catch (RejectedExecutionException error) {
            reply(
                callback,
                invocationId,
                EvaWind.rejection(
                    invocationId,
                    name,
                    "busy",
                    "Soundcore is busy with other requests. Nothing was changed."));
          }
        }
      };

  private static void reply(IEvaExtensionCallback callback, String requestId, String response) {
    try {
      callback.onResult(requestId, response);
    } catch (RemoteException error) {
      Log.w(TAG, "EVA stopped waiting for a reply", error);
    }
  }

  /**
   * Trusts a UID only when every package sharing it is EVA with the expected signer: release EVA by
   * its pinned certificate, and debug EVA when this build is not release-signed and shares its
   * signer.
   */
  private boolean trusted(int uid) {
    PackageManager packages = getPackageManager();
    String[] names = packages.getPackagesForUid(uid);
    if (names == null || names.length == 0) return false;
    for (String name : names) {
      if (EVA.equals(name)) {
        if (!packages.hasSigningCertificate(
            name, hex(EVA_RELEASE_CERT), PackageManager.CERT_INPUT_SHA256)) return false;
      } else if (EVA_DEBUG.equals(name)) {
        List<byte[]> own = ownSigners();
        boolean release = false, shared = false;
        for (byte[] signer : own) {
          release |= MessageDigest.isEqual(signer, hex(RELEASE_CERT));
          shared |= packages.hasSigningCertificate(name, signer, PackageManager.CERT_INPUT_SHA256);
        }
        if (release || !shared) return false;
      } else return false;
    }
    return true;
  }

  private List<byte[]> ownSigners() {
    List<byte[]> digests = new ArrayList<>();
    try {
      PackageInfo info =
          getPackageManager()
              .getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
      if (info.signingInfo == null) return digests;
      for (Signature signature : info.signingInfo.getApkContentsSigners())
        digests.add(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()));
    } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException error) {
      Log.w(TAG, "Could not read Soundcore's signer", error);
    }
    return digests;
  }

  private static byte[] hex(String text) {
    byte[] bytes = new byte[text.length() / 2];
    for (int i = 0; i < bytes.length; i++)
      bytes[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
    return bytes;
  }

  /** Write invocations, recorded before the earbuds are contacted, kept for 14 days. */
  static final class FileJournal implements EvaWind.Journal {
    private static final long RETENTION_MILLIS = 14L * 24 * 60 * 60 * 1000;
    private final AtomicFile file;

    FileJournal(File file) {
      this.file = new AtomicFile(file);
    }

    @Override
    public synchronized JSONObject begin(String key, String request) {
      JSONObject all = load();
      JSONObject existing = all.optJSONObject(key);
      if (existing != null) return existing;
      try {
        all.put(
            key, new JSONObject().put("request", request).put("at", System.currentTimeMillis()));
      } catch (JSONException error) {
        throw new IllegalStateException(error);
      }
      save(all);
      return null;
    }

    @Override
    public synchronized void finish(String key, String envelope) {
      JSONObject all = load();
      JSONObject entry = all.optJSONObject(key);
      if (entry == null) return;
      try {
        entry.put("envelope", envelope);
      } catch (JSONException error) {
        throw new IllegalStateException(error);
      }
      save(all);
    }

    private JSONObject load() {
      if (!file.getBaseFile().exists()) return new JSONObject();
      try {
        return new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
      } catch (IOException | JSONException error) {
        Log.w(TAG, "Discarding unreadable EVA invocation journal", error);
        return new JSONObject();
      }
    }

    private void save(JSONObject all) {
      long cutoff = System.currentTimeMillis() - RETENTION_MILLIS;
      List<String> expired = new ArrayList<>();
      for (Iterator<String> keys = all.keys(); keys.hasNext(); ) {
        String key = keys.next();
        JSONObject entry = all.optJSONObject(key);
        if (entry == null || entry.optLong("at") < cutoff) expired.add(key);
      }
      for (String key : expired) all.remove(key);
      FileOutputStream stream = null;
      try {
        stream = file.startWrite();
        stream.write(all.toString().getBytes(StandardCharsets.UTF_8));
        file.finishWrite(stream);
      } catch (IOException error) {
        if (stream != null) file.failWrite(stream);
        throw new IllegalStateException("Could not record the EVA request", error);
      }
    }
  }
}
