package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.attendance.Attendance;
import br.com.espacotucuma.desktop.application.attendance.AttendanceServiceLine;
import br.com.espacotucuma.desktop.application.attendance.AttendanceStatus;
import br.com.espacotucuma.desktop.application.commission.CommissionDecision;
import br.com.espacotucuma.desktop.application.port.attendance.AttendanceRepository;
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

public final class SqliteAttendanceRepository implements AttendanceRepository {
  private final java.sql.Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteAttendanceRepository(java.sql.Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public Optional<Attendance> findById(UUID id) {
    return find("id", Objects.requireNonNull(id, "id").toString());
  }

  @Override
  public Optional<Attendance> findByAppointment(UUID appointmentId) {
    return find(
        "agendamento_id", Objects.requireNonNull(appointmentId, "appointmentId").toString());
  }

  private Optional<Attendance> find(String column, String value) {
    return unitOfWork.read(
        () -> {
          try (PreparedStatement statement =
              connection.prepareStatement("SELECT * FROM atendimento WHERE " + column + "=?")) {
            statement.setString(1, value);
            try (ResultSet rows = statement.executeQuery()) {
              return rows.next() ? Optional.of(readAttendance(rows)) : Optional.empty();
            }
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível consultar o atendimento local.", failure);
          }
        });
  }

  @Override
  public List<AttendanceServiceLine> findServices(UUID attendanceId) {
    return unitOfWork.read(
        () -> {
          String sql =
              "SELECT * FROM atendimento_servico WHERE atendimento_id=? ORDER BY nome_snapshot COLLATE NOCASE,id";
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, attendanceId.toString());
            List<AttendanceServiceLine> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next()) result.add(readService(rows));
            }
            return List.copyOf(result);
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível consultar os serviços do atendimento.", failure);
          }
        });
  }

  @Override
  public Optional<AttendanceServiceLine> findService(UUID attendanceId, UUID serviceId) {
    return unitOfWork.read(
        () -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT * FROM atendimento_servico WHERE atendimento_id=? AND servico_id=?")) {
            statement.setString(1, attendanceId.toString());
            statement.setString(2, serviceId.toString());
            try (ResultSet rows = statement.executeQuery()) {
              return rows.next() ? Optional.of(readService(rows)) : Optional.empty();
            }
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível consultar o serviço do atendimento.", failure);
          }
        });
  }

  @Override
  public void insert(Attendance attendance) {
    requireTransaction();
    String sql =
        "INSERT INTO atendimento (id,agendamento_id,cliente_id,profissional_id,estado,subtotal_centavos,"
            + "desconto_centavos,total_centavos,iniciado_em_utc,concluido_em_utc,criado_por_usuario_id,criado_em_utc) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, attendance.id().toString());
      statement.setString(2, attendance.appointmentId().toString());
      statement.setString(3, attendance.clientId().toString());
      statement.setString(4, attendance.professionalId().toString());
      statement.setString(5, attendance.status().name());
      statement.setLong(6, cents(attendance.subtotal()));
      statement.setLong(7, cents(attendance.discount()));
      statement.setLong(8, cents(attendance.total()));
      statement.setString(9, attendance.startedAt().toString());
      statement.setString(
          10, attendance.completedAt() == null ? null : attendance.completedAt().toString());
      statement.setString(11, attendance.createdBy().toString());
      statement.setString(12, attendance.startedAt().toString());
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível iniciar o atendimento local.", failure);
    }
  }

  @Override
  public void insertService(AttendanceServiceLine service) {
    requireTransaction();
    String source =
        service.commission().source() == CommissionDecision.Source.PROFESSIONAL_SERVICE
            ? "PROFISSIONAL_SERVICO"
            : "SERVICO";
    String sql =
        "INSERT INTO atendimento_servico (id,atendimento_id,servico_id,nome_snapshot,quantidade,"
            + "preco_unitario_centavos,comissao_pontos_base,comissao_regra_id,comissao_origem) "
            + "VALUES (?,?,?,?,?,?,?,?,?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, service.id().toString());
      statement.setString(2, service.attendanceId().toString());
      statement.setString(3, service.serviceId().toString());
      statement.setString(4, service.nameSnapshot());
      statement.setInt(5, service.quantity());
      statement.setLong(6, cents(service.unitPrice()));
      statement.setInt(7, service.commission().percentageBasisPoints());
      statement.setString(
          8,
          service.commission().ruleId() == null ? null : service.commission().ruleId().toString());
      statement.setString(9, source);
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível registrar o serviço congelado.", failure);
    }
  }

  @Override
  public void updateTotals(Attendance attendance) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE atendimento SET subtotal_centavos=?,desconto_centavos=?,total_centavos=?,atualizado_em_utc=? "
                + "WHERE id=? AND estado='EM_ANDAMENTO'")) {
      statement.setLong(1, cents(attendance.subtotal()));
      statement.setLong(2, cents(attendance.discount()));
      statement.setLong(3, cents(attendance.total()));
      statement.setString(4, attendance.updatedAt().toString());
      statement.setString(5, attendance.id().toString());
      requireUpdated(statement.executeUpdate());
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "Não foi possível atualizar os valores do atendimento.", failure);
    }
  }

  @Override
  public void updateNotes(Attendance attendance) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE atendimento SET observacoes_administrativas=?,atualizado_em_utc=? "
                + "WHERE id=? AND estado='EM_ANDAMENTO'")) {
      statement.setString(1, attendance.notes());
      statement.setString(2, attendance.updatedAt().toString());
      statement.setString(3, attendance.id().toString());
      requireUpdated(statement.executeUpdate());
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "Não foi possível salvar as observações do atendimento.", failure);
    }
  }

  @Override
  public void complete(Attendance attendance) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE atendimento SET estado='CONCLUIDO',subtotal_centavos=?,desconto_centavos=?,"
                + "total_centavos=?,concluido_em_utc=?,observacoes_administrativas=?,atualizado_em_utc=? "
                + "WHERE id=? AND estado='EM_ANDAMENTO'")) {
      statement.setLong(1, cents(attendance.subtotal()));
      statement.setLong(2, cents(attendance.discount()));
      statement.setLong(3, cents(attendance.total()));
      statement.setString(4, attendance.completedAt().toString());
      statement.setString(5, attendance.notes());
      statement.setString(6, attendance.updatedAt().toString());
      statement.setString(7, attendance.id().toString());
      requireUpdated(statement.executeUpdate());
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível concluir o atendimento local.", failure);
    }
  }

  private Attendance readAttendance(ResultSet row) throws SQLException {
    return new Attendance(
        UUID.fromString(row.getString("id")),
        UUID.fromString(row.getString("agendamento_id")),
        UUID.fromString(row.getString("cliente_id")),
        UUID.fromString(row.getString("profissional_id")),
        AttendanceStatus.valueOf(row.getString("estado")),
        money(row.getLong("subtotal_centavos")),
        money(row.getLong("desconto_centavos")),
        money(row.getLong("total_centavos")),
        row.getString("observacoes_administrativas"),
        Instant.parse(row.getString("iniciado_em_utc")),
        row.getString("concluido_em_utc") == null
            ? null
            : Instant.parse(row.getString("concluido_em_utc")),
        UUID.fromString(row.getString("criado_por_usuario_id")),
        Instant.parse(row.getString("atualizado_em_utc")));
  }

  private AttendanceServiceLine readService(ResultSet row) throws SQLException {
    String origin = row.getString("comissao_origem");
    String ruleId = row.getString("comissao_regra_id");
    return new AttendanceServiceLine(
        UUID.fromString(row.getString("id")),
        UUID.fromString(row.getString("atendimento_id")),
        UUID.fromString(row.getString("servico_id")),
        row.getString("nome_snapshot"),
        row.getInt("quantidade"),
        money(row.getLong("preco_unitario_centavos")),
        new CommissionDecision(
            row.getInt("comissao_pontos_base"),
            origin.equals("PROFISSIONAL_SERVICO")
                ? CommissionDecision.Source.PROFESSIONAL_SERVICE
                : CommissionDecision.Source.SERVICE,
            ruleId == null ? null : UUID.fromString(ruleId)));
  }

  private Money money(long cents) {
    return new Money(BigDecimal.valueOf(cents, 2));
  }

  private long cents(Money money) {
    return money.amount().movePointRight(2).longValueExact();
  }

  private void requireUpdated(int rows) {
    if (rows != 1) throw new IllegalStateException("O atendimento não está mais em andamento.");
  }

  private void requireTransaction() {
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException("A gravação do atendimento exige uma transação local.");
    }
  }
}
