package br.com.espacotucuma.sync.contract;

import java.time.Instant;
import java.util.UUID;

public record RestoreResponse(
    int contractVersion,
    UUID backupId,
    String status,
    String downloadAuthorization,
    Instant expiresAt,
    String checksumSha256,
    int schemaVersion) {}
