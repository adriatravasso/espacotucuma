package br.com.espacotucuma.desktop.infrastructure.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalBackupRestorerTest {
  private final ObjectMapper json = new ObjectMapper();
  private final LocalBackupRestorer restorer = new LocalBackupRestorer(json);

  @TempDir Path temporaryDirectory;

  @Test
  void restoresAndOpensMigratedDatabaseWithRecoveredBalancesAndHistory() throws Exception {
    Fixture fixture = fixture();
    Path database = temporaryDirectory.resolve("new-machine/tucuma.sqlite");

    var result =
        restorer.restore(
            database,
            fixture.content(),
            fixture.checksum(),
            fixture.establishmentId(),
            fixture.backupId(),
            true);

    assertEquals(fixture.backupId(), result.backupId());
    assertEquals(42, result.checkpoint());
    assertEquals(1, result.restoredEntityCount());
    assertEquals(1, result.restoredReconciliationCount());
    assertTrue(result.safetyCopy() == null);
    try (LocalDatabaseSession restored = LocalDatabaseSession.open(database)) {
      assertEquals(
          1,
          scalar(
              restored,
              "SELECT COUNT(*) FROM estado_entidade_restaurada WHERE entidade = 'saldo_sessoes'"));
      assertEquals(
          3,
          scalar(
              restored,
              "SELECT json_extract(payload_json, '$.sessoesRestantes') "
                  + "FROM estado_entidade_restaurada WHERE entidade = 'saldo_sessoes'"));
      assertEquals(1, scalar(restored, "SELECT COUNT(*) FROM reconciliacao_restaurada"));
      assertEquals(
          42,
          scalar(
              restored,
              "SELECT checkpoint_remoto FROM checkpoint_sincronizacao WHERE estacao = 'principal'"));
      assertEquals(
          fixture.backupId().toString(),
          text(restored, "SELECT backup_id FROM ponto_recuperacao_aplicado WHERE id = 1"));
    }
  }

  @Test
  void corruptIncompatibleOrUnconfirmedRestoreNeverReplacesExistingDatabase() throws Exception {
    Path database = temporaryDirectory.resolve("existing/tucuma.sqlite");
    Files.createDirectories(database.getParent());
    try (LocalDatabaseSession original = LocalDatabaseSession.open(database)) {
      try (var statement = original.connection().createStatement()) {
        statement.executeUpdate(
            "INSERT INTO pacote (id, nome, sessoes_contratadas, validade_dias, preco_centavos, "
                + "ativo, criado_em_utc, atualizado_em_utc) VALUES "
                + "('53f71f36-6157-4d6f-9d6e-2a4f1b7736b9', 'Pacote local', 5, 30, 100, 1, "
                + "'2026-11-01T00:00:00Z', '2026-11-01T00:00:00Z')");
      }
    }
    byte[] originalFile = Files.readAllBytes(database);
    Fixture fixture = fixture();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            restorer.restore(
                database,
                fixture.content(),
                "0".repeat(64),
                fixture.establishmentId(),
                fixture.backupId(),
                true));
    assertThrows(
        IllegalStateException.class,
        () ->
            restorer.restore(
                database,
                fixture.content(),
                fixture.checksum(),
                fixture.establishmentId(),
                fixture.backupId(),
                false));
    assertTrue(java.util.Arrays.equals(originalFile, Files.readAllBytes(database)));
    assertFalse(
        Files.list(database.getParent())
            .anyMatch(path -> path.getFileName().toString().contains("pre-restore")));

    var successfulRestore =
        restorer.restore(
            database,
            fixture.content(),
            fixture.checksum(),
            fixture.establishmentId(),
            fixture.backupId(),
            true);
    assertNotNull(successfulRestore.safetyCopy());
    assertTrue(Files.exists(successfulRestore.safetyCopy()));
    try (LocalDatabaseSession restored = LocalDatabaseSession.open(database)) {
      assertEquals(1, scalar(restored, "SELECT COUNT(*) FROM pacote WHERE nome = 'Pacote local'"));
      assertEquals(1, scalar(restored, "SELECT COUNT(*) FROM estado_entidade_restaurada"));
    }
  }

  @Test
  void interruptedStagingTransactionLeavesExistingDatabaseIntact() throws Exception {
    Path database = temporaryDirectory.resolve("interrupted/tucuma.sqlite");
    Files.createDirectories(database.getParent());
    try (LocalDatabaseSession original = LocalDatabaseSession.open(database)) {
      try (var statement = original.connection().createStatement()) {
        statement.executeUpdate(
            "INSERT INTO pacote (id, nome, sessoes_contratadas, validade_dias, preco_centavos, "
                + "ativo, criado_em_utc, atualizado_em_utc) VALUES "
                + "('53f71f36-6157-4d6f-9d6e-2a4f1b7736b9', 'Preservado', 5, 30, 100, 1, "
                + "'2026-11-01T00:00:00Z', '2026-11-01T00:00:00Z')");
      }
    }
    byte[] originalFile = Files.readAllBytes(database);
    Fixture invalid = fixtureWithEntityName("x".repeat(81));

    assertThrows(
        IOException.class,
        () ->
            restorer.restore(
                database,
                invalid.content(),
                invalid.checksum(),
                invalid.establishmentId(),
                invalid.backupId(),
                true));

    assertTrue(java.util.Arrays.equals(originalFile, Files.readAllBytes(database)));
    try (LocalDatabaseSession intact = LocalDatabaseSession.open(database)) {
      assertEquals(1, scalar(intact, "SELECT COUNT(*) FROM pacote WHERE nome = 'Preservado'"));
      assertEquals(0, scalar(intact, "SELECT COUNT(*) FROM ponto_recuperacao_aplicado"));
    }
  }

  private Fixture fixture() throws Exception {
    return fixtureWithEntityName("saldo_sessoes");
  }

  private Fixture fixtureWithEntityName(String entityName) throws Exception {
    UUID establishmentId = UUID.randomUUID();
    UUID backupId = UUID.randomUUID();
    UUID balanceId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID deviceId = UUID.randomUUID();
    Instant now = Instant.parse("2026-11-01T12:00:00Z");
    JsonNode balance =
        json.readTree(
            "{\"id\":\""
                + balanceId
                + "\",\"clienteId\":\""
                + UUID.randomUUID()
                + "\",\"pacoteId\":\""
                + UUID.randomUUID()
                + "\",\"sessoesContratadas\":5,\"sessoesRestantes\":3}");
    List<Entity> entities =
        List.of(new Entity(entityName, balanceId, 2, balance, operationId, now.toString()));
    JsonNode details = json.readTree("{\"resumo\":\"ajuste conferido\"}");
    List<Reconciliation> reconciliations =
        List.of(
            new Reconciliation(
                UUID.randomUUID(),
                deviceId,
                operationId,
                "VERSAO_DIVERGENTE",
                "RESOLVIDO",
                details,
                now.minusSeconds(60).toString(),
                now.toString()));
    Snapshot snapshot =
        new Snapshot(
            1,
            6,
            establishmentId,
            42,
            now.toString(),
            new Manifest(
                entities.size(), sha256(json.writeValueAsBytes(entities)),
                reconciliations.size(), sha256(json.writeValueAsBytes(reconciliations))),
            entities,
            reconciliations);
    byte[] content = json.writeValueAsBytes(snapshot);
    return new Fixture(establishmentId, backupId, content, sha256(content));
  }

  private static int scalar(LocalDatabaseSession session, String sql) throws Exception {
    try (var statement = session.connection().createStatement();
        var result = statement.executeQuery(sql)) {
      if (!result.next()) throw new AssertionError("A consulta não retornou resultado.");
      return result.getInt(1);
    }
  }

  private static String text(LocalDatabaseSession session, String sql) throws Exception {
    try (var statement = session.connection().createStatement();
        var result = statement.executeQuery(sql)) {
      if (!result.next()) throw new AssertionError("A consulta não retornou resultado.");
      return result.getString(1);
    }
  }

  private static String sha256(byte[] content) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
  }

  private record Fixture(UUID establishmentId, UUID backupId, byte[] content, String checksum) {}

  private record Snapshot(
      int formatVersion,
      int schemaVersion,
      UUID establishmentId,
      long checkpoint,
      String createdAt,
      Manifest manifest,
      List<Entity> entities,
      List<Reconciliation> reconciliations) {}

  private record Manifest(
      int entityCount,
      String entitiesSha256,
      int reconciliationCount,
      String reconciliationsSha256) {}

  private record Entity(
      String entity,
      UUID entityId,
      long version,
      JsonNode payload,
      UUID lastOperationId,
      String updatedAt) {}

  private record Reconciliation(
      UUID id,
      UUID deviceId,
      UUID operationId,
      String code,
      String state,
      JsonNode details,
      String createdAt,
      String resolvedAt) {}
}
