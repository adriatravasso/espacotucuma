package br.com.espacotucuma.desktop.application.reports;

import java.util.List;

public record CommercialReportPage(List<CommercialReportRow> rows, long totalRows, int page) {
  public CommercialReportPage {
    rows = List.copyOf(rows);
    if (totalRows < 0 || page < 0) throw new IllegalArgumentException("Página inválida.");
  }
}
