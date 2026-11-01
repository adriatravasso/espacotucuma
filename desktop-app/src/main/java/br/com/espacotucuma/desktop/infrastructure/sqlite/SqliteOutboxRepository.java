package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.outbox.ServerConfirmation;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SqliteOutboxRepository implements OutboxRepository {

  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
  private final java.sql.Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteOutboxRepository(java.sql.Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public void enqueue(PendingOperation operation) {
    requireTransaction();
    String sql =
        "INSERT INTO operacao_pendente (id, entidade, entidade_id, tipo_operacao, "
            + "chave_idempotencia, payload_json, estado, tentativas, criada_em_utc, "
            + "payload_versao, versao_esperada, origem_estacao_id) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'PENDENTE', 0, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, operation.id().toString());
      statement.setString(2, operation.entity());
      statement.setString(3, operation.entityId().toString());
      statement.setString(4, operation.type());
      statement.setString(5, operation.idempotencyKey());
      statement.setString(6, operation.payloadJson());
      statement.setString(7, UTC.format(operation.createdAt()));
      statement.setInt(8, operation.payloadVersion());
      statement.setLong(9, operation.expectedVersion());
      statement.setString(10, operation.originStationId().toString());
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível registrar a operação pendente.", failure);
    }
  }

  @Override
  public long expectedVersionForNext(String entity, UUID entityId) {
    return unitOfWork.read(
        () -> {
          String sql =
              "SELECT versao_esperada, versao_remota_confirmada FROM operacao_pendente "
                  + "WHERE entidade = ? AND entidade_id = ? ORDER BY rowid DESC LIMIT 1";
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entity);
            statement.setString(2, entityId.toString());
            try (ResultSet result = statement.executeQuery()) {
              if (!result.next()) return 0L;
              long confirmedVersion = result.getLong("versao_remota_confirmada");
              return result.wasNull() ? result.getLong("versao_esperada") + 1 : confirmedVersion;
            }
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível determinar a versão remota esperada.", failure);
          }
        });
  }

  @Override
  public List<PendingOperation> findPending(int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("O limite de consulta deve ser positivo.");
    }
    return unitOfWork.read(
        () -> {
          List<PendingOperation> operations = new ArrayList<>();
          String sql =
              "SELECT id, entidade, entidade_id, tipo_operacao, chave_idempotencia, payload_json, "
                  + "payload_versao, versao_esperada, origem_estacao_id, criada_em_utc, tentativas "
                  + "FROM operacao_pendente WHERE estado = 'PENDENTE' "
                  + "ORDER BY rowid LIMIT ?";
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) {
                operations.add(
                    new PendingOperation(
                        UUID.fromString(result.getString("id")),
                        result.getString("entidade"),
                        UUID.fromString(result.getString("entidade_id")),
                        result.getString("tipo_operacao"),
                        result.getString("chave_idempotencia"),
                        result.getString("payload_json"),
                        result.getInt("payload_versao"),
                        result.getLong("versao_esperada"),
                        UUID.fromString(result.getString("origem_estacao_id")),
                        Instant.parse(result.getString("criada_em_utc")),
                        result.getInt("tentativas")));
              }
            }
            return List.copyOf(operations);
          } catch (SQLException failure) {
            throw new IllegalStateException("Não foi possível consultar a outbox local.", failure);
          }
        });
  }

  @Override
  public boolean recordAttempt(UUID operationId) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE operacao_pendente SET tentativas = tentativas + 1 "
                + "WHERE id = ? AND estado = 'PENDENTE'")) {
      statement.setString(1, operationId.toString());
      return statement.executeUpdate() == 1;
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível registrar a tentativa de envio.", failure);
    }
  }

  @Override
  public boolean markSynchronized(ServerConfirmation confirmation) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE operacao_pendente SET estado = 'SINCRONIZADA', confirmada_em_utc = ?, "
                + "versao_remota_confirmada = ? WHERE id = ? AND estado = 'PENDENTE'")) {
      statement.setString(1, UTC.format(confirmation.confirmedAt()));
      statement.setLong(2, confirmation.remoteVersion());
      statement.setString(3, confirmation.operationId().toString());
      return statement.executeUpdate() == 1;
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "Não foi possível confirmar a operação sincronizada.", failure);
    }
  }

  private void requireTransaction() {
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException("A alteração da outbox exige uma transação local.");
    }
  }
}
