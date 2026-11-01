package br.com.espacotucuma.desktop.infrastructure.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.ProtectionStatus;
import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteProtectionStatusRepositoryTest {

  @Test
  void pendingCountIsReadFromTheLocalOutbox(@TempDir Path temp) throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("pending.sqlite"))) {
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      SqliteOutboxRepository outbox = new SqliteOutboxRepository(database.connection(), unitOfWork);
      unitOfWork.execute(
          () -> {
            outbox.enqueue(
                new PendingOperation(
                    UUID.randomUUID(),
                    "cliente",
                    UUID.randomUUID(),
                    "CRIADO",
                    "synthetic-idempotency-key",
                    "{}",
                    1,
                    0,
                    UUID.randomUUID(),
                    Instant.parse("2026-10-31T15:00:00Z"),
                    0));
            return null;
          });

      ProtectionStatus status =
          new SqliteProtectionStatusRepository(database.connection(), unitOfWork)
              .load("Recepção", database.databaseFile());
      assertEquals(1, status.pendingOperations());
      assertEquals("Somente neste computador", status.protectionMessage());
    }
  }

  @Test
  void synchronizationAloneNeverClaimsBackupAndBackupMustBeExplicitlyValidated(@TempDir Path temp)
      throws Exception {
    Path databaseFile = temp.resolve("status.sqlite");
    try (LocalDatabaseSession database = LocalDatabaseSession.open(databaseFile)) {
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      SqliteProtectionStatusRepository repository =
          new SqliteProtectionStatusRepository(database.connection(), unitOfWork);
      Instant synchronizedAt = Instant.parse("2026-10-31T15:00:00Z");

      assertThrows(
          IllegalStateException.class,
          () -> repository.recordValidatedBackup("backup-x", synchronizedAt));
      unitOfWork.execute(
          () -> {
            repository.recordSynchronization(synchronizedAt);
            return null;
          });

      ProtectionStatus afterEventsOnly = repository.load("Recepção", database.databaseFile());
      assertEquals("Dados sincronizados; backup pendente", afterEventsOnly.protectionMessage());
      assertFalse(afterEventsOnly.backupCompletedForCurrentState());
      assertEquals(synchronizedAt, afterEventsOnly.lastSynchronizationAt());
      assertEquals(null, afterEventsOnly.lastValidatedBackupAt());

      Instant backupAt = synchronizedAt.plusSeconds(30);
      unitOfWork.execute(
          () -> {
            repository.recordValidatedBackup("backup-validado-1", backupAt);
            return null;
          });
      assertTrue(
          repository.load("Recepção", database.databaseFile()).backupCompletedForCurrentState());
      assertEquals(
          "backup-validado-1",
          repository.load("Recepção", database.databaseFile()).validatedBackupId());

      unitOfWork.execute(
          () -> {
            repository.recordSynchronization(backupAt.plusSeconds(30));
            return null;
          });
      assertEquals(
          "Dados sincronizados; backup pendente",
          repository.load("Recepção", database.databaseFile()).protectionMessage());
      assertFalse(
          repository.load("Recepção", database.databaseFile()).backupCompletedForCurrentState());
    }

    try (LocalDatabaseSession reopened = LocalDatabaseSession.open(databaseFile)) {
      ProtectionStatus persisted =
          new SqliteProtectionStatusRepository(
                  reopened.connection(), new SqliteUnitOfWork(reopened.connection()))
              .load("Recepção", reopened.databaseFile());
      assertEquals(2, persisted.synchronizationGeneration());
      assertEquals("backup-validado-1", persisted.validatedBackupId());
      assertEquals("Dados sincronizados; backup pendente", persisted.protectionMessage());
    }
  }
}
