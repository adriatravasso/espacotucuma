package br.com.espacotucuma.sync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeviceRequestRateLimiterTest {
  @Test
  void limitsRequestsPerDeviceAndAllowsRequestsAfterWindowExpires() {
    DeviceRequestRateLimiter limiter = new DeviceRequestRateLimiter(2, Duration.ofMinutes(1));
    Instant start = Instant.parse("2026-11-01T12:00:00Z");

    assertTrue(limiter.allow("device-a", start));
    assertTrue(limiter.allow("device-a", start.plusSeconds(1)));
    assertFalse(limiter.allow("device-a", start.plusSeconds(2)));
    assertTrue(limiter.allow("device-b", start.plusSeconds(2)));
    assertTrue(limiter.allow("device-a", start.plusSeconds(60)));
  }

  @Test
  void deniesInvalidKeysAndDoesNotAllowOverlappingWindowsToResetQuota() {
    DeviceRequestRateLimiter limiter = new DeviceRequestRateLimiter(1, Duration.ofMinutes(1));
    Instant start = Instant.parse("2026-11-01T12:00:00Z");

    assertFalse(limiter.allow(" ", start));
    assertTrue(limiter.allow("device-a", start));
    assertFalse(limiter.allow("device-a", start.plusSeconds(59)));
    assertTrue(limiter.allow("device-a", start.plusSeconds(60)));
  }
}
