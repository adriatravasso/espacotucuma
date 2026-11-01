package br.com.espacotucuma.sync.contract;

import java.util.UUID;

public record RestoreRequest(int contractVersion, UUID backupId) {}
