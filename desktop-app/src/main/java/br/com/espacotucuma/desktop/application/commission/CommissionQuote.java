package br.com.espacotucuma.desktop.application.commission;

import br.com.espacotucuma.desktop.domain.money.Money;
import java.util.Objects;

public record CommissionQuote(CommissionDecision decision, Money commission) {
  public CommissionQuote {
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(commission, "commission");
  }
}
