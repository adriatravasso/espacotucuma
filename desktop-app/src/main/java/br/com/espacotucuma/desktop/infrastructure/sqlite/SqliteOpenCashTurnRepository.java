package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.finance.Payment;
import br.com.espacotucuma.desktop.application.port.finance.OpenCashTurnRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SqliteOpenCashTurnRepository implements OpenCashTurnRepository {
  private final java.sql.Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteOpenCashTurnRepository(java.sql.Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public Optional<UUID> findOpenTurn(UUID operatorId) {
    return unitOfWork.read(
        () -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id FROM caixa_turno WHERE operador_usuario_id=? AND estado='ABERTO'")) {
            statement.setString(1, operatorId.toString());
            try (ResultSet rows = statement.executeQuery()) {
              return rows.next()
                  ? Optional.of(UUID.fromString(rows.getString("id")))
                  : Optional.empty();
            }
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível consultar o turno de caixa aberto.", failure);
          }
        });
  }

  @Override
  public UUID recordReceipt(UUID turnId, Payment payment, Instant recordedAt) {
    requireTransaction();
    try (PreparedStatement check =
        connection.prepareStatement("SELECT 1 FROM caixa_turno WHERE id=? AND estado='ABERTO'")) {
      check.setString(1, turnId.toString());
      try (ResultSet rows = check.executeQuery()) {
        if (!rows.next()) throw new IllegalStateException("O caixa está fechado.");
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível validar o estado do caixa.", failure);
    }
    UUID movementId = UUID.randomUUID();
    String sql =
        "INSERT INTO movimento_caixa (id,caixa_turno_id,recebimento_id,tipo,valor_centavos,motivo,"
            + "criado_por_usuario_id,criado_em_utc) VALUES (?,?,?,'ENTRADA',?,?,?,?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, movementId.toString());
      statement.setString(2, turnId.toString());
      statement.setString(3, payment.id().toString());
      statement.setLong(4, payment.amount().amount().movePointRight(2).longValueExact());
      statement.setString(5, "Recebimento de atendimento " + payment.attendanceId());
      statement.setString(6, payment.receivedBy().toString());
      statement.setString(7, recordedAt.toString());
      statement.executeUpdate();
      return movementId;
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "Não foi possível registrar a entrada no caixa local.", failure);
    }
  }

  private void requireTransaction() {
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException("A gravação no caixa exige uma transação local.");
    }
  }
}
