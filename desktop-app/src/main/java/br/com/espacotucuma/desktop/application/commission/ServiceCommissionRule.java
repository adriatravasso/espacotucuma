package br.com.espacotucuma.desktop.application.commission;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ServiceCommissionRule(
    UUID id,
    UUID professionalId,
    UUID serviceId,
    int percentageBasisPoints,
    boolean active,
    Instant createdAt,
    Instant updatedAt) {
  public ServiceCommissionRule {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(professionalId, "professionalId");
    Objects.requireNonNull(serviceId, "serviceId");
    if (percentageBasisPoints < 0 || percentageBasisPoints > 10000) {
      throw new IllegalArgumentException("A comissão deve estar entre 0% e 100%.");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("A atualização não pode anteceder o cadastro.");
    }
  }

  public CommissionDecision decision() {
    return new CommissionDecision(
        percentageBasisPoints, CommissionDecision.Source.PROFESSIONAL_SERVICE, id);
  }
}
