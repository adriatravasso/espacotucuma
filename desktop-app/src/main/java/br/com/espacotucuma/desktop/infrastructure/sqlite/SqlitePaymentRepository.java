package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.finance.Payment;
import br.com.espacotucuma.desktop.application.finance.PaymentMethod;
import br.com.espacotucuma.desktop.application.port.finance.PaymentRepository;
import br.com.espacotucuma.desktop.domain.money.Money;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SqlitePaymentRepository implements PaymentRepository {
  private final java.sql.Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqlitePaymentRepository(java.sql.Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
    return unitOfWork.read(
        () -> {
          try (PreparedStatement statement =
              connection.prepareStatement("SELECT * FROM recebimento WHERE chave_idempotencia=?")) {
            statement.setString(1, idempotencyKey);
            try (ResultSet rows = statement.executeQuery()) {
              return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível consultar o recebimento local.", failure);
          }
        });
  }

  @Override
  public Optional<Payment> findById(UUID paymentId) {
    return unitOfWork.read(
        () -> {
          try (PreparedStatement statement =
              connection.prepareStatement("SELECT * FROM recebimento WHERE id=?")) {
            statement.setString(1, paymentId.toString());
            try (ResultSet rows = statement.executeQuery()) {
              return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
          } catch (SQLException failure) {
            throw new IllegalStateException("Não foi possível consultar o recebimento.", failure);
          }
        });
  }

  @Override
  public List<Payment> findForAttendance(UUID attendanceId) {
    return unitOfWork.read(
        () -> {
          List<Payment> result = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT * FROM recebimento WHERE atendimento_id=? ORDER BY recebido_em_utc,id")) {
            statement.setString(1, attendanceId.toString());
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next()) result.add(read(rows));
            }
            return List.copyOf(result);
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível consultar os recebimentos locais.", failure);
          }
        });
  }

  @Override
  public long confirmedTotalCents(UUID attendanceId) {
    return unitOfWork.read(
        () -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT COALESCE(SUM(r.valor_centavos-COALESCE(x.estornado,0)),0) FROM recebimento r "
                      + "LEFT JOIN (SELECT recebimento_id,SUM(valor_centavos) estornado FROM estorno_recebimento GROUP BY recebimento_id) x ON x.recebimento_id=r.id "
                      + "WHERE r.atendimento_id=?")) {
            statement.setString(1, attendanceId.toString());
            try (ResultSet row = statement.executeQuery()) {
              return row.next() ? row.getLong(1) : 0L;
            }
          } catch (SQLException failure) {
            throw new IllegalStateException("Não foi possível calcular o saldo pendente.", failure);
          }
        });
  }

  @Override
  public void insert(Payment payment) {
    requireTransaction();
    String sql =
        "INSERT INTO recebimento (id,atendimento_id,forma,valor_centavos,estado,chave_idempotencia,"
            + "recebido_em_utc,recebido_por_usuario_id) VALUES (?,?,?,?,'CONFIRMADO',?,?,?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, payment.id().toString());
      statement.setString(2, payment.attendanceId().toString());
      statement.setString(3, payment.method().name());
      statement.setLong(4, cents(payment.amount()));
      statement.setString(5, payment.idempotencyKey());
      statement.setString(6, payment.receivedAt().toString());
      statement.setString(7, payment.receivedBy().toString());
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível registrar o recebimento local.", failure);
    }
  }

  private Payment read(ResultSet row) throws SQLException {
    return new Payment(
        UUID.fromString(row.getString("id")),
        UUID.fromString(row.getString("atendimento_id")),
        PaymentMethod.valueOf(row.getString("forma")),
        new Money(BigDecimal.valueOf(row.getLong("valor_centavos"), 2)),
        Instant.parse(row.getString("recebido_em_utc")),
        UUID.fromString(row.getString("recebido_por_usuario_id")),
        row.getString("chave_idempotencia"));
  }

  private long cents(Money amount) {
    return amount.amount().movePointRight(2).longValueExact();
  }

  private void requireTransaction() {
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException("A gravação financeira exige uma transação local.");
    }
  }
}
