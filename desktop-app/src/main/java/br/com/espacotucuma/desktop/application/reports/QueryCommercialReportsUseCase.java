package br.com.espacotucuma.desktop.application.reports;

import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.port.reports.CommercialReportRepository;
import java.util.Objects;

public final class QueryCommercialReportsUseCase {
  private final CommercialReportRepository reports;
  private final AuthorizationService authorization;

  public QueryCommercialReportsUseCase(
      CommercialReportRepository reports, AuthorizationService authorization) {
    this.reports = Objects.requireNonNull(reports);
    this.authorization = Objects.requireNonNull(authorization);
  }

  public CommercialReportPage find(
      CommercialReportType type, CommercialReportPeriod period, int page, int pageSize) {
    authorization.require(Permission.REPORTS_VIEW);
    if (page < 0 || pageSize < 1 || pageSize > 200) {
      throw new IllegalArgumentException("A paginação deve conter de 1 a 200 linhas.");
    }
    return reports.find(
        Objects.requireNonNull(type), Objects.requireNonNull(period), page, pageSize);
  }

  public CommercialReportSummary summarize(CommercialReportPeriod period) {
    authorization.require(Permission.REPORTS_VIEW);
    return reports.summarize(Objects.requireNonNull(period));
  }
}
