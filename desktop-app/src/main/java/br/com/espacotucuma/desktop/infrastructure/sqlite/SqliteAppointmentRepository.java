package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.port.scheduling.AppointmentRepository;
import br.com.espacotucuma.desktop.application.scheduling.Appointment;
import br.com.espacotucuma.desktop.application.scheduling.AppointmentStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SqliteAppointmentRepository implements AppointmentRepository {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Manaus");
  private final Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteAppointmentRepository(Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public Optional<Appointment> findById(UUID id) {
    Objects.requireNonNull(id, "id");
    return unitOfWork.read(
        () -> {
          try (PreparedStatement statement =
              connection.prepareStatement("SELECT * FROM agendamento WHERE id=?")) {
            statement.setString(1, id.toString());
            try (ResultSet rows = statement.executeQuery()) {
              return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
          } catch (SQLException failure) {
            throw new IllegalStateException("Não foi possível consultar o agendamento.", failure);
          }
        });
  }

  @Override
  public List<Appointment> findForDay(LocalDate day, UUID professionalId) {
    Objects.requireNonNull(day, "day");
    return unitOfWork.read(
        () -> {
          String from = day.atStartOfDay(BUSINESS_ZONE).toInstant().toString();
          String until = day.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant().toString();
          String sql =
              "SELECT * FROM agendamento WHERE inicio_em_utc>=? AND inicio_em_utc<? "
                  + "AND (? IS NULL OR profissional_id=?) ORDER BY inicio_em_utc, profissional_id, id";
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, from);
            statement.setString(2, until);
            if (professionalId == null) {
              statement.setNull(3, java.sql.Types.VARCHAR);
              statement.setNull(4, java.sql.Types.VARCHAR);
            } else {
              statement.setString(3, professionalId.toString());
              statement.setString(4, professionalId.toString());
            }
            List<Appointment> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next()) result.add(read(rows));
            }
            return List.copyOf(result);
          } catch (SQLException failure) {
            throw new IllegalStateException("Não foi possível consultar a agenda do dia.", failure);
          }
        });
  }

  @Override
  public boolean hasConflict(
      UUID professionalId,
      LocalDateTime startsAt,
      LocalDateTime endsAt,
      UUID excludingAppointmentId) {
    Objects.requireNonNull(professionalId, "professionalId");
    String sql =
        "SELECT 1 FROM agendamento WHERE profissional_id=? "
            + "AND estado IN ('AGENDADO','CONFIRMADO','EM_ATENDIMENTO') "
            + "AND inicio_em_utc<? AND ?<fim_em_utc AND (? IS NULL OR id<>?) LIMIT 1";
    return unitOfWork.read(
        () -> {
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, professionalId.toString());
            statement.setString(2, toUtc(endsAt));
            statement.setString(3, toUtc(startsAt));
            if (excludingAppointmentId == null) {
              statement.setNull(4, java.sql.Types.VARCHAR);
              statement.setNull(5, java.sql.Types.VARCHAR);
            } else {
              statement.setString(4, excludingAppointmentId.toString());
              statement.setString(5, excludingAppointmentId.toString());
            }
            try (ResultSet rows = statement.executeQuery()) {
              return rows.next();
            }
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível verificar conflito de horário.", failure);
          }
        });
  }

  @Override
  public void insert(Appointment appointment) {
    requireTransaction();
    String sql =
        "INSERT INTO agendamento (id,cliente_id,profissional_id,servico_id,inicio_em_utc,fim_em_utc,"
            + "estado,observacao,criado_por_usuario_id,criado_em_utc,atualizado_em_utc) "
            + "VALUES (?,?,?,?,?,?,'AGENDADO',?,?,?,?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, appointment.id().toString());
      statement.setString(2, appointment.clientId().toString());
      statement.setString(3, appointment.professionalId().toString());
      statement.setString(4, appointment.serviceId().toString());
      statement.setString(5, toUtc(appointment.startsAt()));
      statement.setString(6, toUtc(appointment.endsAt()));
      statement.setString(7, appointment.notes());
      statement.setString(8, appointment.createdBy().toString());
      statement.setString(9, toUtc(appointment.createdAt()));
      statement.setString(10, toUtc(appointment.updatedAt()));
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível salvar o agendamento local.", failure);
    }
  }

  @Override
  public void updateTime(Appointment appointment) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE agendamento SET profissional_id=?,inicio_em_utc=?,fim_em_utc=?,atualizado_em_utc=? "
                + "WHERE id=? AND estado IN ('AGENDADO','CONFIRMADO')")) {
      statement.setString(1, appointment.professionalId().toString());
      statement.setString(2, toUtc(appointment.startsAt()));
      statement.setString(3, toUtc(appointment.endsAt()));
      statement.setString(4, toUtc(appointment.updatedAt()));
      statement.setString(5, appointment.id().toString());
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException(
            "O agendamento deixou de estar disponível para remarcação.");
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível remarcar o agendamento local.", failure);
    }
  }

  @Override
  public void cancel(UUID id, LocalDateTime updatedAt) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE agendamento SET estado='CANCELADO',atualizado_em_utc=? "
                + "WHERE id=? AND estado IN ('AGENDADO','CONFIRMADO')")) {
      statement.setString(1, toUtc(updatedAt));
      statement.setString(2, id.toString());
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException(
            "O agendamento deixou de estar disponível para cancelamento.");
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível cancelar o agendamento local.", failure);
    }
  }

  private Appointment read(ResultSet row) throws SQLException {
    return new Appointment(
        UUID.fromString(row.getString("id")),
        UUID.fromString(row.getString("cliente_id")),
        UUID.fromString(row.getString("profissional_id")),
        UUID.fromString(row.getString("servico_id")),
        local(row.getString("inicio_em_utc")),
        local(row.getString("fim_em_utc")),
        AppointmentStatus.valueOf(row.getString("estado")),
        row.getString("observacao"),
        UUID.fromString(row.getString("criado_por_usuario_id")),
        local(row.getString("criado_em_utc")),
        local(row.getString("atualizado_em_utc")));
  }

  private LocalDateTime local(String utc) {
    return java.time.Instant.parse(utc).atZone(BUSINESS_ZONE).toLocalDateTime();
  }

  private String toUtc(LocalDateTime local) {
    return local.atZone(BUSINESS_ZONE).toInstant().toString();
  }

  private void requireTransaction() {
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException("A gravação do agendamento exige uma transação local.");
    }
  }
}
