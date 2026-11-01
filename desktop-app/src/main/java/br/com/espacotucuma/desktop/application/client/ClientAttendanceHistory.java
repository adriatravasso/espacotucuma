package br.com.espacotucuma.desktop.application.client;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClientAttendanceHistory(
    UUID attendanceId, Instant startedAt, String status, String services, long totalCents) {
  public ClientAttendanceHistory {
    Objects.requireNonNull(attendanceId, "attendanceId");
    Objects.requireNonNull(startedAt, "startedAt");
    status = Objects.requireNonNull(status, "status");
    services = Objects.requireNonNull(services, "services");
    if (totalCents < 0) throw new IllegalArgumentException("O total não pode ser negativo.");
  }
}
