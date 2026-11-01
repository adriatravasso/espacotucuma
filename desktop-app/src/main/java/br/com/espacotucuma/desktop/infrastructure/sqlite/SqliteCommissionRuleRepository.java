package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.commission.ServiceCommissionRule;
import br.com.espacotucuma.desktop.application.port.commission.CommissionRuleRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SqliteCommissionRuleRepository implements CommissionRuleRepository {
  private final Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteCommissionRuleRepository(Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public Optional<ServiceCommissionRule> findByProfessionalAndService(
      UUID professionalId, UUID serviceId) {
    return find(
        "profissional_id=? AND servico_id=? AND ativo=1",
        professionalId.toString(),
        serviceId.toString());
  }

  @Override
  public Optional<ServiceCommissionRule> findById(UUID id) {
    return find("id=?", id.toString());
  }

  @Override
  public Optional<ServiceCommissionRule> findAnyByProfessionalAndService(
      UUID professionalId, UUID serviceId) {
    return find(
        "profissional_id=? AND servico_id=?", professionalId.toString(), serviceId.toString());
  }

  private Optional<ServiceCommissionRule> find(String condition, String... values) {
    String sql =
        "SELECT id,profissional_id,servico_id,percentual_pontos_base,ativo,criado_em_utc,atualizado_em_utc "
            + "FROM regra_comissao_profissional_servico WHERE "
            + condition;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < values.length; index++)
        statement.setString(index + 1, values[index]);
      try (ResultSet row = statement.executeQuery()) {
        return row.next() ? Optional.of(readRule(row)) : Optional.empty();
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível consultar regras de comissão.", failure);
    }
  }

  @Override
  public void save(ServiceCommissionRule rule) {
    requireTransaction();
    String sql =
        "INSERT INTO regra_comissao_profissional_servico "
            + "(id,profissional_id,servico_id,percentual_pontos_base,ativo,criado_em_utc,atualizado_em_utc) "
            + "VALUES (?,?,?,?,?,?,?) ON CONFLICT(profissional_id,servico_id) DO UPDATE SET "
            + "percentual_pontos_base=excluded.percentual_pontos_base,ativo=1,atualizado_em_utc=excluded.atualizado_em_utc "
            + "WHERE id=excluded.id";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, rule.id().toString());
      statement.setString(2, rule.professionalId().toString());
      statement.setString(3, rule.serviceId().toString());
      statement.setInt(4, rule.percentageBasisPoints());
      statement.setInt(5, 1);
      statement.setString(6, rule.createdAt().toString());
      statement.setString(7, rule.updatedAt().toString());
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException(
            "Já existe uma regra de comissão para este profissional e serviço.");
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível salvar a regra de comissão.", failure);
    }
  }

  @Override
  public void deactivate(UUID id, Instant at) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE regra_comissao_profissional_servico SET ativo=0,atualizado_em_utc=? WHERE id=? AND ativo=1")) {
      statement.setString(1, at.toString());
      statement.setString(2, id.toString());
      if (statement.executeUpdate() != 1)
        throw new IllegalStateException("A regra já está inativa.");
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível inativar a regra de comissão.", failure);
    }
  }

  @Override
  public void saveAttendanceSnapshot(
      UUID attendanceServiceId, int percentageBasisPoints, UUID ruleId) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE atendimento_servico SET comissao_pontos_base=?,comissao_regra_id=?,comissao_origem=? "
                + "WHERE id=? AND EXISTS (SELECT 1 FROM atendimento a "
                + "WHERE a.id=atendimento_servico.atendimento_id AND a.estado='EM_ANDAMENTO')")) {
      statement.setInt(1, percentageBasisPoints);
      statement.setString(2, ruleId == null ? null : ruleId.toString());
      statement.setString(3, ruleId == null ? "SERVICO" : "PROFISSIONAL_SERVICO");
      statement.setString(4, attendanceServiceId.toString());
      if (statement.executeUpdate() != 1) {
        throw new IllegalArgumentException("O serviço do atendimento não foi encontrado.");
      }
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "Não foi possível registrar o snapshot da comissão.", failure);
    }
  }

  private ServiceCommissionRule readRule(ResultSet row) throws SQLException {
    return new ServiceCommissionRule(
        UUID.fromString(row.getString("id")),
        UUID.fromString(row.getString("profissional_id")),
        UUID.fromString(row.getString("servico_id")),
        row.getInt("percentual_pontos_base"),
        row.getInt("ativo") == 1,
        Instant.parse(row.getString("criado_em_utc")),
        Instant.parse(row.getString("atualizado_em_utc")));
  }

  private void requireTransaction() {
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException(
          "A alteração da regra de comissão exige uma transação SQLite.");
    }
  }
}
