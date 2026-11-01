package br.com.espacotucuma.sync;

import br.com.espacotucuma.sync.contract.OperationResult;
import br.com.espacotucuma.sync.contract.OperationSubmission;
import br.com.espacotucuma.sync.contract.SyncErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits one received operation, its stored result, entity state, and checkpoint atomically. */
@Service
class SyncOperationReceiver {
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  SyncOperationReceiver(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Transactional
  OperationResult receiveOne(UUID establishmentId, UUID deviceId, OperationSubmission operation) {
    lockEstablishment(establishmentId);
    requireActiveDevice(establishmentId, deviceId);

    List<StoredOperation> storedOperations =
        jdbc.query(
            "SELECT id::text AS operation_id, dispositivo_id::text AS device_id, entidade, "
                + "entidade_id::text AS entity_id, tipo_operacao, payload::text AS payload, "
                + "payload_versao, versao_esperada, resultado_estado, "
                + "COALESCE((resultado->>'remoteVersion')::bigint, 0) AS remote_version "
                + "FROM operacao_recebida WHERE estabelecimento_id = ? AND chave_idempotencia = ?",
            (rs, rowNum) ->
                new StoredOperation(
                    UUID.fromString(rs.getString("operation_id")),
                    UUID.fromString(rs.getString("device_id")),
                    rs.getString("entidade"),
                    UUID.fromString(rs.getString("entity_id")),
                    rs.getString("tipo_operacao"),
                    rs.getString("payload"),
                    rs.getInt("payload_versao"),
                    rs.getLong("versao_esperada"),
                    rs.getString("resultado_estado"),
                    rs.getLong("remote_version")),
            establishmentId,
            operation.idempotencyKey());
    if (!storedOperations.isEmpty()) {
      StoredOperation stored = storedOperations.getFirst();
      if (matches(stored, deviceId, operation)) return stored.result();
      return new OperationResult(
          operation.operationId(),
          OperationResult.Outcome.CONFLICT,
          stored.remoteVersion(),
          SyncErrorCode.CONFLICT);
    }

    long currentVersion = currentVersion(establishmentId, operation);
    boolean versionMatches = currentVersion == operation.entityVersion();
    long resultingVersion = versionMatches ? currentVersion + 1 : currentVersion;
    OperationResult.Outcome outcome =
        versionMatches ? OperationResult.Outcome.ACCEPTED : OperationResult.Outcome.CONFLICT;
    SyncErrorCode errorCode = versionMatches ? null : SyncErrorCode.CONFLICT;
    String payload = json(operation.payload());
    long sequence =
        insertOperation(
            establishmentId,
            deviceId,
            operation,
            payload,
            resultingVersion,
            outcome == OperationResult.Outcome.ACCEPTED ? "ACEITA" : "CONFLITO");

    if (versionMatches) {
      jdbc.update(
          "INSERT INTO entidade_remota_sincronizada "
              + "(estabelecimento_id, entidade, entidade_id, versao, payload, ultima_operacao_id) "
              + "VALUES (?, ?, ?, ?, ?::jsonb, ?) "
              + "ON CONFLICT (estabelecimento_id, entidade, entidade_id) DO UPDATE SET "
              + "versao = EXCLUDED.versao, payload = EXCLUDED.payload, "
              + "ultima_operacao_id = EXCLUDED.ultima_operacao_id, atualizada_em = now()",
          establishmentId,
          operation.entityType(),
          operation.entityId(),
          resultingVersion,
          payload,
          operation.operationId());
    }
    advanceCheckpoint(establishmentId, deviceId, sequence);
    return new OperationResult(operation.operationId(), outcome, resultingVersion, errorCode);
  }

  private void lockEstablishment(UUID establishmentId) {
    List<String> establishments =
        jdbc.query(
            "SELECT id::text FROM estabelecimento_remoto WHERE id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getString(1),
            establishmentId);
    if (establishments.isEmpty()) throw new DeviceAuthorizationException();
  }

  private void requireActiveDevice(UUID establishmentId, UUID deviceId) {
    List<String> activeDevices =
        jdbc.query(
            "SELECT id::text FROM dispositivo_autorizado "
                + "WHERE id = ? AND estabelecimento_id = ? AND estado = 'ATIVO' FOR UPDATE",
            (rs, rowNum) -> rs.getString(1),
            deviceId,
            establishmentId);
    if (activeDevices.size() != 1) throw new DeviceAuthorizationException();
  }

  private long currentVersion(UUID establishmentId, OperationSubmission operation) {
    List<Long> versions =
        jdbc.query(
            "SELECT versao FROM entidade_remota_sincronizada "
                + "WHERE estabelecimento_id = ? AND entidade = ? AND entidade_id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getLong(1),
            establishmentId,
            operation.entityType(),
            operation.entityId());
    return versions.isEmpty() ? 0 : versions.getFirst();
  }

  private long insertOperation(
      UUID establishmentId,
      UUID deviceId,
      OperationSubmission operation,
      String payload,
      long remoteVersion,
      String state) {
    String result = json(Map.of("remoteVersion", remoteVersion));
    return jdbc.queryForObject(
        "INSERT INTO operacao_recebida "
            + "(id, estabelecimento_id, dispositivo_id, chave_idempotencia, entidade, entidade_id, "
            + "tipo_operacao, payload, payload_versao, versao_esperada, resultado_estado, resultado) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb) RETURNING sequencia",
        Long.class,
        operation.operationId(),
        establishmentId,
        deviceId,
        operation.idempotencyKey(),
        operation.entityType(),
        operation.entityId(),
        operation.operationType(),
        payload,
        operation.payloadVersion(),
        operation.entityVersion(),
        state,
        result);
  }

  private void advanceCheckpoint(UUID establishmentId, UUID deviceId, long sequence) {
    jdbc.update(
        "INSERT INTO checkpoint_sincronizacao "
            + "(estabelecimento_id, dispositivo_id, ultima_sequencia) VALUES (?, ?, ?) "
            + "ON CONFLICT (estabelecimento_id, dispositivo_id) DO UPDATE SET "
            + "ultima_sequencia = GREATEST(checkpoint_sincronizacao.ultima_sequencia, "
            + "EXCLUDED.ultima_sequencia), atualizado_em = now()",
        establishmentId,
        deviceId,
        sequence);
  }

  private boolean matches(StoredOperation stored, UUID deviceId, OperationSubmission operation) {
    try {
      JsonNode storedPayload = objectMapper.readTree(stored.payload());
      JsonNode receivedPayload = objectMapper.valueToTree(operation.payload());
      return stored.operationId().equals(operation.operationId())
          && stored.deviceId().equals(deviceId)
          && stored.entity().equals(operation.entityType())
          && stored.entityId().equals(operation.entityId())
          && stored.operationType().equals(operation.operationType())
          && stored.payloadVersion() == operation.payloadVersion()
          && stored.expectedVersion() == operation.entityVersion()
          && storedPayload.equals(receivedPayload);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Persisted synchronization payload is invalid.", exception);
    }
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Synchronization payload cannot be encoded.", exception);
    }
  }

  private record StoredOperation(
      UUID operationId,
      UUID deviceId,
      String entity,
      UUID entityId,
      String operationType,
      String payload,
      int payloadVersion,
      long expectedVersion,
      String outcome,
      long remoteVersion) {
    OperationResult result() {
      return switch (outcome) {
        case "ACEITA" ->
            new OperationResult(operationId, OperationResult.Outcome.ACCEPTED, remoteVersion, null);
        case "CONFLITO" ->
            new OperationResult(
                operationId,
                OperationResult.Outcome.CONFLICT,
                remoteVersion,
                SyncErrorCode.CONFLICT);
        case "REJEITADA" ->
            new OperationResult(
                operationId,
                OperationResult.Outcome.REJECTED,
                remoteVersion,
                SyncErrorCode.VALIDATION_FAILED);
        default -> throw new IllegalStateException("Unknown persisted operation result.");
      };
    }
  }
}
