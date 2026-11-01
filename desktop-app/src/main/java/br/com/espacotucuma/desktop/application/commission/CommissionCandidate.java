package br.com.espacotucuma.desktop.application.commission;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CommissionCandidate(
    UUID serviceLineId,
    UUID attendanceId,
    UUID professionalId,
    String professionalName,
    String serviceName,
    Instant completedAt,
    long grossCents,
    long attendanceDiscountCents,
    int percentageBasisPoints) {
  public CommissionCandidate {
    Objects.requireNonNull(serviceLineId);
    Objects.requireNonNull(attendanceId);
    Objects.requireNonNull(professionalId);
    professionalName = Objects.requireNonNull(professionalName).strip();
    serviceName = Objects.requireNonNull(serviceName).strip();
    Objects.requireNonNull(completedAt);
    if (grossCents < 0
        || attendanceDiscountCents < 0
        || percentageBasisPoints < 0
        || percentageBasisPoints > 10_000)
      throw new IllegalArgumentException("Os dados da comissão são inválidos.");
  }
}
