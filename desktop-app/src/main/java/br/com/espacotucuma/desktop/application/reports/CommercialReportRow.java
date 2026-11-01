package br.com.espacotucuma.desktop.application.reports;

import java.time.Instant;

public record CommercialReportRow(
    Instant occurredAt,
    String title,
    String detail,
    String status,
    long quantity,
    long amountCents) {}
