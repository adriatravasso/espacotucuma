package br.com.espacotucuma.sync;

import br.com.espacotucuma.sync.contract.BackupRequest;
import br.com.espacotucuma.sync.contract.BackupResponse;
import br.com.espacotucuma.sync.contract.BackupsResponse;
import br.com.espacotucuma.sync.contract.RestoreResponse;
import br.com.espacotucuma.sync.contract.SyncContract;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates a recoverable snapshot for exactly the latest committed remote checkpoint. */
@Service
class BackupSnapshotService {
  private static final int SNAPSHOT_FORMAT_VERSION = 1;
  private static final Logger LOGGER = LoggerFactory.getLogger(BackupSnapshotService.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Duration DOWNLOAD_AUTHORIZATION_LIFETIME = Duration.ofMinutes(10);
  private final JdbcTemplate jdbc;
  private final BackupObjectStorage storage;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactions;
  private final int retentionRecentCount;
  private final int retentionCoverageDays;

  BackupSnapshotService(
      JdbcTemplate jdbc,
      BackupObjectStorage storage,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager,
      @Value("${tucuma.backup.retention.recent-count:30}") int retentionRecentCount,
      @Value("${tucuma.backup.retention.coverage-days:90}") int retentionCoverageDays) {
    this.jdbc = jdbc;
    this.storage = storage;
    this.objectMapper = objectMapper;
    this.transactions = new TransactionTemplate(transactionManager);
    this.retentionRecentCount = Math.max(1, retentionRecentCount);
    this.retentionCoverageDays = Math.max(1, retentionCoverageDays);
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
            "UPDATE backup_remoto SET checksum_sha256 = ?, estado = 'VALIDADO', validado_em = now(), "
                + "verificado_em = now(), resultado_verificacao = 'INTEGRO' "
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
    return verifyBackup(establishmentId, backupId) == VerificationResult.INTEGRO;
  }

  RestoreResponse authorizeDownload(UUID establishmentId, UUID deviceId, UUID backupId) {
    List<StoredBackup> candidates =
        jdbc.query(
            "SELECT id, sequencia, chave_armazenamento, checksum_sha256, versao_formato, criado_em "
                + "FROM backup_remoto WHERE id = ? AND estabelecimento_id = ? "
                + "AND estado = 'VALIDADO'",
            (rs, row) ->
                new StoredBackup(
                    rs.getObject("id", UUID.class),
                    rs.getLong("sequencia"),
                    rs.getString("chave_armazenamento"),
                    rs.getString("checksum_sha256").trim(),
                    rs.getInt("versao_formato"),
                    rs.getTimestamp("criado_em").toInstant()),
            backupId,
            establishmentId);
    if (candidates.isEmpty()
        || !verifyBackup(establishmentId, backupId).equals(VerificationResult.INTEGRO)) {
      throw new IllegalArgumentException("Ponto de recuperação indisponível ou inválido.");
    }
    StoredBackup backup = candidates.getFirst();
    byte[] content = storage.get(backup.key());
    if (!backup.checksum().equals(sha256(content))) {
      throw new IllegalArgumentException("Ponto de recuperação indisponível ou inválido.");
    }
    Snapshot snapshot = decode(content);
    String authorization = newDownloadAuthorization();
    Instant expiresAt = Instant.now().plus(DOWNLOAD_AUTHORIZATION_LIFETIME);
    jdbc.update(
        "INSERT INTO autorizacao_download_backup "
            + "(token_hash, backup_id, dispositivo_id, expira_em) VALUES (?, ?, ?, ?)",
        DeviceAuthenticationController.hash(authorization),
        backupId,
        deviceId,
        Timestamp.from(expiresAt));
    return new RestoreResponse(
        SyncContract.VERSION,
        backupId,
        "AUTHORIZED",
        authorization,
        expiresAt,
        backup.checksum(),
        snapshot.schemaVersion());
  }

  @org.springframework.transaction.annotation.Transactional
  BackupDownload consumeDownload(UUID establishmentId, UUID deviceId, String authorization) {
    List<StoredBackup> authorized =
        jdbc.query(
            "SELECT b.id, b.sequencia, b.chave_armazenamento, b.checksum_sha256, "
                + "b.versao_formato, b.criado_em FROM autorizacao_download_backup a "
                + "JOIN backup_remoto b ON b.id = a.backup_id "
                + "WHERE a.token_hash = ? AND a.dispositivo_id = ? "
                + "AND b.estabelecimento_id = ? AND a.usada_em IS NULL AND a.expira_em > now() "
                + "AND b.estado = 'VALIDADO' AND b.resultado_verificacao = 'INTEGRO' "
                + "FOR UPDATE OF a",
            (rs, row) ->
                new StoredBackup(
                    rs.getObject("id", UUID.class),
                    rs.getLong("sequencia"),
                    rs.getString("chave_armazenamento"),
                    rs.getString("checksum_sha256").trim(),
                    rs.getInt("versao_formato"),
                    rs.getTimestamp("criado_em").toInstant()),
            DeviceAuthenticationController.hash(authorization),
            deviceId,
            establishmentId);
    if (authorized.isEmpty()) throw new DeviceAuthorizationException();
    StoredBackup backup = authorized.getFirst();
    byte[] content = storage.get(backup.key());
    if (!backup.checksum().equals(sha256(content))
        || verifyBackup(establishmentId, backup.id()) != VerificationResult.INTEGRO) {
      throw new IllegalStateException(
          "O ponto de recuperação falhou na verificação de integridade.");
    }
    jdbc.update(
        "UPDATE autorizacao_download_backup SET usada_em = now() WHERE token_hash = ? AND usada_em IS NULL",
        DeviceAuthenticationController.hash(authorization));
    Snapshot snapshot = decode(content);
    return new BackupDownload(backup.id(), backup.checksum(), snapshot.schemaVersion(), content);
  }

  private static String newDownloadAuthorization() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  BackupsResponse listVerified(UUID establishmentId) {
    List<UUID> backupIds =
        jdbc.query(
            "SELECT id FROM backup_remoto WHERE estabelecimento_id = ? AND estado = 'VALIDADO' "
                + "ORDER BY criado_em DESC, id",
            (rs, row) -> rs.getObject(1, UUID.class),
            establishmentId);
    for (UUID backupId : backupIds) verifyBackup(establishmentId, backupId);
    List<BackupResponse> backups =
        jdbc.query(
            "SELECT id, sequencia, criado_em FROM backup_remoto WHERE estabelecimento_id = ? "
                + "AND estado = 'VALIDADO' AND resultado_verificacao = 'INTEGRO' "
                + "ORDER BY criado_em DESC, id",
            (rs, row) ->
                new BackupResponse(
                    SyncContract.VERSION,
                    rs.getObject("id", UUID.class),
                    rs.getLong("sequencia"),
                    "VALIDADO",
                    rs.getTimestamp("criado_em").toInstant()),
            establishmentId);
    return new BackupsResponse(SyncContract.VERSION, backups);
  }

  @Scheduled(cron = "${tucuma.backup.maintenance-cron:0 0 2 * * *}", zone = "UTC")
  void maintainBackups() {
    maintainBackups(Instant.now());
  }

  void maintainBackups(Instant now) {
    List<UUID> establishments =
        jdbc.query(
            "SELECT DISTINCT estabelecimento_id FROM backup_remoto WHERE estado = 'VALIDADO'",
            (rs, row) -> rs.getObject(1, UUID.class));
    for (UUID establishmentId : establishments) {
      List<UUID> backupIds =
          jdbc.query(
              "SELECT id FROM backup_remoto WHERE estabelecimento_id = ? AND estado = 'VALIDADO'",
              (rs, row) -> rs.getObject(1, UUID.class),
              establishmentId);
      for (UUID backupId : backupIds) verifyBackup(establishmentId, backupId);
      try {
        applyRetention(establishmentId, now);
      } catch (RuntimeException failure) {
        LOGGER.warn("Backup retention could not complete for establishment {}", establishmentId);
      }
    }
  }

  private VerificationResult verifyBackup(UUID establishmentId, UUID backupId) {
    List<StoredBackup> backups =
        jdbc.query(
            "SELECT sequencia, chave_armazenamento, checksum_sha256, versao_formato, criado_em "
                + "FROM backup_remoto WHERE id = ? AND estabelecimento_id = ? AND estado = 'VALIDADO'",
            (rs, row) ->
                new StoredBackup(
                    backupId,
                    rs.getLong("sequencia"),
                    rs.getString("chave_armazenamento"),
                    rs.getString("checksum_sha256").trim(),
                    rs.getInt("versao_formato"),
                    rs.getTimestamp("criado_em").toInstant()),
            backupId,
            establishmentId);
    if (backups.isEmpty()) return VerificationResult.OBJETO_AUSENTE;
    StoredBackup backup = backups.getFirst();
    byte[] content;
    try {
      content = storage.get(backup.key());
    } catch (BackupObjectMissingException missing) {
      return recordVerification(establishmentId, backupId, VerificationResult.OBJETO_AUSENTE);
    } catch (RuntimeException storageFailure) {
      return recordVerification(establishmentId, backupId, VerificationResult.ERRO_STORAGE);
    }
    if (!backup.checksum().equals(sha256(content))) {
      return recordVerification(establishmentId, backupId, VerificationResult.CHECKSUM_INVALIDO);
    }
    Snapshot snapshot;
    try {
      snapshot = decode(content);
    } catch (IllegalStateException invalidFormat) {
      return recordVerification(establishmentId, backupId, VerificationResult.FORMATO_INVALIDO);
    }
    boolean valid;
    try {
      valid =
          snapshot.formatVersion() == backup.formatVersion()
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
    } catch (RuntimeException invalidFormat) {
      valid = false;
    }
    return recordVerification(
        establishmentId,
        backupId,
        valid ? VerificationResult.INTEGRO : VerificationResult.FORMATO_INVALIDO);
  }

  private VerificationResult recordVerification(
      UUID establishmentId, UUID backupId, VerificationResult result) {
    String state =
        result == VerificationResult.INTEGRO || result == VerificationResult.ERRO_STORAGE
            ? "VALIDADO"
            : "FALHOU";
    jdbc.update(
        "UPDATE backup_remoto SET estado = ?, verificado_em = now(), resultado_verificacao = ? "
            + "WHERE id = ? AND estabelecimento_id = ? AND estado = 'VALIDADO'",
        state,
        result.name(),
        backupId,
        establishmentId);
    return result;
  }

  private void applyRetention(UUID establishmentId, Instant now) {
    transactions.executeWithoutResult(
        status -> {
          lockEstablishment(establishmentId);
          List<StoredBackup> validBackups =
              jdbc.query(
                  "SELECT id, sequencia, chave_armazenamento, checksum_sha256, versao_formato, criado_em "
                      + "FROM backup_remoto WHERE estabelecimento_id = ? AND estado = 'VALIDADO' "
                      + "AND resultado_verificacao = 'INTEGRO' ORDER BY criado_em DESC, id",
                  (rs, row) ->
                      new StoredBackup(
                          rs.getObject("id", UUID.class),
                          rs.getLong("sequencia"),
                          rs.getString("chave_armazenamento"),
                          rs.getString("checksum_sha256").trim(),
                          rs.getInt("versao_formato"),
                          rs.getTimestamp("criado_em").toInstant()),
                  establishmentId);
          Set<UUID> retained = new HashSet<>();
          validBackups.stream()
              .limit(retentionRecentCount)
              .map(StoredBackup::id)
              .forEach(retained::add);

          Instant cutoff = now.minus(Duration.ofDays(retentionCoverageDays));
          var dailyCoverage = new TreeMap<LocalDate, StoredBackup>();
          for (StoredBackup backup : validBackups) {
            if (!backup.createdAt().isBefore(cutoff)) {
              LocalDate day = backup.createdAt().atZone(ZoneOffset.UTC).toLocalDate();
              dailyCoverage.putIfAbsent(day, backup);
            }
          }
          dailyCoverage.values().stream().map(StoredBackup::id).forEach(retained::add);
          validBackups.stream()
              .filter(backup -> !backup.createdAt().isAfter(cutoff))
              .findFirst()
              .map(StoredBackup::id)
              .ifPresent(retained::add);

          for (StoredBackup backup : validBackups) {
            if (retained.contains(backup.id())) continue;
            try {
              storage.delete(backup.key());
            } catch (RuntimeException storageFailure) {
              LOGGER.warn("Backup object could not be removed for backup {}", backup.id());
              continue;
            }
            jdbc.update(
                "UPDATE backup_remoto SET estado = 'EXPIRADO', verificado_em = ?, "
                    + "resultado_verificacao = 'REMOVIDO_RETENCAO' "
                    + "WHERE id = ? AND estabelecimento_id = ? AND estado = 'VALIDADO' "
                    + "AND resultado_verificacao = 'INTEGRO'",
                java.sql.Timestamp.from(now),
                backup.id(),
                establishmentId);
          }
        });
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

  enum VerificationResult {
    INTEGRO,
    CHECKSUM_INVALIDO,
    FORMATO_INVALIDO,
    OBJETO_AUSENTE,
    ERRO_STORAGE
  }

  private record StoredBackup(
      UUID id,
      long checkpoint,
      String key,
      String checksum,
      int formatVersion,
      Instant createdAt) {}

  record BackupDownload(UUID backupId, String checksumSha256, int schemaVersion, byte[] content) {}
}
