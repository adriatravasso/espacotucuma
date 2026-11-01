package br.com.espacotucuma.desktop.application.reports;

public enum CommercialReportType {
  DAILY_AGENDA("Agenda do dia"),
  ABSENCES("Faltas"),
  RECURRING_CLIENTS("Clientes recorrentes"),
  ATTENDANCES_BY_PROFESSIONAL("Atendimentos por profissional"),
  SERVICES_PERFORMED("Serviços realizados"),
  REVENUE("Faturamento"),
  RECEIPTS_BY_METHOD("Recebimentos por forma"),
  EXPENSES("Despesas"),
  STOCK_POSITION("Posição de estoque"),
  LOW_STOCK("Produtos abaixo do mínimo"),
  STOCK_CONSUMPTION("Consumo de materiais"),
  COMMISSION_STATEMENTS("Extratos de comissões"),
  CASH_CLOSINGS("Fechamentos de caixa");

  private final String label;

  CommercialReportType(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  @Override
  public String toString() {
    return label;
  }
}
