package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.port.synchronization.RemoteChangeRepository;
import br.com.espacotucuma.sync.contract.ChangesResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Persists an incremental page and its cursor atomically, retaining unexpected changes as
 * conflicts.
 */
public final class SqliteRemoteChangeRepository implements RemoteChangeRepository {
  private final java.sql.Connection connection;
  private final SqliteUnitOfWork unitOfWork;
  private final ObjectMapper json;
  private final UUID deviceId;

  public SqliteRemoteChangeRepository(
      java.sql.Connection connection,
      SqliteUnitOfWork unitOfWork,
      ObjectMapper json,
      UUID deviceId) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.json = Objects.requireNonNull(json, "json");
    this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
  }

  @Override
  public long checkpoint() {
    return unitOfWork.read(
        () -> {
          try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT sequencia FROM cursor_alteracao_remota WHERE id = 1");
              ResultSet result = statement.executeQuery()) {
            if (!result.next())
              throw new IllegalStateException("Remote cursor is not initialized.");
            return result.getLong(1);
          } catch (SQLException failure) {
            throw new IllegalStateException("Não foi possível ler o cursor remoto local.", failure);
          }
        });
  }

  @Override
  public void applyPage(long expectedCheckpoint, ChangesResponse page) {
    requireTransaction();
    Objects.requireNonNull(page, "page");
    if (expectedCheckpoint < 0 || page.checkpoint() < expectedCheckpoint) {
      throw new IllegalArgumentException("Remote checkpoint moved backwards.");
    }
    Set<Long> sequences = new HashSet<>();
    Set<UUID> operationIds = new HashSet<>();
    long previous = expectedCheckpoint;
    for (ChangesResponse.RemoteChange change : page.changes()) {
      if (change == null
          || change.sequence() <= previous
          || change.sequence() > page.checkpoint()
          || !sequences.add(change.sequence())
          || change.operationId() == null
          || !operationIds.add(change.operationId())
          || change.sourceDeviceId() == null
          || blank(change.entityType())
          || change.entityId() == null
          || blank(change.operationType())
          || change.payloadVersion() < 1
          || change.entityVersion() < 0
          || !Set.of("ACEITA", "REJEITADA", "CONFLITO").contains(change.outcome())
          || change.payload() == null) {
        throw new IllegalArgumentException("Malformed or out-of-order remote change page.");
      }
      previous = change.sequence();
    }
    long expectedPageCheckpoint = page.changes().isEmpty() ? expectedCheckpoint : previous;
    if (page.checkpoint() != expectedPageCheckpoint) {
      throw new IllegalArgumentException("Remote page checkpoint does not match its last change.");
    }
    try {
      for (ChangesResponse.RemoteChange change : page.changes()) insert(change);
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE cursor_alteracao_remota SET sequencia = ? WHERE id = 1 AND sequencia = ?")) {
        statement.setLong(1, page.checkpoint());
        statement.setLong(2, expectedCheckpoint);
        if (statement.executeUpdate() != 1) {
          throw new IllegalStateException("Remote cursor changed during page application.");
        }
      }
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "Não foi possível aplicar a página incremental local.", failure);
    }
  }

  private void insert(ChangesResponse.RemoteChange change) throws SQLException {
    String conflict = conflictReason(change);
    String payload;
    try {
      payload = json.writeValueAsString(change.payload());
    } catch (JsonProcessingException failure) {
      throw new IllegalArgumentException("Remote payload cannot be encoded.", failure);
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO alteracao_remota_recebida "
                + "(sequencia, operacao_id, dispositivo_origem_id, entidade, entidade_id, "
                + "tipo_operacao, payload_versao, versao_entidade, resultado, payload_json, "
                + "estado_local, motivo_conflito) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      statement.setLong(1, change.sequence());
      statement.setString(2, change.operationId().toString());
      statement.setString(3, change.sourceDeviceId().toString());
      statement.setString(4, change.entityType());
      statement.setString(5, change.entityId().toString());
      statement.setString(6, change.operationType());
      statement.setInt(7, change.payloadVersion());
      statement.setLong(8, change.entityVersion());
      statement.setString(9, change.outcome());
      statement.setString(10, payload);
      statement.setString(11, conflict == null ? "RECONCILIADA" : "CONFLITO");
      statement.setString(12, conflict);
      statement.executeUpdate();
    }
  }

  private String conflictReason(ChangesResponse.RemoteChange change) throws SQLException {
    if (!deviceId.equals(change.sourceDeviceId())) return "ORIGEM_REMOTA_INESPERADA";
    if (!"ACEITA".equals(change.outcome())) return "OPERACAO_REMOTA_NAO_ACEITA";
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT entidade, entidade_id, tipo_operacao, estado, versao_remota_confirmada, "
                + "payload_json FROM operacao_pendente WHERE id = ?")) {
      statement.setString(1, change.operationId().toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return "OPERACAO_LOCAL_DESCONHECIDA";
        if (!change.entityType().equals(result.getString("entidade"))
            || !change.entityId().toString().equals(result.getString("entidade_id"))
            || !change.operationType().equals(result.getString("tipo_operacao"))) {
          return "IDENTIDADE_DIVERGENTE";
        }
        if (!"SINCRONIZADA".equals(result.getString("estado"))) {
          return "OPERACAO_LOCAL_NAO_CONFIRMADA";
        }
        if (result.getLong("versao_remota_confirmada") != change.entityVersion()) {
          return "VERSAO_REMOTA_DIVERGENTE";
        }
        try {
          if (!json.readTree(result.getString("payload_json"))
              .equals(json.valueToTree(change.payload()))) {
            return "PAYLOAD_DIVERGENTE";
          }
        } catch (JsonProcessingException failure) {
          throw new IllegalStateException("Local synchronization payload is invalid.", failure);
        }
      }
    }
    return null;
  }

  private void requireTransaction() {
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException("A aplicação da página remota exige uma transação local.");
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
