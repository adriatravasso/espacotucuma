package br.com.espacotucuma.desktop.application.finance;

import java.time.Instant;
import java.util.UUID;

public record CashTurn(
    UUID id,
    UUID operatorId,
    boolean open,
    Instant openedAt,
    Instant closedAt,
    long openingCents,
    Long closingCents) {}
