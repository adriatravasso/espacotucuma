package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.finance.PaymentRefund;
import br.com.espacotucuma.desktop.application.port.finance.PaymentRefundRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SqlitePaymentRefundRepository implements PaymentRefundRepository {
  private final java.sql.Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqlitePaymentRefundRepository(
      java.sql.Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public Optional<PaymentRefund> findByIdempotencyKey(String key) {
    return unitOfWork.read(
        () -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT * FROM estorno_recebimento WHERE chave_idempotencia=?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
              return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
          } catch (SQLException failure) {
            throw new IllegalStateException("Não foi possível consultar o estorno.", failure);
          }
        });
  }

  @Override
  public long refundedTotalCents(UUID paymentId) {
    return unitOfWork.read(
        () -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT COALESCE(SUM(valor_centavos),0) FROM estorno_recebimento WHERE recebimento_id=?")) {
            statement.setString(1, paymentId.toString());
            try (ResultSet row = statement.executeQuery()) {
              return row.next() ? row.getLong(1) : 0L;
            }
          } catch (SQLException failure) {
            throw new IllegalStateException("Não foi possível apurar os estornos.", failure);
          }
        });
  }

  @Override
  public List<PaymentRefund> findForPayment(UUID paymentId) {
    return unitOfWork.read(
        () -> {
          List<PaymentRefund> result = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT * FROM estorno_recebimento WHERE recebimento_id=? ORDER BY estornado_em_utc,id")) {
            statement.setString(1, paymentId.toString());
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next()) result.add(read(rows));
            }
            return List.copyOf(result);
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível consultar o histórico de estornos.", failure);
          }
        });
  }

  @Override
  public void insert(PaymentRefund refund) {
    requireTransaction();
    String sql =
        "INSERT INTO estorno_recebimento (id,recebimento_id,movimento_caixa_id,valor_centavos,chave_idempotencia,motivo,estornado_por_usuario_id,estornado_em_utc) VALUES (?,?,?,?,?,?,?,?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, refund.id().toString());
      statement.setString(2, refund.paymentId().toString());
      statement.setString(3, refund.cashMovementId().toString());
      statement.setLong(4, refund.amountCents());
      statement.setString(5, refund.idempotencyKey());
      statement.setString(6, refund.reason());
      statement.setString(7, refund.refundedBy().toString());
      statement.setString(8, refund.refundedAt().toString());
      statement.executeUpdate();
      try (PreparedStatement update =
          connection.prepareStatement(
              "UPDATE recebimento SET estado='ESTORNADO' WHERE id=? AND (SELECT COALESCE(SUM(valor_centavos),0) FROM estorno_recebimento WHERE recebimento_id=?) >= valor_centavos")) {
        update.setString(1, refund.paymentId().toString());
        update.setString(2, refund.paymentId().toString());
        update.executeUpdate();
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível registrar o estorno.", failure);
    }
  }

  private PaymentRefund read(ResultSet row) throws SQLException {
    return new PaymentRefund(
        UUID.fromString(row.getString("id")),
        UUID.fromString(row.getString("recebimento_id")),
        UUID.fromString(row.getString("movimento_caixa_id")),
        row.getLong("valor_centavos"),
        row.getString("chave_idempotencia"),
        row.getString("motivo"),
        UUID.fromString(row.getString("estornado_por_usuario_id")),
        Instant.parse(row.getString("estornado_em_utc")));
  }

  private void requireTransaction() {
    if (!unitOfWork.isExecutingOnCurrentThread())
      throw new IllegalStateException("O estorno exige uma transação local.");
  }
}
