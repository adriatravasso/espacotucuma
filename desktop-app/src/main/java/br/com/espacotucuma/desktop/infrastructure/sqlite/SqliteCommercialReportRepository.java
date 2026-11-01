package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.port.reports.CommercialReportRepository;
import br.com.espacotucuma.desktop.application.reports.CommercialReportPage;
import br.com.espacotucuma.desktop.application.reports.CommercialReportPeriod;
import br.com.espacotucuma.desktop.application.reports.CommercialReportRow;
import br.com.espacotucuma.desktop.application.reports.CommercialReportSummary;
import br.com.espacotucuma.desktop.application.reports.CommercialReportType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;

public final class SqliteCommercialReportRepository implements CommercialReportRepository {
  private static final int MAX_PAGE_SIZE = 200;
  private final Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteCommercialReportRepository(Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection);
    this.unitOfWork = Objects.requireNonNull(unitOfWork);
  }

  @Override
  public CommercialReportPage find(
      CommercialReportType type, CommercialReportPeriod period, int page, int pageSize) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(period);
    if (page < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("A paginação deve conter de 1 a 200 linhas.");
    }
    return unitOfWork.read(
        () -> {
          Sql query = query(type);
          String pagedSql =
              "SELECT report_rows.*, COUNT(*) OVER() total_rows FROM ("
                  + query.rows()
                  + ") report_rows ORDER BY sort_at, sort_title, sort_id LIMIT ? OFFSET ?";
          try (PreparedStatement statement = connection.prepareStatement(pagedSql)) {
            statement.setString(1, period.fromUtc());
            statement.setString(2, period.untilUtc());
            int pageParameter = 3;
            if (query.periodPairs() == 2) {
              statement.setString(3, period.fromUtc());
              statement.setString(4, period.untilUtc());
              pageParameter = 5;
            }
            statement.setInt(pageParameter, pageSize);
            statement.setLong(pageParameter + 1, (long) page * pageSize);
            try (ResultSet rows = statement.executeQuery()) {
              var result = new ArrayList<CommercialReportRow>();
              long totalRows = 0;
              while (rows.next()) {
                totalRows = rows.getLong("total_rows");
                String timestamp = rows.getString("occurred_at");
                result.add(
                    new CommercialReportRow(
                        timestamp == null ? null : Instant.parse(timestamp),
                        rows.getString("title"),
                        rows.getString("detail"),
                        rows.getString("status"),
                        rows.getLong("quantity"),
                        rows.getLong("amount_cents")));
              }
              return new CommercialReportPage(result, totalRows, page);
            }
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível consultar o relatório local.", failure);
          }
        });
  }

  @Override
  public CommercialReportSummary summarize(CommercialReportPeriod period) {
    Objects.requireNonNull(period);
    return unitOfWork.read(
        () -> {
          String sql =
              "SELECT "
                  + "COALESCE((SELECT SUM(a.total_centavos) FROM atendimento a WHERE a.estado='CONCLUIDO' AND a.concluido_em_utc>=? AND a.concluido_em_utc<?),0) revenue_cents,"
                  + "COALESCE((SELECT SUM(r.valor_centavos) FROM recebimento r WHERE r.recebido_em_utc>=? AND r.recebido_em_utc<?),0)-"
                  + "COALESCE((SELECT SUM(e.valor_centavos) FROM estorno_recebimento e WHERE e.estornado_em_utc>=? AND e.estornado_em_utc<?),0) received_cents,"
                  + "COALESCE((SELECT SUM(MAX(0,a.total_centavos-COALESCE((SELECT SUM(r.valor_centavos) FROM recebimento r WHERE r.atendimento_id=a.id),0)+COALESCE((SELECT SUM(e.valor_centavos) FROM estorno_recebimento e JOIN recebimento r ON r.id=e.recebimento_id WHERE r.atendimento_id=a.id),0))) FROM atendimento a WHERE a.estado='CONCLUIDO' AND a.concluido_em_utc>=? AND a.concluido_em_utc<?),0) pending_cents,"
                  + "COALESCE((SELECT SUM(m.valor_centavos) FROM movimento_caixa m WHERE m.tipo='SAIDA' AND m.criado_em_utc>=? AND m.criado_em_utc<?),0) expenses_cents";
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPeriod(statement, period, 1);
            bindPeriod(statement, period, 3);
            bindPeriod(statement, period, 5);
            bindPeriod(statement, period, 7);
            bindPeriod(statement, period, 9);
            try (ResultSet row = statement.executeQuery()) {
              if (!row.next()) throw new SQLException("Resumo sem resultado.");
              return new CommercialReportSummary(
                  row.getLong("revenue_cents"),
                  row.getLong("received_cents"),
                  row.getLong("pending_cents"),
                  row.getLong("expenses_cents"));
            }
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível consultar o resumo financeiro local.", failure);
          }
        });
  }

  private static Sql query(CommercialReportType type) {
    String columns =
        "SELECT occurred_at,title,detail,status,quantity,amount_cents,sort_at,sort_title,sort_id FROM (";
    String agenda =
        "SELECT a.inicio_em_utc occurred_at,c.nome title,p.nome||' — '||s.nome detail,a.estado status,1 quantity,0 amount_cents,a.inicio_em_utc sort_at,c.nome sort_title,a.id sort_id FROM agendamento a JOIN cliente c ON c.id=a.cliente_id JOIN profissional p ON p.id=a.profissional_id JOIN servico s ON s.id=a.servico_id WHERE a.inicio_em_utc>=? AND a.inicio_em_utc<?";
    return switch (type) {
      case DAILY_AGENDA -> new Sql(columns + agenda + ")", 1);
      case ABSENCES -> new Sql(columns + agenda + " AND a.estado='NAO_COMPARECEU')", 1);
      case RECURRING_CLIENTS ->
          new Sql(
              "SELECT NULL occurred_at,c.nome title,COUNT(*)||' atendimentos no período' detail,'RECORRENTE' status,COUNT(*) quantity,SUM(a.total_centavos) amount_cents,c.nome sort_at,c.nome sort_title,c.id sort_id FROM atendimento a JOIN cliente c ON c.id=a.cliente_id WHERE a.estado='CONCLUIDO' AND a.concluido_em_utc>=? AND a.concluido_em_utc<? GROUP BY c.id,c.nome HAVING COUNT(*)>1",
              1);
      case ATTENDANCES_BY_PROFESSIONAL ->
          new Sql(
              "SELECT NULL occurred_at,p.nome title,'Atendimentos concluídos' detail,'CONCLUIDO' status,COUNT(*) quantity,SUM(a.total_centavos) amount_cents,p.nome sort_at,p.nome sort_title,p.id sort_id FROM atendimento a JOIN profissional p ON p.id=a.profissional_id WHERE a.estado='CONCLUIDO' AND a.concluido_em_utc>=? AND a.concluido_em_utc<? GROUP BY p.id,p.nome",
              1);
      case SERVICES_PERFORMED ->
          new Sql(
              "SELECT NULL occurred_at,l.nome_snapshot title,'Serviço realizado' detail,'CONCLUIDO' status,SUM(l.quantidade) quantity,SUM(l.preco_unitario_centavos*l.quantidade) amount_cents,l.nome_snapshot sort_at,l.nome_snapshot sort_title,MIN(l.id) sort_id FROM atendimento_servico l JOIN atendimento a ON a.id=l.atendimento_id WHERE a.estado='CONCLUIDO' AND a.concluido_em_utc>=? AND a.concluido_em_utc<? GROUP BY l.nome_snapshot",
              1);
      case REVENUE ->
          new Sql(
              "SELECT a.concluido_em_utc occurred_at,c.nome title,p.nome detail,a.estado status,1 quantity,a.total_centavos amount_cents,a.concluido_em_utc sort_at,c.nome sort_title,a.id sort_id FROM atendimento a JOIN cliente c ON c.id=a.cliente_id JOIN profissional p ON p.id=a.profissional_id WHERE a.estado='CONCLUIDO' AND a.concluido_em_utc>=? AND a.concluido_em_utc<?",
              1);
      case RECEIPTS_BY_METHOD ->
          new Sql(
              "WITH receipt_events AS (SELECT r.recebido_em_utc occurred_at,r.forma method,r.valor_centavos amount_cents FROM recebimento r WHERE r.recebido_em_utc>=? AND r.recebido_em_utc<? UNION ALL SELECT e.estornado_em_utc occurred_at,r.forma method,-e.valor_centavos amount_cents FROM estorno_recebimento e JOIN recebimento r ON r.id=e.recebimento_id WHERE e.estornado_em_utc>=? AND e.estornado_em_utc<?) SELECT NULL occurred_at,method title,'Recebimentos líquidos por forma' detail,'LÍQUIDO' status,COUNT(*) quantity,SUM(amount_cents) amount_cents,method sort_at,method sort_title,method sort_id FROM receipt_events GROUP BY method",
              2);
      case EXPENSES ->
          new Sql(
              "SELECT m.criado_em_utc occurred_at,m.motivo title,COALESCE(u.nome,'') detail,m.tipo status,1 quantity,m.valor_centavos amount_cents,m.criado_em_utc sort_at,m.motivo sort_title,m.id sort_id FROM movimento_caixa m LEFT JOIN usuario u ON u.id=m.criado_por_usuario_id WHERE m.tipo='SAIDA' AND m.criado_em_utc>=? AND m.criado_em_utc<?",
              1);
    };
  }

  private static void bindPeriod(
      PreparedStatement statement, CommercialReportPeriod period, int start) throws SQLException {
    statement.setString(start, period.fromUtc());
    statement.setString(start + 1, period.untilUtc());
  }

  private record Sql(String rows, int periodPairs) {}
}
