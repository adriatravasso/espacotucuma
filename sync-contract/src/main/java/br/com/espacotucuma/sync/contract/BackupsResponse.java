package br.com.espacotucuma.sync.contract;

import java.util.List;

public record BackupsResponse(int contractVersion, List<BackupResponse> backups) {}
