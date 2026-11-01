package br.com.espacotucuma.desktop.application.prepaid;

import br.com.espacotucuma.desktop.domain.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record SessionBalance(
    UUID id,
    UUID clientId,
    String clientName,
    UUID packageId,
    String packageName,
    int sessionsContracted,
    int sessionsRemaining,
    LocalDate expiresOn,
    Money paidPrice,
    PaymentForm paymentForm,
    BalanceState state,
    Instant purchasedAt,
    UUID purchasedBy) {
  public SessionBalance {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(clientId, "clientId");
    clientName = Objects.requireNonNull(clientName, "clientName");
    Objects.requireNonNull(packageId, "packageId");
    packageName = Objects.requireNonNull(packageName, "packageName");
    if (sessionsContracted < 1 || sessionsRemaining < 0 || sessionsRemaining > sessionsContracted) {
      throw new IllegalArgumentException("O saldo do pacote é inválido.");
    }
    Objects.requireNonNull(expiresOn, "expiresOn");
    Objects.requireNonNull(paidPrice, "paidPrice");
    Objects.requireNonNull(paymentForm, "paymentForm");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(purchasedAt, "purchasedAt");
    Objects.requireNonNull(purchasedBy, "purchasedBy");
    if (state == BalanceState.ESGOTADO && sessionsRemaining != 0) {
      throw new IllegalArgumentException("Pacote esgotado deve estar com saldo zero.");
    }
  }
}
