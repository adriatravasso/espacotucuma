package br.com.espacotucuma.sync;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Small fixed-window limiter keyed by the authenticated device. */
final class DeviceRequestRateLimiter {
  private static final int MAX_TRACKED_KEYS = 4096;
  private final int maximumRequests;
  private final Duration window;
  private final Map<String, Window> windows = new HashMap<>();

  DeviceRequestRateLimiter(int maximumRequests, Duration window) {
    if (maximumRequests < 1 || window.isZero() || window.isNegative()) {
      throw new IllegalArgumentException("A política de limite de requisições é inválida.");
    }
    this.maximumRequests = maximumRequests;
    this.window = window;
  }

  synchronized boolean allow(String key, Instant now) {
    if (key == null || key.isBlank()) return false;
    Window current = windows.get(key);
    if (current == null || !now.isBefore(current.startedAt().plus(window))) {
      removeExpired(now);
      if (current == null && windows.size() >= MAX_TRACKED_KEYS) return false;
      windows.put(key, new Window(now, 1));
      return true;
    }
    if (current.requestCount() >= maximumRequests) return false;
    windows.put(key, new Window(current.startedAt(), current.requestCount() + 1));
    return true;
  }

  private void removeExpired(Instant now) {
    windows.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().startedAt().plus(window)));
  }

  private record Window(Instant startedAt, int requestCount) {}
}
