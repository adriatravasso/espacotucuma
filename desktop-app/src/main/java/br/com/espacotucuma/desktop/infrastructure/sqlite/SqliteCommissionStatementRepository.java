package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.commission.CommissionCandidate;
import br.com.espacotucuma.desktop.application.commission.CommissionStatementLine;
import br.com.espacotucuma.desktop.application.port.commission.CommissionStatementRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SqliteCommissionStatementRepository implements CommissionStatementRepository {
  private final Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteCommissionStatementRepository(Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection);
    this.unitOfWork = Objects.requireNonNull(unitOfWork);
  }

  @Override
  public List<CommissionCandidate> findEligible(YearMonth month) {
    return unitOfWork.read(
        () -> {
          String sql =
              "SELECT l.id service_line_id,a.id attendance_id,a.profissional_id,p.nome profissional_nome,"
                  + "l.nome_snapshot service_name,a.concluido_em_utc,l.preco_unitario_centavos*l.quantidade gross_cents,"
                  + "a.desconto_centavos,l.comissao_pontos_base FROM atendimento_servico l "
                  + "JOIN atendimento a ON a.id=l.atendimento_id JOIN profissional p ON p.id=a.profissional_id "
                  + "WHERE a.estado='CONCLUIDO' AND a.concluido_em_utc>=? AND a.concluido_em_utc<? "
                  + "AND NOT EXISTS (SELECT 1 FROM comissao c WHERE c.atendimento_servico_id=l.id) "
                  + "ORDER BY a.id,l.id";
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            java.time.ZoneId businessZone = java.time.ZoneId.of("America/Manaus");
            statement.setString(
                1, month.atDay(1).atStartOfDay(businessZone).toInstant().toString());
            statement.setString(
                2, month.plusMonths(1).atDay(1).atStartOfDay(businessZone).toInstant().toString());
            try (ResultSet rows = statement.executeQuery()) {
              List<CommissionCandidate> result = new ArrayList<>();
              while (rows.next())
                result.add(
                    new CommissionCandidate(
                        UUID.fromString(rows.getString("service_line_id")),
                        UUID.fromString(rows.getString("attendance_id")),
                        UUID.fromString(rows.getString("profissional_id")),
                        rows.getString("profissional_nome"),
                        rows.getString("service_name"),
                        Instant.parse(rows.getString("concluido_em_utc")),
                        rows.getLong("gross_cents"),
                        rows.getLong("desconto_centavos"),
                        rows.getInt("comissao_pontos_base")));
              return List.copyOf(result);
            }
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível consultar atendimentos elegíveis para comissão.", failure);
          }
        });
  }

  @Override
  public void closeCompetency(YearMonth month, UUID actorId, Instant at) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO competencia_comissao (competencia,estado,fechada_por_usuario_id,fechada_em_utc) VALUES (?,'FECHADA',?,?)")) {
      statement.setString(1, month.toString());
      statement.setString(2, actorId.toString());
      statement.setString(3, at.toString());
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "Não foi possível fechar a competência de comissão.", failure);
    }
  }

  @Override
  public void insert(CommissionStatementLine line, YearMonth month, Instant calculatedAt) {
    requireTransaction();
    String sql =
        "INSERT INTO comissao (id,atendimento_servico_id,profissional_id,base_centavos,percentual_pontos_base,valor_centavos,estado,apurada_em_utc,competencia,profissional_nome_snapshot) VALUES (?,?,?,?,?,?,'PENDENTE',?,?,?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, line.commissionId().toString());
      statement.setString(2, line.serviceLineId().toString());
      statement.setString(3, line.professionalId().toString());
      statement.setLong(4, line.commissionBaseCents());
      statement.setInt(5, line.percentageBasisPoints());
      statement.setLong(6, line.commissionCents());
      statement.setString(7, calculatedAt.toString());
      statement.setString(8, month.toString());
      statement.setString(9, line.professionalName());
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível materializar a comissão apurada.", failure);
    }
  }

  @Override
  public boolean isClosed(YearMonth month) {
    return unitOfWork.read(
        () -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT 1 FROM competencia_comissao WHERE competencia=? AND estado='FECHADA'")) {
            statement.setString(1, month.toString());
            try (ResultSet rows = statement.executeQuery()) {
              return rows.next();
            }
          } catch (SQLException failure) {
            throw new IllegalStateException("Não foi possível consultar a competência.", failure);
          }
        });
  }

  @Override
  public List<CommissionStatementLine> findStatement(YearMonth month, UUID professionalId) {
    return unitOfWork.read(
        () -> {
          String sql =
              "SELECT c.id commission_id,l.id service_line_id,a.id attendance_id,p.id professional_id,COALESCE(c.profissional_nome_snapshot,p.nome) professional_name,"
                  + "l.nome_snapshot service_name,a.concluido_em_utc,l.preco_unitario_centavos*l.quantidade gross_cents,"
                  + "(l.preco_unitario_centavos*l.quantidade-c.base_centavos) discount_cents,c.base_centavos,c.percentual_pontos_base,c.valor_centavos,c.estado "
                  + "FROM comissao c JOIN atendimento_servico l ON l.id=c.atendimento_servico_id "
                  + "JOIN atendimento a ON a.id=l.atendimento_id JOIN profissional p ON p.id=c.profissional_id "
                  + "WHERE c.competencia=? AND (? IS NULL OR c.profissional_id=?) ORDER BY a.concluido_em_utc,p.nome,l.nome_snapshot,l.id";
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, month.toString());
            String professional = professionalId == null ? null : professionalId.toString();
            statement.setString(2, professional);
            statement.setString(3, professional);
            try (ResultSet rows = statement.executeQuery()) {
              List<CommissionStatementLine> result = new ArrayList<>();
              while (rows.next())
                result.add(
                    new CommissionStatementLine(
                        UUID.fromString(rows.getString("commission_id")),
                        UUID.fromString(rows.getString("service_line_id")),
                        UUID.fromString(rows.getString("attendance_id")),
                        UUID.fromString(rows.getString("professional_id")),
                        rows.getString("professional_name"),
                        rows.getString("service_name"),
                        Instant.parse(rows.getString("concluido_em_utc")),
                        rows.getLong("gross_cents"),
                        rows.getLong("discount_cents"),
                        rows.getLong("base_centavos"),
                        rows.getInt("percentual_pontos_base"),
                        rows.getLong("valor_centavos"),
                        rows.getString("estado")));
              return List.copyOf(result);
            }
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível gerar o extrato de comissão.", failure);
          }
        });
  }

  private void requireTransaction() {
    if (!unitOfWork.isExecutingOnCurrentThread())
      throw new IllegalStateException("A apuração exige uma transação local.");
  }
}
