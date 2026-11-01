package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.port.synchronization.RemoteConflictRepository;
import br.com.espacotucuma.desktop.application.synchronization.RemoteConflict;
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

public final class SqliteRemoteConflictRepository implements RemoteConflictRepository {
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
  private final java.sql.Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteRemoteConflictRepository(
      java.sql.Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public List<RemoteConflict> findAll() {
    return unitOfWork.read(
        () -> {
          List<RemoteConflict> conflicts = new ArrayList<>();
          String sql =
              "SELECT sequencia, operacao_id, entidade, entidade_id, tipo_operacao, "
                  + "motivo_conflito, acao_necessaria, estado_reconciliacao, payload_local_json, "
                  + "payload_json, decisao, motivo_decisao FROM alteracao_remota_recebida "
                  + "WHERE estado_local = 'CONFLITO' ORDER BY sequencia DESC";
          try (PreparedStatement statement = connection.prepareStatement(sql);
              ResultSet result = statement.executeQuery()) {
            while (result.next()) {
              conflicts.add(
                  new RemoteConflict(
                      result.getLong("sequencia"),
                      UUID.fromString(result.getString("operacao_id")),
                      result.getString("entidade"),
                      UUID.fromString(result.getString("entidade_id")),
                      result.getString("tipo_operacao"),
                      result.getString("motivo_conflito"),
                      result.getString("acao_necessaria"),
                      result.getString("estado_reconciliacao"),
                      result.getString("payload_local_json"),
                      result.getString("payload_json"),
                      result.getString("decisao"),
                      result.getString("motivo_decisao")));
            }
            return List.copyOf(conflicts);
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível consultar conflitos de sync.", failure);
          }
        });
  }

  @Override
  public void recordDecision(UUID operationId, UUID userId, Instant decidedAt, String reason) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE alteracao_remota_recebida SET estado_reconciliacao = 'RESOLVIDO', "
                + "decisao = 'MANTER_LOCAL_PRESERVAR_REMOTO', decidido_por_usuario_id = ?, "
                + "decidido_em_utc = ?, motivo_decisao = ? WHERE operacao_id = ? "
                + "AND estado_local = 'CONFLITO' AND estado_reconciliacao = 'ABERTO'")) {
      statement.setString(1, Objects.requireNonNull(userId, "userId").toString());
      statement.setString(2, UTC.format(Objects.requireNonNull(decidedAt, "decidedAt")));
      statement.setString(3, Objects.requireNonNull(reason, "reason"));
      statement.setString(4, Objects.requireNonNull(operationId, "operationId").toString());
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException("O conflito não está aberto ou já recebeu uma decisão.");
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível registrar a decisão do conflito.", failure);
    }
  }

  private void requireTransaction() {
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException("A decisão de conflito exige uma transação local.");
    }
  }
}
