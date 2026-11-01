package br.com.espacotucuma.desktop.application.reports;

public record CommercialReportSummary(
    long revenueCents, long receivedCents, long pendingCents, long expensesCents) {
  public CommercialReportSummary {
    if (revenueCents < 0 || pendingCents < 0 || expensesCents < 0) {
      throw new IllegalArgumentException("O resumo financeiro não pode conter valores negativos.");
    }
  }
}
