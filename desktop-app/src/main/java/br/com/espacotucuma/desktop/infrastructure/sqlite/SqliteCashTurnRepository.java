package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.finance.CashMovement;
import br.com.espacotucuma.desktop.application.finance.CashMovementType;
import br.com.espacotucuma.desktop.application.finance.CashReconciliation;
import br.com.espacotucuma.desktop.application.finance.CashTurn;
import br.com.espacotucuma.desktop.application.finance.PaymentMethod;
import br.com.espacotucuma.desktop.application.port.finance.CashTurnRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SqliteCashTurnRepository implements CashTurnRepository {
  private final java.sql.Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteCashTurnRepository(java.sql.Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public Optional<CashTurn> findOpenTurn(UUID operatorId) {
    return findOne("estado='ABERTO'", operatorId);
  }

  @Override
  public Optional<CashTurn> findLatestTurn(UUID operatorId) {
    return findOne("1=1 ORDER BY abertura_em_utc DESC LIMIT 1", operatorId);
  }

  @Override
  public Optional<CashTurn> findById(UUID turnId) {
    return unitOfWork.read(
        () -> {
          try {
            return findByIdSql(turnId);
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível consultar o turno de caixa.", failure);
          }
        });
  }

  private Optional<CashTurn> findOne(String predicate, UUID operatorId) {
    return unitOfWork.read(
        () -> {
          String sql =
              "SELECT id,operador_usuario_id,estado,abertura_em_utc,fechamento_em_utc,"
                  + "saldo_abertura_centavos,saldo_fechamento_centavos FROM caixa_turno WHERE "
                  + "operador_usuario_id=? AND "
                  + predicate;
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, operatorId.toString());
            try (ResultSet rows = statement.executeQuery()) {
              return rows.next() ? Optional.of(turn(rows)) : Optional.empty();
            }
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível consultar o turno de caixa.", failure);
          }
        });
  }

  @Override
  public List<CashMovement> findMovements(UUID turnId) {
    return unitOfWork.read(
        () -> {
          List<CashMovement> result = new ArrayList<>();
          String sql =
              "SELECT id,caixa_turno_id,recebimento_id,tipo,valor_centavos,motivo,"
                  + "criado_por_usuario_id,criado_em_utc FROM movimento_caixa "
                  + "WHERE caixa_turno_id=? ORDER BY criado_em_utc,id";
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, turnId.toString());
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next()) {
                String receipt = rows.getString("recebimento_id");
                result.add(
                    new CashMovement(
                        UUID.fromString(rows.getString("id")),
                        UUID.fromString(rows.getString("caixa_turno_id")),
                        receipt == null ? null : UUID.fromString(receipt),
                        CashMovementType.valueOf(rows.getString("tipo")),
                        rows.getLong("valor_centavos"),
                        rows.getString("motivo"),
                        UUID.fromString(rows.getString("criado_por_usuario_id")),
                        Instant.parse(rows.getString("criado_em_utc"))));
              }
            }
            return List.copyOf(result);
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível consultar os movimentos do turno.", failure);
          }
        });
  }

  @Override
  public Map<PaymentMethod, Long> expectedTotals(UUID turnId) {
    return unitOfWork.read(
        () -> {
          EnumMap<PaymentMethod, Long> totals = new EnumMap<>(PaymentMethod.class);
          for (PaymentMethod method : PaymentMethod.values()) totals.put(method, 0L);
          String sql =
              "SELECT r.forma,SUM(r.valor_centavos-COALESCE(x.estornado,0)) total FROM recebimento r "
                  + "JOIN movimento_caixa m ON m.recebimento_id=r.id "
                  + "LEFT JOIN (SELECT recebimento_id,SUM(valor_centavos) estornado FROM estorno_recebimento GROUP BY recebimento_id) x ON x.recebimento_id=r.id "
                  + "WHERE m.caixa_turno_id=? GROUP BY r.forma";
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, turnId.toString());
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next()) {
                totals.put(PaymentMethod.valueOf(rows.getString("forma")), rows.getLong("total"));
              }
            }
            CashTurn turn = findByIdSql(turnId).orElseThrow();
            long cash = Math.addExact(totals.get(PaymentMethod.DINHEIRO), turn.openingCents());
            try (PreparedStatement movements =
                connection.prepareStatement(
                    "SELECT tipo,SUM(valor_centavos) total FROM movimento_caixa "
                        + "WHERE caixa_turno_id=? AND recebimento_id IS NULL GROUP BY tipo")) {
              movements.setString(1, turnId.toString());
              try (ResultSet rows = movements.executeQuery()) {
                while (rows.next()) {
                  long amount = rows.getLong("total");
                  cash =
                      switch (rows.getString("tipo")) {
                        case "SUPRIMENTO" -> Math.addExact(cash, amount);
                        case "SANGRIA", "SAIDA" -> Math.subtractExact(cash, amount);
                        default -> cash;
                      };
                }
              }
            }
            totals.put(PaymentMethod.DINHEIRO, cash);
            return Map.copyOf(totals);
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível apurar os totais esperados.", failure);
          }
        });
  }

  private Optional<CashTurn> findByIdSql(UUID id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,operador_usuario_id,estado,abertura_em_utc,fechamento_em_utc,"
                + "saldo_abertura_centavos,saldo_fechamento_centavos FROM caixa_turno WHERE id=?")) {
      statement.setString(1, id.toString());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? Optional.of(turn(rows)) : Optional.empty();
      }
    }
  }

  @Override
  public List<CashReconciliation> findLatestClosing(UUID turnId) {
    return unitOfWork.read(
        () -> {
          List<CashReconciliation> result = new ArrayList<>();
          String sql =
              "SELECT forma,esperado_centavos,conferido_centavos FROM conferencia_caixa "
                  + "WHERE fechamento_id=(SELECT fechamento_id FROM conferencia_caixa "
                  + "WHERE caixa_turno_id=? ORDER BY conferido_em_utc DESC,rowid DESC LIMIT 1) ORDER BY forma";
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, turnId.toString());
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next()) {
                result.add(
                    new CashReconciliation(
                        PaymentMethod.valueOf(rows.getString("forma")),
                        rows.getLong("esperado_centavos"),
                        rows.getLong("conferido_centavos")));
              }
            }
            return List.copyOf(result);
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível consultar a conferência do turno.", failure);
          }
        });
  }

  @Override
  public void open(CashTurn turn) {
    requireTransaction();
    String sql =
        "INSERT INTO caixa_turno (id,operador_usuario_id,estado,abertura_em_utc,saldo_abertura_centavos) "
            + "VALUES (?,?,'ABERTO',?,?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, turn.id().toString());
      statement.setString(2, turn.operatorId().toString());
      statement.setString(3, turn.openedAt().toString());
      statement.setLong(4, turn.openingCents());
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível abrir o turno de caixa.", failure);
    }
  }

  @Override
  public void recordMovement(CashMovement movement) {
    requireTransaction();
    requireOpen(movement.turnId());
    String sql =
        "INSERT INTO movimento_caixa (id,caixa_turno_id,recebimento_id,tipo,valor_centavos,motivo,"
            + "criado_por_usuario_id,criado_em_utc) VALUES (?,?,NULL,?,?,?,?,?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, movement.id().toString());
      statement.setString(2, movement.turnId().toString());
      statement.setString(3, movement.type().name());
      statement.setLong(4, movement.amountCents());
      statement.setString(5, movement.reason());
      statement.setString(6, movement.createdBy().toString());
      statement.setString(7, movement.createdAt().toString());
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível registrar o movimento de caixa.", failure);
    }
  }

  @Override
  public UUID recordRefund(
      UUID turnId, UUID paymentId, long amountCents, String reason, UUID actorId, Instant at) {
    requireTransaction();
    requireOpen(turnId);
    UUID movementId = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO movimento_caixa (id,caixa_turno_id,recebimento_id,tipo,valor_centavos,motivo,"
                + "criado_por_usuario_id,criado_em_utc) VALUES (?,?,NULL,'ESTORNO',?,?,?,?)")) {
      statement.setString(1, movementId.toString());
      statement.setString(2, turnId.toString());
      statement.setLong(3, amountCents);
      statement.setString(4, "Estorno do recebimento " + paymentId + ": " + reason);
      statement.setString(5, actorId.toString());
      statement.setString(6, at.toString());
      statement.executeUpdate();
      return movementId;
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível lançar o estorno no caixa.", failure);
    }
  }

  @Override
  public void close(
      UUID turnId,
      UUID closingId,
      UUID operatorId,
      Instant closedAt,
      Map<PaymentMethod, CashReconciliation> totals,
      String reason) {
    requireTransaction();
    String sql =
        "INSERT INTO conferencia_caixa (id,caixa_turno_id,fechamento_id,forma,esperado_centavos,"
            + "conferido_centavos,justificativa,conferido_por_usuario_id,conferido_em_utc) "
            + "VALUES (?,?,?,?,?,?,?,?,?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (PaymentMethod method : PaymentMethod.values()) {
        CashReconciliation total = totals.get(method);
        statement.setString(1, UUID.randomUUID().toString());
        statement.setString(2, turnId.toString());
        statement.setString(3, closingId.toString());
        statement.setString(4, method.name());
        statement.setLong(5, total.expectedCents());
        statement.setLong(6, total.countedCents());
        statement.setString(7, reason);
        statement.setString(8, operatorId.toString());
        statement.setString(9, closedAt.toString());
        statement.addBatch();
      }
      statement.executeBatch();
      try (PreparedStatement update =
          connection.prepareStatement(
              "UPDATE caixa_turno SET estado='FECHADO',fechamento_em_utc=?,saldo_fechamento_centavos=? "
                  + "WHERE id=? AND estado='ABERTO'")) {
        update.setString(1, closedAt.toString());
        update.setLong(2, totals.get(PaymentMethod.DINHEIRO).countedCents());
        update.setString(3, turnId.toString());
        if (update.executeUpdate() != 1) {
          throw new IllegalStateException("O turno não está aberto ou já foi fechado.");
        }
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível salvar o fechamento do caixa.", failure);
    }
  }

  @Override
  public void reopen(UUID turnId) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE caixa_turno SET estado='ABERTO',fechamento_em_utc=NULL,"
                + "saldo_fechamento_centavos=NULL WHERE id=? AND estado='FECHADO'")) {
      statement.setString(1, turnId.toString());
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException("Somente um caixa fechado pode ser reaberto.");
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível reabrir o caixa.", failure);
    }
  }

  private void requireOpen(UUID turnId) {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM caixa_turno WHERE id=? AND estado='ABERTO'")) {
      statement.setString(1, turnId.toString());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) throw new IllegalStateException("O caixa está fechado.");
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível validar o estado do caixa.", failure);
    }
  }

  private CashTurn turn(ResultSet rows) throws SQLException {
    String closedAt = rows.getString("fechamento_em_utc");
    long closingCents = rows.getLong("saldo_fechamento_centavos");
    Long closing = rows.wasNull() ? null : closingCents;
    return new CashTurn(
        UUID.fromString(rows.getString("id")),
        UUID.fromString(rows.getString("operador_usuario_id")),
        "ABERTO".equals(rows.getString("estado")),
        Instant.parse(rows.getString("abertura_em_utc")),
        closedAt == null ? null : Instant.parse(closedAt),
        rows.getLong("saldo_abertura_centavos"),
        closing);
  }

  private void requireTransaction() {
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException("A gravação do caixa exige uma transação local.");
    }
  }
}
