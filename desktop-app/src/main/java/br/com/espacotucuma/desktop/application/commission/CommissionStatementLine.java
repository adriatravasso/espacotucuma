package br.com.espacotucuma.desktop.application.commission;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CommissionStatementLine(
    UUID commissionId,
    UUID serviceLineId,
    UUID attendanceId,
    UUID professionalId,
    String professionalName,
    String serviceName,
    Instant completedAt,
    long grossCents,
    long discountCents,
    long commissionBaseCents,
    int percentageBasisPoints,
    long commissionCents,
    String status) {
  public CommissionStatementLine {
    Objects.requireNonNull(commissionId);
    Objects.requireNonNull(serviceLineId);
    Objects.requireNonNull(attendanceId);
    Objects.requireNonNull(professionalId);
    professionalName = Objects.requireNonNull(professionalName).strip();
    serviceName = Objects.requireNonNull(serviceName).strip();
    Objects.requireNonNull(completedAt);
    status = Objects.requireNonNull(status).strip();
  }
}
