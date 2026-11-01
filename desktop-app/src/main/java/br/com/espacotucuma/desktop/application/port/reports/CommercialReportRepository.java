package br.com.espacotucuma.desktop.application.port.reports;

import br.com.espacotucuma.desktop.application.reports.CommercialReportPage;
import br.com.espacotucuma.desktop.application.reports.CommercialReportPeriod;
import br.com.espacotucuma.desktop.application.reports.CommercialReportSummary;
import br.com.espacotucuma.desktop.application.reports.CommercialReportType;

public interface CommercialReportRepository {
  CommercialReportPage find(
      CommercialReportType type, CommercialReportPeriod period, int page, int pageSize);

  CommercialReportPage find(
      CommercialReportType type,
      CommercialReportPeriod period,
      int page,
      int pageSize,
      String professionalFilter,
      String serviceFilter);

  CommercialReportSummary summarize(CommercialReportPeriod period);
}
