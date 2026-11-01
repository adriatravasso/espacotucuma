package br.com.espacotucuma.desktop.application.commission;

import br.com.espacotucuma.desktop.domain.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/** Commission precedence is professional + service override, then the service default. */
public final class CommissionCalculator {
  private CommissionCalculator() {}

  public static CommissionDecision resolve(
      int serviceDefaultBasisPoints, Optional<ServiceCommissionRule> override) {
    Objects.requireNonNull(override, "override");
    return override
        .filter(ServiceCommissionRule::active)
        .map(ServiceCommissionRule::decision)
        .orElseGet(
            () ->
                new CommissionDecision(
                    serviceDefaultBasisPoints, CommissionDecision.Source.SERVICE, null));
  }

  /** Discounts reduce the commission base; the final amount is rounded to the nearest cent. */
  public static Money calculate(Money serviceAmount, Money discount, CommissionDecision decision) {
    Objects.requireNonNull(serviceAmount, "serviceAmount");
    Objects.requireNonNull(discount, "discount");
    Objects.requireNonNull(decision, "decision");
    if (serviceAmount.amount().signum() < 0
        || discount.amount().signum() < 0
        || discount.amount().compareTo(serviceAmount.amount()) > 0) {
      throw new IllegalArgumentException("O desconto deve estar entre zero e o valor do serviço.");
    }
    BigDecimal netBase = serviceAmount.amount().subtract(discount.amount());
    return new Money(
        netBase
            .multiply(BigDecimal.valueOf(decision.percentageBasisPoints()))
            .divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP));
  }
}
