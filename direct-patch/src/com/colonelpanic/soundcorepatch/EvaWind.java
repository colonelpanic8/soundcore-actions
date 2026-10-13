package com.colonelpanic.soundcorepatch;

import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Wind reduction capabilities in EVA's installed-app extension protocol v1. */
final class EvaWind {
  static final String REVISION = "soundcore-wind-1";
  static final String STATUS = "wind_reduction_status";
  static final String SET = "set_wind_reduction";
  static final long MAX_WAIT_MILLIS = 15000;
  static final long REPLY_MARGIN_MILLIS = 1500;
  private static final int MAX_ARGUMENT_BYTES = 16384;

  interface Runner {
    void run(WindToggle.Mode mode, long budgetMillis, WindToggle.Result result);
  }

  interface Journal {
    /** Returns null after recording a new entry, otherwise the existing entry. */
    JSONObject begin(String key, String request);

    void finish(String key, String envelope);
  }

  private final Runner runner;
  private final Journal journal;
  private final LongSupplier elapsed;

  EvaWind(Runner runner, Journal journal, LongSupplier elapsed) {
    this.runner = runner;
    this.journal = journal;
    this.elapsed = elapsed;
  }

  static String describe() {
    try {
      JSONObject descriptor =
          new JSONObject(
              """
              {
                "protocolVersion": 1,
                "authorizationScopeRevision": "local-1",
                "title": "Soundcore",
                "capabilities": [
                  {
                    "tool": {
                      "name": "wind_reduction_status",
                      "title": "Check wind reduction",
                      "description": "Read whether wind noise reduction is on for the earbuds connected in the Soundcore app. Supports Liberty 5 Pro.",
                      "inputSchema": {"type": "object", "properties": {}, "required": [], "additionalProperties": false},
                      "outputSchema": {
                        "type": "object",
                        "properties": {"enabled": {"type": "boolean"}},
                        "required": ["enabled"],
                        "additionalProperties": false
                      },
                      "annotations": {"readOnlyHint": true, "openWorldHint": false}
                    },
                    "effects": "read",
                    "execution": {"mode": "synchronous", "requiresForeground": false, "maxWaitMillis": 15000},
                    "result": {"maxBytes": 4096}
                  },
                  {
                    "tool": {
                      "name": "set_wind_reduction",
                      "title": "Set wind reduction",
                      "description": "Turn wind noise reduction on or off, or toggle it, on the earbuds connected in the Soundcore app. Reads the current setting first and confirms the change with the earbuds. Supports Liberty 5 Pro.",
                      "inputSchema": {
                        "type": "object",
                        "properties": {
                          "state": {
                            "type": "string",
                            "enum": ["on", "off", "toggle"],
                            "description": "on or off sets wind reduction; toggle flips the current setting."
                          }
                        },
                        "required": ["state"],
                        "additionalProperties": false
                      },
                      "outputSchema": {
                        "type": "object",
                        "properties": {
                          "invocationId": {"type": "string"},
                          "state": {"type": "string"},
                          "enabled": {"type": "boolean", "description": "Wind reduction as last confirmed by the earbuds."}
                        },
                        "required": ["invocationId", "state"],
                        "additionalProperties": false
                      },
                      "annotations": {"readOnlyHint": false, "destructiveHint": false, "openWorldHint": false}
                    },
                    "effects": "write",
                    "execution": {"mode": "synchronous", "requiresForeground": false, "maxWaitMillis": 15000},
                    "result": {"maxBytes": 4096}
                  }
                ]
              }
              """);
      descriptor.put("descriptorRevision", REVISION);
      return envelope("completed", null, "").put("descriptor", descriptor).toString();
    } catch (JSONException error) {
      throw new IllegalStateException(error);
    }
  }

  static String describeFailure(String status, String reason, String message) {
    try {
      return envelope(status, reason, message).put("descriptor", JSONObject.NULL).toString();
    } catch (JSONException error) {
      throw new IllegalStateException(error);
    }
  }

  static String rejection(String invocationId, String capability, String reason, String message) {
    return reply(
        "not_executed",
        reason,
        message,
        SET.equals(capability) ? receipt(invocationId, "rejected", null) : null);
  }

  static String unexpected(String invocationId, String capability) {
    return SET.equals(capability)
        ? reply(
            "unknown",
            null,
            "Soundcore hit an unexpected error; wind reduction may have changed.",
            receipt(invocationId, "uncertain", null))
        : reply("failed", null, "Soundcore hit an unexpected error.", null);
  }

  String execute(
      int caller,
      String invocationId,
      String expectedRevision,
      String capability,
      String argumentsJson,
      long deadline) {
    boolean write = SET.equals(capability);
    if (!write && !STATUS.equals(capability))
      return reply("not_executed", "invalid_arguments", "Unknown capability " + capability, null);
    if (!REVISION.equals(expectedRevision))
      return reply(
          "not_executed",
          "stale_descriptor",
          "Soundcore's wind reduction actions changed. Nothing was changed.",
          write ? receipt(invocationId, "rejected", null) : null);
    WindToggle.Mode mode;
    try {
      mode = mode(capability, argumentsJson);
    } catch (IllegalArgumentException | JSONException error) {
      return reply(
          "not_executed",
          "invalid_arguments",
          "Invalid arguments: " + error.getMessage(),
          write ? receipt(invocationId, "invalid_request", null) : null);
    }
    long budget =
        Math.min(deadline - elapsed.getAsLong(), MAX_WAIT_MILLIS + 5000) - REPLY_MARGIN_MILLIS;
    if (budget < 1000)
      return reply(
          "not_executed",
          "deadline_exceeded",
          "Not enough time left to reach the earbuds. Nothing was changed.",
          write ? receipt(invocationId, "expired", null) : null);
    String key = null;
    if (write) {
      key = caller + ":" + invocationId;
      JSONObject existing;
      try {
        existing = journal.begin(key, mode.name());
      } catch (RuntimeException error) {
        return reply(
            "not_executed",
            null,
            "Soundcore could not record the request. Nothing was changed.",
            receipt(invocationId, "not_sent", null));
      }
      if (existing != null) {
        if (!mode.name().equals(existing.optString("request")))
          return reply(
              "not_executed",
              "invalid_arguments",
              "This invocation ID was already used for a different request.",
              receipt(invocationId, "request_id_conflict", null));
        String recorded = existing.optString("envelope", "");
        if (!recorded.isEmpty()) return recorded;
        return reply(
            "unknown",
            null,
            "An earlier attempt of this request was interrupted; wind reduction may have changed.",
            receipt(invocationId, "uncertain", null));
      }
    }
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<WindToggle.Outcome> result = new AtomicReference<>();
    runner.run(
        mode,
        budget,
        outcome -> {
          result.set(outcome);
          done.countDown();
        });
    try {
      done.await(budget + 500, TimeUnit.MILLISECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
    WindToggle.Outcome outcome = result.get();
    if (outcome == null)
      outcome =
          new WindToggle.Outcome(
              write ? "uncertain" : "failed", null, "Earbuds did not answer in time.");
    String reply = write ? writeReply(invocationId, outcome) : readReply(outcome);
    if (write) {
      try {
        journal.finish(key, reply);
      } catch (RuntimeException error) {
        Log.w("SoundcoreActions", "Could not record the EVA wind reduction outcome", error);
      }
    }
    return reply;
  }

  private static WindToggle.Mode mode(String capability, String argumentsJson)
      throws JSONException {
    if (argumentsJson.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES)
      throw new IllegalArgumentException("arguments are too large");
    JSONObject arguments = new JSONObject(argumentsJson);
    Iterator<String> keys = arguments.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (!(SET.equals(capability) && key.equals("state")))
        throw new IllegalArgumentException("unexpected argument " + key);
    }
    if (STATUS.equals(capability)) return WindToggle.Mode.READ;
    Object state = arguments.opt("state");
    if ("on".equals(state)) return WindToggle.Mode.ON;
    if ("off".equals(state)) return WindToggle.Mode.OFF;
    if ("toggle".equals(state)) return WindToggle.Mode.TOGGLE;
    throw new IllegalArgumentException("state must be on, off, or toggle");
  }

  private static String readReply(WindToggle.Outcome outcome) {
    switch (outcome.state) {
      case "completed":
        try {
          return reply(
              "completed",
              null,
              outcome.message,
              new JSONObject().put("enabled", outcome.enabled.booleanValue()));
        } catch (JSONException error) {
          throw new IllegalStateException(error);
        }
      case "busy":
        return reply("not_executed", "busy", outcome.message, null);
      case "needs_configuration":
        return reply("not_executed", "not_configured", outcome.message, null);
      default:
        return reply("failed", null, outcome.message, null);
    }
  }

  private static String writeReply(String invocationId, WindToggle.Outcome outcome) {
    String status, reason = null, state = outcome.state;
    switch (outcome.state) {
      case "completed":
        status = "completed";
        break;
      case "not_sent":
        status = "not_executed";
        break;
      case "busy":
        status = "not_executed";
        reason = "busy";
        state = "rejected";
        break;
      case "needs_configuration":
        status = "not_executed";
        reason = "not_configured";
        break;
      case "failed":
        status = "failed";
        break;
      default:
        status = "unknown";
        state = "uncertain";
    }
    return reply(status, reason, outcome.message, receipt(invocationId, state, outcome.enabled));
  }

  private static JSONObject receipt(String invocationId, String state, Boolean enabled) {
    try {
      JSONObject receipt = new JSONObject().put("invocationId", invocationId).put("state", state);
      if (enabled != null) receipt.put("enabled", enabled.booleanValue());
      return receipt;
    } catch (JSONException error) {
      throw new IllegalStateException(error);
    }
  }

  private static String reply(
      String status, String reason, String message, JSONObject structuredContent) {
    try {
      JSONObject reply = envelope(status, reason, message);
      if (structuredContent != null) reply.put("structuredContent", structuredContent);
      return reply.toString();
    } catch (JSONException error) {
      throw new IllegalStateException(error);
    }
  }

  private static JSONObject envelope(String status, String reason, String message)
      throws JSONException {
    return new JSONObject()
        .put("protocolVersion", 1)
        .put("status", status)
        .put("reasonCode", reason == null ? JSONObject.NULL : reason)
        .put("truncated", false)
        .put(
            "content",
            message.isEmpty()
                ? new JSONArray()
                : new JSONArray().put(new JSONObject().put("type", "text").put("text", message)));
  }
}
