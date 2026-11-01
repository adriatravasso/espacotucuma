package br.com.espacotucuma.desktop.application.commission;

import java.util.Objects;
import java.util.UUID;

public record CommissionDecision(int percentageBasisPoints, Source source, UUID ruleId) {
  public CommissionDecision {
    if (percentageBasisPoints < 0 || percentageBasisPoints > 10000) {
      throw new IllegalArgumentException("A comissão deve estar entre 0% e 100%.");
    }
    Objects.requireNonNull(source, "source");
    if ((source == Source.SERVICE) != (ruleId == null)) {
      throw new IllegalArgumentException("A origem e a regra da comissão são inconsistentes.");
    }
  }

  public enum Source {
    SERVICE,
    PROFESSIONAL_SERVICE
  }
}
