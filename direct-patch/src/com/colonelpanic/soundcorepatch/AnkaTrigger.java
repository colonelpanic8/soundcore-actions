package com.colonelpanic.soundcorepatch;

import java.util.function.LongSupplier;

final class AnkaTrigger {
  private final LongSupplier clock;
  private long lastDispatch = -1;
  private boolean lastWasGesture;

  AnkaTrigger(LongSupplier clock) {
    this.clock = clock;
  }

  synchronized boolean accept(boolean gesture) {
    long now = clock.getAsLong();
    if (lastDispatch >= 0) {
      long elapsed = now - lastDispatch;
      if (elapsed < 1500 && gesture != lastWasGesture) return false;
      if (elapsed < 500 && gesture && lastWasGesture) return false;
    }
    lastDispatch = now;
    lastWasGesture = gesture;
    return true;
  }
}
