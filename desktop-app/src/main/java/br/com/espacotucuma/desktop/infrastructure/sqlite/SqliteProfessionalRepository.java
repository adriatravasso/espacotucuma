package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.Page;
import br.com.espacotucuma.desktop.application.port.professional.ProfessionalRepository;
import br.com.espacotucuma.desktop.application.professional.OneOffUnavailability;
import br.com.espacotucuma.desktop.application.professional.Professional;
import br.com.espacotucuma.desktop.application.professional.ProfessionalPageQuery;
import br.com.espacotucuma.desktop.application.professional.ProfessionalSchedule;
import br.com.espacotucuma.desktop.application.professional.WeeklyInterval;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SqliteProfessionalRepository implements ProfessionalRepository {
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
  private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
  private final Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteProfessionalRepository(Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public Page<Professional> searchPage(ProfessionalPageQuery query) {
    Objects.requireNonNull(query, "query");
    String condition = "(? = '' OR nome LIKE ? ESCAPE '\\') AND (? = 1 OR ativo = 1)";
    String term = "%" + escapeLike(query.searchText()) + "%";
    try {
      long total;
      try (PreparedStatement count =
          connection.prepareStatement("SELECT COUNT(*) FROM profissional WHERE " + condition)) {
        count.setString(1, query.searchText());
        count.setString(2, term);
        count.setInt(3, query.includeInactive() ? 1 : 0);
        try (ResultSet result = count.executeQuery()) {
          result.next();
          total = result.getLong(1);
        }
      }
      List<Professional> items = new ArrayList<>();
      try (PreparedStatement select =
          connection.prepareStatement(
              "SELECT id, nome, telefone, ativo, criado_em_utc, atualizado_em_utc "
                  + "FROM profissional WHERE "
                  + condition
                  + " ORDER BY nome COLLATE NOCASE, id LIMIT ? OFFSET ?")) {
        select.setString(1, query.searchText());
        select.setString(2, term);
        select.setInt(3, query.includeInactive() ? 1 : 0);
        select.setInt(4, query.pageSize());
        select.setLong(5, (long) query.pageIndex() * query.pageSize());
        try (ResultSet result = select.executeQuery()) {
          while (result.next()) items.add(readProfessional(result));
        }
      }
      return new Page<>(items, total, query.pageIndex(), query.pageSize());
    } catch (SQLException error) {
      throw new IllegalStateException("Não foi possível consultar profissionais.", error);
    }
  }

  @Override
  public Optional<Professional> findById(UUID id) {
    Objects.requireNonNull(id, "id");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id, nome, telefone, ativo, criado_em_utc, atualizado_em_utc "
                + "FROM profissional WHERE id = ?")) {
      statement.setString(1, id.toString());
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? Optional.of(readProfessional(result)) : Optional.empty();
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Não foi possível consultar o profissional.", error);
    }
  }

  @Override
  public List<Professional> findActiveForScheduling() {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id, nome, telefone, ativo, criado_em_utc, atualizado_em_utc "
                + "FROM profissional WHERE ativo = 1 ORDER BY nome COLLATE NOCASE, id")) {
      List<Professional> items = new ArrayList<>();
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) items.add(readProfessional(result));
      }
      return List.copyOf(items);
    } catch (SQLException error) {
      throw new IllegalStateException("Não foi possível consultar profissionais ativos.", error);
    }
  }

  @Override
  public boolean canReceive(UUID professionalId, LocalDateTime startsAt, LocalDateTime endsAt) {
    return findById(professionalId)
        .map(professional -> professional.canReceive(startsAt, endsAt))
        .orElse(false);
  }

  @Override
  public void insert(Professional professional) {
    Objects.requireNonNull(professional, "professional");
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO profissional (id, nome, telefone, ativo, criado_em_utc, atualizado_em_utc) "
                + "VALUES (?, ?, ?, ?, ?, ?)")) {
      bindBasic(statement, professional);
      statement.executeUpdate();
      replaceSchedule(professional);
    } catch (SQLException error) {
      throw new IllegalStateException("Não foi possível cadastrar o profissional.", error);
    }
  }

  @Override
  public void update(Professional professional) {
    Objects.requireNonNull(professional, "professional");
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE profissional SET nome = ?, telefone = ?, atualizado_em_utc = ? WHERE id = ?")) {
      statement.setString(1, professional.name());
      statement.setString(2, professional.phone());
      statement.setString(3, professional.updatedAt().toString());
      statement.setString(4, professional.id().toString());
      if (statement.executeUpdate() != 1) {
        throw new IllegalArgumentException("Profissional não encontrado: " + professional.id());
      }
      replaceSchedule(professional);
    } catch (SQLException error) {
      throw new IllegalStateException("Não foi possível atualizar o profissional.", error);
    }
  }

  @Override
  public void deactivate(UUID id, Instant at) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE profissional SET ativo = 0, atualizado_em_utc = ? WHERE id = ?")) {
      statement.setString(1, at.toString());
      statement.setString(2, id.toString());
      if (statement.executeUpdate() != 1) {
        throw new IllegalArgumentException("Profissional não encontrado: " + id);
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Não foi possível inativar o profissional.", error);
    }
  }

  private Professional readProfessional(ResultSet result) throws SQLException {
    UUID id = UUID.fromString(result.getString("id"));
    List<WeeklyInterval> weekly = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT dia_semana, inicio_local, fim_local FROM disponibilidade_profissional "
                + "WHERE profissional_id = ? AND ativo = 1 ORDER BY dia_semana, inicio_local")) {
      statement.setString(1, id.toString());
      try (ResultSet intervals = statement.executeQuery()) {
        while (intervals.next()) {
          int day = intervals.getInt("dia_semana");
          weekly.add(
              new WeeklyInterval(
                  DayOfWeek.of(day == 0 ? 7 : day),
                  LocalTime.parse(intervals.getString("inicio_local"), TIME),
                  LocalTime.parse(intervals.getString("fim_local"), TIME)));
        }
      }
    }
    List<OneOffUnavailability> absences = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT inicio_local, fim_local, motivo FROM indisponibilidade_profissional "
                + "WHERE profissional_id = ? ORDER BY inicio_local")) {
      statement.setString(1, id.toString());
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          absences.add(
              new OneOffUnavailability(
                  LocalDateTime.parse(rows.getString("inicio_local"), LOCAL),
                  LocalDateTime.parse(rows.getString("fim_local"), LOCAL),
                  rows.getString("motivo")));
        }
      }
    }
    return new Professional(
        id,
        result.getString("nome"),
        result.getString("telefone"),
        result.getInt("ativo") == 1,
        new ProfessionalSchedule(weekly, absences),
        Instant.parse(result.getString("criado_em_utc")),
        Instant.parse(result.getString("atualizado_em_utc")));
  }

  private void replaceSchedule(Professional professional) throws SQLException {
    try (PreparedStatement deleteWeekly =
            connection.prepareStatement(
                "DELETE FROM disponibilidade_profissional WHERE profissional_id = ?");
        PreparedStatement deleteAbsences =
            connection.prepareStatement(
                "DELETE FROM indisponibilidade_profissional WHERE profissional_id = ?")) {
      deleteWeekly.setString(1, professional.id().toString());
      deleteWeekly.executeUpdate();
      deleteAbsences.setString(1, professional.id().toString());
      deleteAbsences.executeUpdate();
    }
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO disponibilidade_profissional (id, profissional_id, dia_semana, inicio_local, fim_local, ativo) "
                + "VALUES (?, ?, ?, ?, ?, 1)")) {
      for (WeeklyInterval interval : professional.schedule().weeklyIntervals()) {
        insert.setString(1, UUID.randomUUID().toString());
        insert.setString(2, professional.id().toString());
        insert.setInt(3, interval.day().getValue() % 7);
        insert.setString(4, TIME.format(interval.startsAt()));
        insert.setString(5, TIME.format(interval.endsAt()));
        insert.addBatch();
      }
      insert.executeBatch();
    }
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO indisponibilidade_profissional "
                + "(id, profissional_id, inicio_local, fim_local, motivo, criado_em_utc) VALUES (?, ?, ?, ?, ?, ?)")) {
      for (OneOffUnavailability block : professional.schedule().unavailabilities()) {
        insert.setString(1, UUID.randomUUID().toString());
        insert.setString(2, professional.id().toString());
        insert.setString(3, LOCAL.format(block.startsAt()));
        insert.setString(4, LOCAL.format(block.endsAt()));
        insert.setString(5, block.reason());
        insert.setString(6, professional.updatedAt().toString());
        insert.addBatch();
      }
      insert.executeBatch();
    }
  }

  private void bindBasic(PreparedStatement statement, Professional professional)
      throws SQLException {
    statement.setString(1, professional.id().toString());
    statement.setString(2, professional.name());
    statement.setString(3, professional.phone());
    statement.setInt(4, professional.active() ? 1 : 0);
    statement.setString(5, professional.createdAt().toString());
    statement.setString(6, professional.updatedAt().toString());
  }

  private String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private void requireTransaction() {
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException("A gravação do profissional exige uma transação local.");
    }
  }
}
