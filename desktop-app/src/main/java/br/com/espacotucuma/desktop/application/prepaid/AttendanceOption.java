package br.com.espacotucuma.desktop.application.prepaid;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AttendanceOption(
    UUID id,
    UUID clientId,
    String clientName,
    String professionalName,
    String serviceName,
    Instant startedAt,
    String state) {
  public AttendanceOption {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(clientId, "clientId");
    Objects.requireNonNull(clientName, "clientName");
    Objects.requireNonNull(professionalName, "professionalName");
    Objects.requireNonNull(serviceName, "serviceName");
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(state, "state");
  }

  @Override
  public String toString() {
    return clientName + " — " + professionalName + " — " + serviceName;
  }
}
