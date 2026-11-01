package br.com.espacotucuma.desktop.application.finance;

public record CashReconciliation(PaymentMethod method, long expectedCents, long countedCents) {
  public long differenceCents() {
    return countedCents - expectedCents;
  }
}
