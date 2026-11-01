package br.com.espacotucuma.desktop.application.prepaid;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PackageUsage(
    UUID id,
    UUID attendanceId,
    String attendanceState,
    int sessionsUsed,
    Instant usedAt,
    boolean reversed,
    String reversalReason) {
  public PackageUsage {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(attendanceId, "attendanceId");
    Objects.requireNonNull(attendanceState, "attendanceState");
    if (sessionsUsed < 1)
      throw new IllegalArgumentException("A quantidade usada deve ser positiva.");
    Objects.requireNonNull(usedAt, "usedAt");
    if (reversed) reversalReason = Objects.requireNonNull(reversalReason, "reversalReason");
  }
}
