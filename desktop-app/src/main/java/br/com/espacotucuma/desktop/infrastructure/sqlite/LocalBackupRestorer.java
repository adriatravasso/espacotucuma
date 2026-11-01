package br.com.espacotucuma.desktop.infrastructure.sqlite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Validates a remote recovery point, stages it in a migrated SQLite file, then swaps atomically.
 */
public final class LocalBackupRestorer {
  private static final int SNAPSHOT_FORMAT_VERSION = 1;
  private static final int MAX_SUPPORTED_SCHEMA_VERSION = 6;
  private static final int MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024;

  private final ObjectMapper objectMapper;

  public LocalBackupRestorer(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public RestoreResult restore(
      Path databaseFile,
      byte[] content,
      String expectedSha256,
      UUID expectedEstablishmentId,
      UUID expectedBackupId,
      boolean explicitConfirmation)
      throws IOException {
    Objects.requireNonNull(databaseFile, "databaseFile");
    if (!explicitConfirmation) {
      throw new IllegalStateException("A confirmação explícita da restauração é obrigatória.");
    }
    Snapshot snapshot =
        validate(content, expectedSha256, expectedEstablishmentId, expectedBackupId);
    Path target = databaseFile.toAbsolutePath().normalize();
    Path parent = target.getParent();
    if (parent == null) throw new IllegalArgumentException("O caminho do banco local é inválido.");
    Files.createDirectories(parent);
    if (Files.isSymbolicLink(target)) {
      throw new IllegalArgumentException(
          "O arquivo de banco local não pode ser um link simbólico.");
    }

    Path lockPath = target.resolveSibling(target.getFileName() + ".lock");
    try (FileChannel channel =
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      FileLock lock;
      try {
        lock = channel.tryLock();
      } catch (OverlappingFileLockException alreadyOpen) {
        throw new IOException(
            "Feche o Espaço Tucumã antes de restaurar o banco local.", alreadyOpen);
      }
      if (lock == null)
        throw new IOException("Feche o Espaço Tucumã antes de restaurar o banco local.");
      try (lock) {
        return restoreWhileLocked(target, parent, content, snapshot, expectedBackupId);
      }
    }
  }

  private RestoreResult restoreWhileLocked(
      Path target, Path parent, byte[] content, Snapshot snapshot, UUID expectedBackupId)
      throws IOException {
    Path staging = Files.createTempFile(parent, ".tucuma-restore-", ".sqlite");
    Path safetyCopy = null;
    boolean replacementStarted = false;
    try {
      if (Files.exists(target)) Files.copy(target, staging, StandardCopyOption.REPLACE_EXISTING);
      SQLiteSchemaMigrator.migrate(staging);
      applySnapshot(staging, snapshot, expectedBackupId);
      verifyStagedDatabase(staging, snapshot);

      if (Files.exists(target)) {
        safetyCopy =
            target.resolveSibling(
                target.getFileName()
                    + ".pre-restore-"
                    + Instant.now().toEpochMilli()
                    + "-"
                    + UUID.randomUUID().toString().substring(0, 8)
                    + ".sqlite");
        Files.copy(target, safetyCopy);
      }
      replacementStarted = true;
      replaceDatabase(staging, target);
      return new RestoreResult(
          expectedBackupId,
          snapshot.checkpoint(),
          snapshot.entities().size(),
          snapshot.reconciliations().size(),
          safetyCopy);
    } catch (IOException | RuntimeException failure) {
      if (replacementStarted && safetyCopy != null && Files.exists(safetyCopy)) {
        try {
          Files.copy(safetyCopy, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException restoreFailure) {
          failure.addSuppressed(restoreFailure);
        }
      }
      throw failure;
    } finally {
      Files.deleteIfExists(staging);
    }
  }

  private Snapshot validate(
      byte[] content, String expectedSha256, UUID expectedEstablishmentId, UUID expectedBackupId) {
    Objects.requireNonNull(expectedEstablishmentId, "expectedEstablishmentId");
    Objects.requireNonNull(expectedBackupId, "expectedBackupId");
    if (content == null || content.length == 0 || content.length > MAX_DOWNLOAD_BYTES) {
      throw new IllegalArgumentException("O arquivo de recuperação está vazio ou excede 64 MiB.");
    }
    if (expectedSha256 == null
        || !expectedSha256.matches("[0-9a-f]{64}")
        || !expectedSha256.equals(sha256(content))) {
      throw new IllegalArgumentException("O checksum do arquivo de recuperação não confere.");
    }
    try {
      Snapshot snapshot = objectMapper.readValue(content, Snapshot.class);
      if (snapshot.formatVersion() != SNAPSHOT_FORMAT_VERSION
          || snapshot.schemaVersion() < 1
          || snapshot.schemaVersion() > MAX_SUPPORTED_SCHEMA_VERSION
          || !expectedEstablishmentId.equals(snapshot.establishmentId())
          || snapshot.checkpoint() < 0
          || snapshot.entities() == null
          || snapshot.reconciliations() == null
          || snapshot.manifest() == null
          || snapshot.manifest().entityCount() != snapshot.entities().size()
          || snapshot.manifest().reconciliationCount() != snapshot.reconciliations().size()
          || !snapshot.manifest().entitiesSha256().equals(sha256(encode(snapshot.entities())))
          || !snapshot
              .manifest()
              .reconciliationsSha256()
              .equals(sha256(encode(snapshot.reconciliations())))) {
        throw new IllegalArgumentException(
            "O formato, esquema ou manifesto do backup não é compatível.");
      }
      for (EntityState entity : snapshot.entities()) {
        if (entity == null
            || entity.entity() == null
            || entity.entity().isBlank()
            || entity.entityId() == null
            || entity.version() < 1
            || entity.payload() == null
            || entity.lastOperationId() == null
            || !validInstant(entity.updatedAt())) {
          throw new IllegalArgumentException("O backup contém uma entidade inválida.");
        }
      }
      for (ReconciliationState reconciliation : snapshot.reconciliations()) {
        if (reconciliation == null
            || reconciliation.id() == null
            || reconciliation.deviceId() == null
            || reconciliation.code() == null
            || reconciliation.code().isBlank()
            || !List.of("ABERTO", "RESOLVIDO").contains(reconciliation.state())
            || reconciliation.details() == null
            || !validInstant(reconciliation.createdAt())
            || (reconciliation.resolvedAt() != null
                && !validInstant(reconciliation.resolvedAt()))) {
          throw new IllegalArgumentException("O backup contém um registro de histórico inválido.");
        }
      }
      return snapshot;
    } catch (IOException failure) {
      throw new IllegalArgumentException(
          "O arquivo de recuperação não é um snapshot válido.", failure);
    }
  }

  private void applySnapshot(Path staging, Snapshot snapshot, UUID backupId) throws IOException {
    try (var connection = SQLiteSchemaMigrator.dataSource(staging).getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.executeUpdate("DELETE FROM estado_entidade_restaurada");
        statement.executeUpdate("DELETE FROM reconciliacao_restaurada");
        statement.executeUpdate("DELETE FROM ponto_recuperacao_aplicado");
      }
      try (var entityInsert =
              connection.prepareStatement(
                  "INSERT INTO estado_entidade_restaurada "
                      + "(entidade, entidade_id, versao_remota, payload_json, ultima_operacao_id, atualizada_em_utc) "
                      + "VALUES (?, ?, ?, ?, ?, ?)");
          var reconciliationInsert =
              connection.prepareStatement(
                  "INSERT INTO reconciliacao_restaurada "
                      + "(id, dispositivo_id, operacao_id, codigo, estado, detalhes_json, "
                      + "criado_em_utc, resolvido_em_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
          var pointInsert =
              connection.prepareStatement(
                  "INSERT INTO ponto_recuperacao_aplicado "
                      + "(id, estabelecimento_id, backup_id, checkpoint_remoto, schema_version, restaurado_em_utc) "
                      + "VALUES (1, ?, ?, ?, ?, ?)")) {
        for (EntityState entity : snapshot.entities()) {
          entityInsert.setString(1, entity.entity());
          entityInsert.setString(2, entity.entityId().toString());
          entityInsert.setLong(3, entity.version());
          entityInsert.setString(4, objectMapper.writeValueAsString(entity.payload()));
          entityInsert.setString(5, entity.lastOperationId().toString());
          entityInsert.setString(6, utc(entity.updatedAt()));
          entityInsert.addBatch();
        }
        entityInsert.executeBatch();
        for (ReconciliationState row : snapshot.reconciliations()) {
          reconciliationInsert.setString(1, row.id().toString());
          reconciliationInsert.setString(2, row.deviceId().toString());
          reconciliationInsert.setString(
              3, row.operationId() == null ? null : row.operationId().toString());
          reconciliationInsert.setString(4, row.code());
          reconciliationInsert.setString(5, row.state());
          reconciliationInsert.setString(6, objectMapper.writeValueAsString(row.details()));
          reconciliationInsert.setString(7, utc(row.createdAt()));
          reconciliationInsert.setString(
              8, row.resolvedAt() == null ? null : utc(row.resolvedAt()));
          reconciliationInsert.addBatch();
        }
        reconciliationInsert.executeBatch();
        pointInsert.setString(1, snapshot.establishmentId().toString());
        pointInsert.setString(2, backupId.toString());
        pointInsert.setLong(3, snapshot.checkpoint());
        pointInsert.setInt(4, snapshot.schemaVersion());
        pointInsert.setString(5, utc(Instant.now()));
        pointInsert.executeUpdate();
        try (var checkpoint =
            connection.prepareStatement(
                "INSERT INTO checkpoint_sincronizacao (estacao, checkpoint_remoto) "
                    + "VALUES ('principal', ?) ON CONFLICT(estacao) DO UPDATE SET "
                    + "checkpoint_remoto = excluded.checkpoint_remoto")) {
          checkpoint.setString(1, Long.toString(snapshot.checkpoint()));
          checkpoint.executeUpdate();
        }
        connection.commit();
      } catch (Exception failure) {
        connection.rollback();
        if (failure instanceof IOException ioFailure) throw ioFailure;
        throw new IOException("Não foi possível gravar o snapshot na base de estágio.", failure);
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (java.sql.SQLException failure) {
      throw new IOException("Não foi possível acessar a base SQLite de estágio.", failure);
    }
  }

  private void verifyStagedDatabase(Path staging, Snapshot snapshot) throws IOException {
    try (LocalDatabaseSession session = LocalDatabaseSession.open(staging)) {
      try (var statement = session.connection().createStatement()) {
        try (var result =
            statement.executeQuery("SELECT COUNT(*) FROM estado_entidade_restaurada")) {
          if (!result.next() || result.getInt(1) != snapshot.entities().size()) {
            throw new IOException("A quantidade de entidades recuperadas não confere.");
          }
        }
        try (var result = statement.executeQuery("SELECT COUNT(*) FROM reconciliacao_restaurada")) {
          if (!result.next() || result.getInt(1) != snapshot.reconciliations().size()) {
            throw new IOException(
                "A quantidade de registros de histórico recuperados não confere.");
          }
        }
      } catch (java.sql.SQLException failure) {
        throw new IOException("A validação pós-restauração do banco falhou.", failure);
      }
    } catch (java.sql.SQLException failure) {
      throw new IOException("O banco de estágio não pôde ser aberto para validação.", failure);
    }
  }

  private static void replaceDatabase(Path staging, Path target) throws IOException {
    Files.copy(staging, target, StandardCopyOption.REPLACE_EXISTING);
  }

  private byte[] encode(Object value) throws IOException {
    return objectMapper.writeValueAsBytes(value);
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 indisponível.", failure);
    }
  }

  private static String utc(Instant value) {
    return java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'")
        .withZone(java.time.ZoneOffset.UTC)
        .format(value.truncatedTo(ChronoUnit.SECONDS));
  }

  private static String utc(String value) {
    return utc(Instant.parse(value));
  }

  private static boolean validInstant(String value) {
    if (value == null) return false;
    try {
      Instant.parse(value);
      return true;
    } catch (java.time.DateTimeException invalid) {
      return false;
    }
  }

  public record RestoreResult(
      UUID backupId,
      long checkpoint,
      int restoredEntityCount,
      int restoredReconciliationCount,
      Path safetyCopy) {}

  private record Snapshot(
      int formatVersion,
      int schemaVersion,
      UUID establishmentId,
      long checkpoint,
      String createdAt,
      Manifest manifest,
      List<EntityState> entities,
      List<ReconciliationState> reconciliations) {}

  private record Manifest(
      int entityCount,
      String entitiesSha256,
      int reconciliationCount,
      String reconciliationsSha256) {}

  private record EntityState(
      String entity,
      UUID entityId,
      long version,
      JsonNode payload,
      UUID lastOperationId,
      String updatedAt) {}

  private record ReconciliationState(
      UUID id,
      UUID deviceId,
      UUID operationId,
      String code,
      String state,
      JsonNode details,
      String createdAt,
      String resolvedAt) {}
}
