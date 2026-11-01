package br.com.espacotucuma.desktop.infrastructure.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.outbox.ServerConfirmation;
import br.com.espacotucuma.sync.contract.ChangesResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteRemoteChangeRepositoryTest {
  private static final UUID DEVICE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");

  @Test
  void checkpointAndReceivedChangesCommitTogetherAndUnexpectedOriginBecomesConflict(
      @TempDir Path temp) throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("changes.sqlite"))) {
      SqliteUnitOfWork unit = new SqliteUnitOfWork(database.connection());
      var repository =
          new SqliteRemoteChangeRepository(
              database.connection(), unit, new ObjectMapper(), DEVICE_ID);

      unit.execute(
          () -> {
            repository.applyPage(0, page(5, List.of(change(2), change(5))));
            return null;
          });

      assertEquals(5, repository.checkpoint());
      try (Statement statement = database.connection().createStatement();
          var result =
              statement.executeQuery(
                  "SELECT COUNT(*) AS total, "
                      + "SUM(CASE WHEN estado_local = 'CONFLITO' THEN 1 ELSE 0 END) AS conflicts "
                      + "FROM alteracao_remota_recebida")) {
        result.next();
        assertEquals(2, result.getInt("total"));
        assertEquals(2, result.getInt("conflicts"));
      }
    }
  }

  @Test
  void malformedOutOfOrderPageDoesNotPersistRowsOrAdvanceCursor(@TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("invalid.sqlite"))) {
      SqliteUnitOfWork unit = new SqliteUnitOfWork(database.connection());
      var repository =
          new SqliteRemoteChangeRepository(
              database.connection(), unit, new ObjectMapper(), DEVICE_ID);

      assertThrows(
          IllegalArgumentException.class,
          () ->
              unit.execute(
                  () -> {
                    repository.applyPage(0, page(2, List.of(change(2), change(1))));
                    return null;
                  }));

      assertEquals(0, repository.checkpoint());
      try (Statement statement = database.connection().createStatement();
          var result = statement.executeQuery("SELECT COUNT(*) FROM alteracao_remota_recebida")) {
        result.next();
        assertEquals(0, result.getInt(1));
      }
    }
  }

  @Test
  void versionDivergenceForLocallyConfirmedOperationIsRetainedAsConflict(@TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("version.sqlite"))) {
      SqliteUnitOfWork unit = new SqliteUnitOfWork(database.connection());
      SqliteOutboxRepository outbox = new SqliteOutboxRepository(database.connection(), unit);
      UUID operationId = UUID.fromString("60000000-0000-0000-0000-000000000001");
      UUID entityId = UUID.fromString("50000000-0000-0000-0000-000000000001");
      PendingOperation localOperation =
          new PendingOperation(
              operationId,
              "cliente",
              entityId,
              "CLIENTE_CRIADO",
              operationId.toString(),
              "{\"nome\":\"Local\"}",
              1,
              0,
              DEVICE_ID,
              Instant.parse("2026-11-01T12:00:00Z"),
              0);
      unit.execute(
          () -> {
            outbox.enqueue(localOperation);
            outbox.markSynchronized(
                new ServerConfirmation(operationId, 1, Instant.parse("2026-11-01T12:01:00Z")));
            return null;
          });
      var repository =
          new SqliteRemoteChangeRepository(
              database.connection(), unit, new ObjectMapper(), DEVICE_ID);
      var changedVersion =
          new ChangesResponse.RemoteChange(
              1,
              operationId,
              DEVICE_ID,
              "cliente",
              entityId,
              "CLIENTE_CRIADO",
              1,
              2,
              "ACEITA",
              Map.of("nome", "Local"));

      unit.execute(
          () -> {
            repository.applyPage(0, page(1, List.of(changedVersion)));
            return null;
          });

      try (Statement statement = database.connection().createStatement();
          var result =
              statement.executeQuery(
                  "SELECT estado_local, motivo_conflito FROM alteracao_remota_recebida")) {
        result.next();
        assertEquals("CONFLITO", result.getString("estado_local"));
        assertEquals("VERSAO_REMOTA_DIVERGENTE", result.getString("motivo_conflito"));
      }
      assertEquals(1, repository.checkpoint());
    }
  }

  private static ChangesResponse page(long checkpoint, List<ChangesResponse.RemoteChange> changes) {
    return new ChangesResponse(1, checkpoint, changes);
  }

  private static ChangesResponse.RemoteChange change(long sequence) {
    return new ChangesResponse.RemoteChange(
        sequence,
        UUID.nameUUIDFromBytes(
            ("operation-" + sequence).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        UUID.fromString("40000000-0000-0000-0000-000000000001"),
        "cliente",
        UUID.fromString("50000000-0000-0000-0000-000000000001"),
        "CLIENTE_CRIADO",
        1,
        1,
        "ACEITA",
        Map.of("nome", "Pessoa sintética"));
  }
}
