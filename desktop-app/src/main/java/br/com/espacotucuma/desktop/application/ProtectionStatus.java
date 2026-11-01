package br.com.espacotucuma.desktop.application;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Local view of synchronization and recovery evidence for the current database. */
public record ProtectionStatus(
    String userName,
    Path databaseFile,
    int pendingOperations,
    long synchronizationGeneration,
    Instant lastSynchronizationAt,
    long backupSynchronizationGeneration,
    String validatedBackupId,
    Instant lastValidatedBackupAt) {

  public ProtectionStatus {
    Objects.requireNonNull(userName, "userName");
    Objects.requireNonNull(databaseFile, "databaseFile");
    if (pendingOperations < 0
        || synchronizationGeneration < 0
        || backupSynchronizationGeneration < 0) {
      throw new IllegalArgumentException("Os contadores de proteção não podem ser negativos.");
    }
    if ((validatedBackupId == null) != (lastValidatedBackupAt == null)) {
      throw new IllegalArgumentException("O backup validado exige identificador e horário.");
    }
  }

  public boolean backupCompletedForCurrentState() {
    return pendingOperations == 0
        && synchronizationGeneration > 0
        && backupSynchronizationGeneration == synchronizationGeneration
        && validatedBackupId != null;
  }

  public String protectionMessage() {
    if (pendingOperations > 0 || synchronizationGeneration == 0) {
      return "Somente neste computador";
    }
    if (!backupCompletedForCurrentState()) {
      return "Dados sincronizados; backup pendente";
    }
    return "Backup concluído";
  }
}
