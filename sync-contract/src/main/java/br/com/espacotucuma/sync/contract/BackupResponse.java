package br.com.espacotucuma.sync.contract;

import java.time.Instant;
import java.util.UUID;

public record BackupResponse(
    int contractVersion, UUID backupId, long checkpoint, String status, Instant createdAt) {}
