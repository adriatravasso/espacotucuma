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
    return find(type, period, page, pageSize, "", "");
  }

  public CommercialReportPage find(
      CommercialReportType type,
      CommercialReportPeriod period,
      int page,
      int pageSize,
      String professionalFilter,
      String serviceFilter) {
    Objects.requireNonNull(type);
    switch (type) {
      case STOCK_POSITION, LOW_STOCK, STOCK_CONSUMPTION ->
          authorization.require(Permission.INVENTORY_READ);
      case COMMISSION_STATEMENTS -> authorization.require(Permission.COMMISSION_RULE_MANAGE);
      default -> authorization.require(Permission.REPORTS_VIEW);
    }
    if (page < 0 || pageSize < 1 || pageSize > 200) {
      throw new IllegalArgumentException("A paginação deve conter de 1 a 200 linhas.");
    }
    return reports.find(
        type,
        Objects.requireNonNull(period),
        page,
        pageSize,
        Objects.requireNonNull(professionalFilter).strip(),
        Objects.requireNonNull(serviceFilter).strip());
  }

  public CommercialReportSummary summarize(CommercialReportPeriod period) {
    authorization.require(Permission.REPORTS_VIEW);
    return reports.summarize(Objects.requireNonNull(period));
  }
}
