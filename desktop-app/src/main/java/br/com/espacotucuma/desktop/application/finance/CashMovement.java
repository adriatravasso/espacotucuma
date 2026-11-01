package br.com.espacotucuma.desktop.application.finance;

import java.time.Instant;
import java.util.UUID;

public record CashMovement(
    UUID id,
    UUID turnId,
    UUID receiptId,
    CashMovementType type,
    long amountCents,
    String reason,
    UUID createdBy,
    Instant createdAt) {}
