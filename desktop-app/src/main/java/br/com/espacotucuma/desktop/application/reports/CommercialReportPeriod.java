package br.com.espacotucuma.desktop.application.reports;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

public record CommercialReportPeriod(LocalDate from, LocalDate through) {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Manaus");

  public CommercialReportPeriod {
    Objects.requireNonNull(from);
    Objects.requireNonNull(through);
    if (through.isBefore(from) || through.isAfter(from.plusYears(2))) {
      throw new IllegalArgumentException("Informe um período válido de até dois anos.");
    }
  }

  public String fromUtc() {
    return from.atStartOfDay(BUSINESS_ZONE).toInstant().toString();
  }

  public String untilUtc() {
    return through.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant().toString();
  }
}
