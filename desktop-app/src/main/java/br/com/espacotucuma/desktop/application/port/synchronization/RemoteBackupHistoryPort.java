package br.com.espacotucuma.desktop.application.port.synchronization;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Lists remote recovery points after explicit operator request. */
public interface RemoteBackupHistoryPort {
  boolean canListBackups();

  List<RemoteBackupSummary> listAvailableBackups();

  record RemoteBackupSummary(UUID backupId, long checkpoint, Instant createdAt) {}
}
