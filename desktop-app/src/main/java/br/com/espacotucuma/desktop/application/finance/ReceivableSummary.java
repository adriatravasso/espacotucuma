package br.com.espacotucuma.desktop.application.finance;

import br.com.espacotucuma.desktop.domain.money.Money;
import java.util.Objects;

public record ReceivableSummary(Money total, Money received, Money outstanding) {
  public ReceivableSummary {
    Objects.requireNonNull(total, "total");
    Objects.requireNonNull(received, "received");
    Objects.requireNonNull(outstanding, "outstanding");
    if (total.amount().signum() < 0
        || received.amount().signum() < 0
        || outstanding.amount().signum() < 0
        || !total.equals(received.add(outstanding))) {
      throw new IllegalArgumentException("O resumo do recebível está inconsistente.");
    }
  }

  public boolean settled() {
    return outstanding.amount().signum() == 0;
  }
}
