package br.com.espacotucuma.sync.contract;

import java.util.UUID;

public record SyncStatusResponse(
    int contractVersion,
    UUID establishmentId,
    UUID deviceId,
    String serviceStatus,
    long checkpoint) {}
