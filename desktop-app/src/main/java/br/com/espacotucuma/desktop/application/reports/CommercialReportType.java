package br.com.espacotucuma.desktop.application.reports;

public enum CommercialReportType {
  DAILY_AGENDA("Agenda do dia"),
  ABSENCES("Faltas"),
  RECURRING_CLIENTS("Clientes recorrentes"),
  ATTENDANCES_BY_PROFESSIONAL("Atendimentos por profissional"),
  SERVICES_PERFORMED("Serviços realizados"),
  REVENUE("Faturamento"),
  RECEIPTS_BY_METHOD("Recebimentos por forma"),
  EXPENSES("Despesas");

  private final String label;

  CommercialReportType(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
