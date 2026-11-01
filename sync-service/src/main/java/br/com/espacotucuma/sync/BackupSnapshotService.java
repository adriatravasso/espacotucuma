package br.com.espacotucuma.sync;

import br.com.espacotucuma.sync.contract.BackupRequest;
import br.com.espacotucuma.sync.contract.BackupResponse;
import br.com.espacotucuma.sync.contract.SyncContract;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates a recoverable snapshot for exactly the latest committed remote checkpoint. */
@Service
class BackupSnapshotService {
  private static final int SNAPSHOT_FORMAT_VERSION = 1;
  private final JdbcTemplate jdbc;
  private final BackupObjectStorage storage;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactions;

  BackupSnapshotService(
      JdbcTemplate jdbc,
      BackupObjectStorage storage,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.storage = storage;
    this.objectMapper = objectMapper;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  BackupResponse create(UUID establishmentId, BackupRequest request) {
    UUID backupId = UUID.randomUUID();
    String key = "backups/" + establishmentId + "/" + backupId + ".json";
    transactions.executeWithoutResult(
        status -> reservePending(establishmentId, request.checkpoint(), backupId, key));

    return transactions.execute(
        status -> createAndValidate(establishmentId, request.checkpoint(), backupId, key));
  }

  private void reservePending(
      UUID establishmentId, long requestedCheckpoint, UUID backupId, String key) {
    lockEstablishment(establishmentId);
    if (requestedCheckpoint != currentCheckpoint(establishmentId)) {
      throw new IllegalArgumentException(
          "O checkpoint solicitado não corresponde ao estado remoto atual.");
    }
    jdbc.update(
        "INSERT INTO backup_remoto (id, estabelecimento_id, sequencia, chave_armazenamento, "
            + "checksum_sha256, versao_formato, estado) VALUES (?, ?, ?, ?, NULL, ?, 'PENDENTE')",
        backupId,
        establishmentId,
        requestedCheckpoint,
        key,
        SNAPSHOT_FORMAT_VERSION);
  }

  private BackupResponse createAndValidate(
      UUID establishmentId, long requestedCheckpoint, UUID backupId, String key) {
    lockEstablishment(establishmentId);
    if (requestedCheckpoint != currentCheckpoint(establishmentId)) {
      throw new IllegalArgumentException(
          "O checkpoint solicitado não corresponde ao estado remoto atual.");
    }

    Snapshot snapshot = readSnapshot(establishmentId, requestedCheckpoint);
    byte[] content = encode(snapshot);
    String checksum = sha256(content);
    storage.putPrivate(key, content);
    byte[] storedContent = storage.get(key);
    if (!MessageDigest.isEqual(content, storedContent) || !checksum.equals(sha256(storedContent))) {
      throw new IllegalStateException(
          "O snapshot armazenado não passou na verificação de integridade.");
    }
    Snapshot verified = decode(storedContent);
    if (verified.formatVersion() != SNAPSHOT_FORMAT_VERSION
        || verified.checkpoint() != requestedCheckpoint
        || verified.manifest().entityCount() != snapshot.manifest().entityCount()
        || verified.manifest().reconciliationCount() != snapshot.manifest().reconciliationCount()
        || !verified.manifest().entitiesSha256().equals(snapshot.manifest().entitiesSha256())
        || !verified
            .manifest()
            .reconciliationsSha256()
            .equals(snapshot.manifest().reconciliationsSha256())) {
      throw new IllegalStateException("O snapshot armazenado não corresponde ao manifesto gerado.");
    }

    int updated =
        jdbc.update(
            "UPDATE backup_remoto SET checksum_sha256 = ?, estado = 'VALIDADO', validado_em = now() "
                + "WHERE id = ? AND estabelecimento_id = ? AND estado = 'PENDENTE'",
            checksum,
            backupId,
            establishmentId);
    if (updated != 1) throw new IllegalStateException("O registro pendente de backup desapareceu.");
    Instant createdAt =
        jdbc.queryForObject(
            "SELECT criado_em FROM backup_remoto WHERE id = ?",
            (rs, row) -> rs.getTimestamp(1).toInstant(),
            backupId);
    return new BackupResponse(
        SyncContract.VERSION, backupId, requestedCheckpoint, "VALIDADO", createdAt);
  }

  private void lockEstablishment(UUID establishmentId) {
    List<String> locked =
        jdbc.query(
            "SELECT id::text FROM estabelecimento_remoto WHERE id = ? FOR UPDATE",
            (rs, row) -> rs.getString(1),
            establishmentId);
    if (locked.isEmpty()) throw new DeviceAuthorizationException();
  }

  private long currentCheckpoint(UUID establishmentId) {
    return jdbc.queryForObject(
        "SELECT COALESCE(MAX(sequencia), 0) FROM operacao_recebida WHERE estabelecimento_id = ?",
        Long.class,
        establishmentId);
  }

  boolean verify(UUID establishmentId, UUID backupId) {
    List<StoredBackup> backups =
        jdbc.query(
            "SELECT sequencia, chave_armazenamento, checksum_sha256, versao_formato "
                + "FROM backup_remoto WHERE id = ? AND estabelecimento_id = ? AND estado = 'VALIDADO'",
            (rs, row) ->
                new StoredBackup(
                    rs.getLong("sequencia"),
                    rs.getString("chave_armazenamento"),
                    rs.getString("checksum_sha256").trim(),
                    rs.getInt("versao_formato")),
            backupId,
            establishmentId);
    if (backups.isEmpty()) return false;
    StoredBackup backup = backups.getFirst();
    byte[] content = storage.get(backup.key());
    if (!backup.checksum().equals(sha256(content))) return false;
    Snapshot snapshot = decode(content);
    return snapshot.formatVersion() == backup.formatVersion()
        && snapshot.schemaVersion() > 0
        && snapshot.checkpoint() == backup.checkpoint()
        && snapshot.establishmentId().equals(establishmentId)
        && snapshot.manifest().entityCount() == snapshot.entities().size()
        && snapshot.manifest().reconciliationCount() == snapshot.reconciliations().size()
        && snapshot.manifest().entitiesSha256().equals(sha256(encode(snapshot.entities())))
        && snapshot
            .manifest()
            .reconciliationsSha256()
            .equals(sha256(encode(snapshot.reconciliations())));
  }

  private Snapshot readSnapshot(UUID establishmentId, long checkpoint) {
    List<EntityState> entities =
        jdbc.query(
            "SELECT entidade, entidade_id::text, versao, payload::text, ultima_operacao_id::text, atualizada_em "
                + "FROM entidade_remota_sincronizada WHERE estabelecimento_id = ? "
                + "ORDER BY entidade, entidade_id",
            (rs, row) ->
                new EntityState(
                    rs.getString(1),
                    UUID.fromString(rs.getString(2)),
                    rs.getLong(3),
                    readJson(rs.getString(4)),
                    UUID.fromString(rs.getString(5)),
                    rs.getTimestamp(6).toInstant()),
            establishmentId);
    List<ReconciliationState> reconciliations =
        jdbc.query(
            "SELECT id::text, dispositivo_id::text, operacao_id::text, codigo, estado, detalhes::text, "
                + "criado_em, resolvido_em FROM estado_reconciliado WHERE estabelecimento_id = ? "
                + "ORDER BY criado_em, id",
            (rs, row) ->
                new ReconciliationState(
                    UUID.fromString(rs.getString(1)),
                    UUID.fromString(rs.getString(2)),
                    rs.getString(3) == null ? null : UUID.fromString(rs.getString(3)),
                    rs.getString(4),
                    rs.getString(5),
                    readJson(rs.getString(6)),
                    rs.getTimestamp(7).toInstant(),
                    rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()),
            establishmentId);
    Integer schemaVersion =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(version::integer), 0) FROM flyway_schema_history "
                + "WHERE success = true AND version ~ '^[0-9]+$'",
            Integer.class);
    return new Snapshot(
        SNAPSHOT_FORMAT_VERSION,
        schemaVersion,
        establishmentId,
        checkpoint,
        Instant.now(),
        new Manifest(
            entities.size(),
            sha256(encode(entities)),
            reconciliations.size(),
            sha256(encode(reconciliations))),
        entities,
        reconciliations);
  }

  private byte[] encode(Object value) {
    try {
      return objectMapper.writeValueAsBytes(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Snapshot não pôde ser serializado.", exception);
    }
  }

  private JsonNode readJson(String value) {
    try {
      return objectMapper.readTree(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("JSON persistido para o snapshot é inválido.", exception);
    }
  }

  private Snapshot decode(byte[] content) {
    try {
      return objectMapper.readValue(content, Snapshot.class);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("Snapshot armazenado possui formato inválido.", exception);
    }
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 indisponível.", exception);
    }
  }

  record Snapshot(
      int formatVersion,
      int schemaVersion,
      UUID establishmentId,
      long checkpoint,
      Instant createdAt,
      Manifest manifest,
      List<EntityState> entities,
      List<ReconciliationState> reconciliations) {}

  record Manifest(
      int entityCount,
      String entitiesSha256,
      int reconciliationCount,
      String reconciliationsSha256) {}

  record EntityState(
      String entity,
      UUID entityId,
      long version,
      JsonNode payload,
      UUID lastOperationId,
      Instant updatedAt) {}

  record ReconciliationState(
      UUID id,
      UUID deviceId,
      UUID operationId,
      String code,
      String state,
      JsonNode details,
      Instant createdAt,
      Instant resolvedAt) {}

  private record StoredBackup(long checkpoint, String key, String checksum, int formatVersion) {}
}
