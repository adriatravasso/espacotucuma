package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.Page;
import br.com.espacotucuma.desktop.application.port.prepaid.PrepaidPackageRepository;
import br.com.espacotucuma.desktop.application.prepaid.AttendanceOption;
import br.com.espacotucuma.desktop.application.prepaid.BalanceState;
import br.com.espacotucuma.desktop.application.prepaid.PackagePageQuery;
import br.com.espacotucuma.desktop.application.prepaid.PackageUsage;
import br.com.espacotucuma.desktop.application.prepaid.PaymentForm;
import br.com.espacotucuma.desktop.application.prepaid.PrepaidPackage;
import br.com.espacotucuma.desktop.application.prepaid.SessionBalance;
import br.com.espacotucuma.desktop.domain.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SqlitePrepaidPackageRepository implements PrepaidPackageRepository {
  private final Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqlitePrepaidPackageRepository(Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public Page<PrepaidPackage> searchPackages(PackagePageQuery query) {
    Objects.requireNonNull(query, "query");
    String condition = "(?='' OR nome LIKE ? ESCAPE '\\') AND (?=1 OR ativo=1)";
    String term = "%" + escapeLike(query.searchText()) + "%";
    try {
      long total;
      try (PreparedStatement count =
          connection.prepareStatement("SELECT COUNT(*) FROM pacote WHERE " + condition)) {
        bindPackageFilter(count, query, term);
        try (ResultSet row = count.executeQuery()) {
          row.next();
          total = row.getLong(1);
        }
      }
      List<PrepaidPackage> items = new ArrayList<>();
      try (PreparedStatement select =
          connection.prepareStatement(
              "SELECT id,nome,sessoes_contratadas,validade_dias,preco_centavos,ativo,criado_em_utc,atualizado_em_utc "
                  + "FROM pacote WHERE "
                  + condition
                  + " ORDER BY nome COLLATE NOCASE,id LIMIT ? OFFSET ?")) {
        bindPackageFilter(select, query, term);
        select.setInt(4, query.pageSize());
        select.setLong(5, (long) query.pageIndex() * query.pageSize());
        try (ResultSet rows = select.executeQuery()) {
          while (rows.next()) items.add(readPackage(rows));
        }
      }
      return new Page<>(items, total, query.pageIndex(), query.pageSize());
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível consultar os pacotes.", failure);
    }
  }

  @Override
  public Optional<PrepaidPackage> findPackage(UUID id) {
    Objects.requireNonNull(id, "id");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,nome,sessoes_contratadas,validade_dias,preco_centavos,ativo,criado_em_utc,atualizado_em_utc FROM pacote WHERE id=?")) {
      statement.setString(1, id.toString());
      try (ResultSet row = statement.executeQuery()) {
        return row.next() ? Optional.of(readPackage(row)) : Optional.empty();
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível consultar o pacote.", failure);
    }
  }

  @Override
  public void insertPackage(PrepaidPackage prepaidPackage) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO pacote (id,nome,sessoes_contratadas,validade_dias,preco_centavos,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,?,?,?,?,?,?,?)")) {
      bindPackage(statement, prepaidPackage);
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível cadastrar o pacote.", failure);
    }
  }

  @Override
  public void updatePackage(PrepaidPackage prepaidPackage) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE pacote SET nome=?,sessoes_contratadas=?,validade_dias=?,preco_centavos=?,atualizado_em_utc=? WHERE id=? AND ativo=1")) {
      statement.setString(1, prepaidPackage.name());
      statement.setInt(2, prepaidPackage.sessions());
      statement.setInt(3, prepaidPackage.validityDays());
      statement.setLong(4, cents(prepaidPackage.price()));
      statement.setString(5, prepaidPackage.updatedAt().toString());
      statement.setString(6, prepaidPackage.id().toString());
      if (statement.executeUpdate() != 1)
        throw new IllegalStateException("O pacote não existe ou está inativo.");
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível atualizar o pacote.", failure);
    }
  }

  @Override
  public void deactivatePackage(UUID id, Instant at) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE pacote SET ativo=0,atualizado_em_utc=? WHERE id=? AND ativo=1")) {
      statement.setString(1, at.toString());
      statement.setString(2, id.toString());
      if (statement.executeUpdate() != 1)
        throw new IllegalStateException("O pacote já está inativo.");
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível inativar o pacote.", failure);
    }
  }

  @Override
  public Page<SessionBalance> searchBalances(String clientSearch, int pageIndex, int pageSize) {
    String search = Objects.requireNonNull(clientSearch, "clientSearch").strip();
    if (pageIndex < 0 || pageSize < 1 || pageSize > 200) {
      throw new IllegalArgumentException("A página de saldos é inválida.");
    }
    String condition = "(?='' OR c.nome LIKE ? ESCAPE '\\' OR c.codigo_interno LIKE ? ESCAPE '\\')";
    String term = "%" + escapeLike(search) + "%";
    try {
      long total;
      try (PreparedStatement count =
          connection.prepareStatement(
              "SELECT COUNT(*) FROM saldo_sessoes ss JOIN cliente c ON c.id=ss.cliente_id WHERE "
                  + condition)) {
        bindClientFilter(count, search, term);
        try (ResultSet row = count.executeQuery()) {
          row.next();
          total = row.getLong(1);
        }
      }
      List<SessionBalance> balances = new ArrayList<>();
      try (PreparedStatement select =
          connection.prepareStatement(
              "SELECT ss.id,ss.cliente_id,c.nome AS cliente_nome,ss.pacote_id,ss.nome_pacote_snapshot,"
                  + "ss.sessoes_contratadas,ss.sessoes_restantes,ss.valido_ate,ss.preco_pago_centavos,"
                  + "ss.forma_pagamento,ss.estado,ss.adquirido_em_utc,ss.criado_por_usuario_id "
                  + "FROM saldo_sessoes ss JOIN cliente c ON c.id=ss.cliente_id WHERE "
                  + condition
                  + " ORDER BY ss.valido_ate,c.nome COLLATE NOCASE,ss.adquirido_em_utc LIMIT ? OFFSET ?")) {
        bindClientFilter(select, search, term);
        select.setInt(4, pageSize);
        select.setLong(5, (long) pageIndex * pageSize);
        try (ResultSet rows = select.executeQuery()) {
          while (rows.next()) balances.add(readBalance(rows));
        }
      }
      return new Page<>(balances, total, pageIndex, pageSize);
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível consultar os saldos de sessões.", failure);
    }
  }

  @Override
  public Optional<SessionBalance> findBalance(UUID id) {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT ss.id,ss.cliente_id,c.nome AS cliente_nome,ss.pacote_id,ss.nome_pacote_snapshot,"
                + "ss.sessoes_contratadas,ss.sessoes_restantes,ss.valido_ate,ss.preco_pago_centavos,"
                + "ss.forma_pagamento,ss.estado,ss.adquirido_em_utc,ss.criado_por_usuario_id "
                + "FROM saldo_sessoes ss JOIN cliente c ON c.id=ss.cliente_id WHERE ss.id=?")) {
      statement.setString(1, id.toString());
      try (ResultSet row = statement.executeQuery()) {
        return row.next() ? Optional.of(readBalance(row)) : Optional.empty();
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível consultar o saldo de sessões.", failure);
    }
  }

  @Override
  public void contract(SessionBalance balance) {
    requireTransaction();
    String sql =
        "INSERT INTO saldo_sessoes (id,cliente_id,pacote_id,sessoes_contratadas,sessoes_restantes,"
            + "adquirido_em_utc,valido_ate,estado,criado_por_usuario_id,nome_pacote_snapshot,preco_pago_centavos,forma_pagamento) "
            + "VALUES (?,?,?,?,?,?,?,'ATIVO',?,?,?,?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, balance.id().toString());
      statement.setString(2, balance.clientId().toString());
      statement.setString(3, balance.packageId().toString());
      statement.setInt(4, balance.sessionsContracted());
      statement.setInt(5, balance.sessionsRemaining());
      statement.setString(6, balance.purchasedAt().toString());
      statement.setString(7, balance.expiresOn().toString());
      statement.setString(8, balance.purchasedBy().toString());
      statement.setString(9, balance.packageName());
      statement.setLong(10, cents(balance.paidPrice()));
      statement.setString(11, balance.paymentForm().name());
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível contratar o pacote.", failure);
    }
  }

  @Override
  public List<AttendanceOption> eligibleAttendances(UUID clientId) {
    String sql =
        "SELECT DISTINCT a.id,a.cliente_id,c.nome AS cliente_nome,p.nome AS profissional_nome,"
            + "COALESCE((SELECT ats.nome_snapshot FROM atendimento_servico ats WHERE ats.atendimento_id=a.id ORDER BY ats.rowid LIMIT 1),'Atendimento') AS servico_nome,"
            + "a.iniciado_em_utc,a.estado FROM atendimento a JOIN cliente c ON c.id=a.cliente_id "
            + "JOIN profissional p ON p.id=a.profissional_id WHERE a.cliente_id=? AND a.estado='EM_ANDAMENTO' "
            + "AND EXISTS (SELECT 1 FROM atendimento_servico ats WHERE ats.atendimento_id=a.id) "
            + "AND NOT EXISTS (SELECT 1 FROM utilizacao_pacote u WHERE u.atendimento_id=a.id) "
            + "ORDER BY a.iniciado_em_utc";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, clientId.toString());
      List<AttendanceOption> result = new ArrayList<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) result.add(readAttendance(rows));
      }
      return List.copyOf(result);
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível consultar atendimentos abertos.", failure);
    }
  }

  @Override
  public Optional<AttendanceOption> findAttendance(UUID attendanceId) {
    String sql =
        "SELECT a.id,a.cliente_id,c.nome AS cliente_nome,p.nome AS profissional_nome,"
            + "COALESCE((SELECT ats.nome_snapshot FROM atendimento_servico ats WHERE ats.atendimento_id=a.id ORDER BY ats.rowid LIMIT 1),'Atendimento') AS servico_nome,"
            + "a.iniciado_em_utc,a.estado FROM atendimento a JOIN cliente c ON c.id=a.cliente_id "
            + "JOIN profissional p ON p.id=a.profissional_id WHERE a.id=?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, attendanceId.toString());
      try (ResultSet row = statement.executeQuery()) {
        return row.next() ? Optional.of(readAttendance(row)) : Optional.empty();
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível consultar o atendimento.", failure);
    }
  }

  @Override
  public void consume(
      UUID balanceId,
      UUID attendanceId,
      UUID usageId,
      int sessions,
      UUID userId,
      Instant at,
      LocalDate today) {
    requireTransaction();
    String debit =
        "UPDATE saldo_sessoes SET sessoes_restantes=sessoes_restantes-?, "
            + "estado=CASE WHEN sessoes_restantes-?=0 THEN 'ESGOTADO' ELSE 'ATIVO' END "
            + "WHERE id=? AND estado='ATIVO' AND valido_ate>=? AND sessoes_restantes>=? "
            + "AND EXISTS (SELECT 1 FROM atendimento a WHERE a.id=? AND a.cliente_id=saldo_sessoes.cliente_id AND a.estado='EM_ANDAMENTO')";
    try (PreparedStatement statement = connection.prepareStatement(debit)) {
      statement.setInt(1, sessions);
      statement.setInt(2, sessions);
      statement.setString(3, balanceId.toString());
      statement.setString(4, today.toString());
      statement.setInt(5, sessions);
      statement.setString(6, attendanceId.toString());
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException(
            "O pacote está vencido, esgotado ou não corresponde ao atendimento.");
      }
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO utilizacao_pacote (id,saldo_sessoes_id,atendimento_id,sessoes_utilizadas,utilizado_em_utc,criado_por_usuario_id) VALUES (?,?,?,?,?,?)")) {
        insert.setString(1, usageId.toString());
        insert.setString(2, balanceId.toString());
        insert.setString(3, attendanceId.toString());
        insert.setInt(4, sessions);
        insert.setString(5, at.toString());
        insert.setString(6, userId.toString());
        insert.executeUpdate();
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível consumir a sessão do pacote.", failure);
    }
  }

  @Override
  public List<PackageUsage> history(UUID balanceId) {
    return historyQuery("u.saldo_sessoes_id=?", balanceId.toString());
  }

  @Override
  public Optional<PackageUsage> historyForUsage(UUID usageId) {
    List<PackageUsage> result = historyQuery("u.id=?", usageId.toString());
    return result.stream().findFirst();
  }

  private List<PackageUsage> historyQuery(String condition, String id) {
    String sql =
        "SELECT u.id,u.atendimento_id,a.estado,u.sessoes_utilizadas,u.utilizado_em_utc,"
            + "r.id AS reversao_id,r.motivo AS motivo_reversao FROM utilizacao_pacote u "
            + "JOIN atendimento a ON a.id=u.atendimento_id LEFT JOIN reversao_utilizacao_pacote r "
            + "ON r.utilizacao_pacote_id=u.id WHERE "
            + condition
            + " ORDER BY u.utilizado_em_utc DESC,u.id";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, id);
      List<PackageUsage> result = new ArrayList<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          result.add(
              new PackageUsage(
                  UUID.fromString(rows.getString("id")),
                  UUID.fromString(rows.getString("atendimento_id")),
                  rows.getString("estado"),
                  rows.getInt("sessoes_utilizadas"),
                  Instant.parse(rows.getString("utilizado_em_utc")),
                  rows.getString("reversao_id") != null,
                  rows.getString("motivo_reversao")));
        }
      }
      return List.copyOf(result);
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível consultar o extrato do pacote.", failure);
    }
  }

  @Override
  public void reverse(UUID usageId, String reason, UUID userId, Instant at, LocalDate today) {
    requireTransaction();
    PackageUsage usage =
        historyForUsage(usageId)
            .orElseThrow(() -> new IllegalArgumentException("Utilização não encontrada."));
    if (usage.reversed()) throw new IllegalStateException("A utilização já foi revertida.");
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE saldo_sessoes SET sessoes_restantes=sessoes_restantes+?,estado='ATIVO' "
                + "WHERE id=(SELECT saldo_sessoes_id FROM utilizacao_pacote WHERE id=?) "
                + "AND estado IN ('ATIVO','ESGOTADO') AND valido_ate>=? AND sessoes_restantes+?<=sessoes_contratadas")) {
      update.setInt(1, usage.sessionsUsed());
      update.setString(2, usageId.toString());
      update.setString(3, today.toString());
      update.setInt(4, usage.sessionsUsed());
      if (update.executeUpdate() != 1) {
        throw new IllegalStateException(
            "Não é permitido repor sessão em pacote vencido ou cancelado.");
      }
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO reversao_utilizacao_pacote (id,utilizacao_pacote_id,motivo,criado_por_usuario_id,criado_em_utc) VALUES (?,?,?,?,?)")) {
        insert.setString(1, UUID.randomUUID().toString());
        insert.setString(2, usageId.toString());
        insert.setString(3, reason);
        insert.setString(4, userId.toString());
        insert.setString(5, at.toString());
        insert.executeUpdate();
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível reverter a utilização do pacote.", failure);
    }
  }

  private void bindPackageFilter(PreparedStatement statement, PackagePageQuery query, String term)
      throws SQLException {
    statement.setString(1, query.searchText());
    statement.setString(2, term);
    statement.setInt(3, query.includeInactive() ? 1 : 0);
  }

  private void bindClientFilter(PreparedStatement statement, String search, String term)
      throws SQLException {
    statement.setString(1, search);
    statement.setString(2, term);
    statement.setString(3, term);
  }

  private PrepaidPackage readPackage(ResultSet row) throws SQLException {
    return new PrepaidPackage(
        UUID.fromString(row.getString("id")),
        row.getString("nome"),
        row.getInt("sessoes_contratadas"),
        row.getInt("validade_dias"),
        new Money(BigDecimal.valueOf(row.getLong("preco_centavos"), 2)),
        row.getInt("ativo") == 1,
        Instant.parse(row.getString("criado_em_utc")),
        Instant.parse(row.getString("atualizado_em_utc")));
  }

  private SessionBalance readBalance(ResultSet row) throws SQLException {
    return new SessionBalance(
        UUID.fromString(row.getString("id")),
        UUID.fromString(row.getString("cliente_id")),
        row.getString("cliente_nome"),
        UUID.fromString(row.getString("pacote_id")),
        row.getString("nome_pacote_snapshot"),
        row.getInt("sessoes_contratadas"),
        row.getInt("sessoes_restantes"),
        LocalDate.parse(row.getString("valido_ate")),
        new Money(BigDecimal.valueOf(row.getLong("preco_pago_centavos"), 2)),
        PaymentForm.valueOf(row.getString("forma_pagamento")),
        BalanceState.valueOf(row.getString("estado")),
        Instant.parse(row.getString("adquirido_em_utc")),
        UUID.fromString(row.getString("criado_por_usuario_id")));
  }

  private AttendanceOption readAttendance(ResultSet row) throws SQLException {
    return new AttendanceOption(
        UUID.fromString(row.getString("id")),
        UUID.fromString(row.getString("cliente_id")),
        row.getString("cliente_nome"),
        row.getString("profissional_nome"),
        row.getString("servico_nome"),
        Instant.parse(row.getString("iniciado_em_utc")),
        row.getString("estado"));
  }

  private void bindPackage(PreparedStatement statement, PrepaidPackage prepaidPackage)
      throws SQLException {
    statement.setString(1, prepaidPackage.id().toString());
    statement.setString(2, prepaidPackage.name());
    statement.setInt(3, prepaidPackage.sessions());
    statement.setInt(4, prepaidPackage.validityDays());
    statement.setLong(5, cents(prepaidPackage.price()));
    statement.setInt(6, prepaidPackage.active() ? 1 : 0);
    statement.setString(7, prepaidPackage.createdAt().toString());
    statement.setString(8, prepaidPackage.updatedAt().toString());
  }

  private long cents(Money money) {
    return money.amount().movePointRight(2).longValueExact();
  }

  private String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private void requireTransaction() {
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException("A operação de pacote exige uma transação SQLite.");
    }
  }
}
