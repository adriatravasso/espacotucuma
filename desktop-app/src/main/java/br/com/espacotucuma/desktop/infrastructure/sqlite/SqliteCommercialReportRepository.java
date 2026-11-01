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
    return find(type, period, page, pageSize, "", "");
  }

  @Override
  public CommercialReportPage find(
      CommercialReportType type,
      CommercialReportPeriod period,
      int page,
      int pageSize,
      String professionalFilter,
      String serviceFilter) {
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
            int nextParameter = 1;
            if (query.periodPairs() >= 1) {
              bindPeriod(statement, period, nextParameter);
              nextParameter += 2;
            }
            if (query.periodPairs() == 2) {
              bindPeriod(statement, period, nextParameter);
              nextParameter += 2;
            }
            int filterParameter = nextParameter;
            if (query.professionalFilter()) {
              statement.setString(filterParameter++, professionalFilter);
              statement.setString(filterParameter++, contains(professionalFilter));
            }
            if (query.serviceFilter()) {
              statement.setString(filterParameter++, serviceFilter);
              statement.setString(filterParameter++, contains(serviceFilter));
            }
            statement.setInt(filterParameter, pageSize);
            statement.setLong(filterParameter + 1, (long) page * pageSize);
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
              "SELECT NULL occurred_at,p.nome title,'Atendimentos concluídos' detail,'CONCLUIDO' status,COUNT(*) quantity,SUM(a.total_centavos) amount_cents,p.nome sort_at,p.nome sort_title,p.id sort_id FROM atendimento a JOIN profissional p ON p.id=a.profissional_id WHERE a.estado='CONCLUIDO' AND a.concluido_em_utc>=? AND a.concluido_em_utc<? AND (?='' OR p.nome LIKE ? COLLATE NOCASE) GROUP BY p.id,p.nome",
              1,
              true,
              false);
      case SERVICES_PERFORMED ->
          new Sql(
              "SELECT NULL occurred_at,l.nome_snapshot title,'Serviço realizado' detail,'CONCLUIDO' status,SUM(l.quantidade) quantity,SUM(l.preco_unitario_centavos*l.quantidade) amount_cents,l.nome_snapshot sort_at,l.nome_snapshot sort_title,MIN(l.id) sort_id FROM atendimento_servico l JOIN atendimento a ON a.id=l.atendimento_id WHERE a.estado='CONCLUIDO' AND a.concluido_em_utc>=? AND a.concluido_em_utc<? AND (?='' OR l.nome_snapshot LIKE ? COLLATE NOCASE) GROUP BY l.nome_snapshot",
              1,
              false,
              true);
      case REVENUE ->
          new Sql(
              "SELECT a.concluido_em_utc occurred_at,c.nome title,p.nome detail,a.estado status,1 quantity,a.total_centavos amount_cents,a.concluido_em_utc sort_at,c.nome sort_title,a.id sort_id FROM atendimento a JOIN cliente c ON c.id=a.cliente_id JOIN profissional p ON p.id=a.profissional_id WHERE a.estado='CONCLUIDO' AND a.concluido_em_utc>=? AND a.concluido_em_utc<? AND (?='' OR p.nome LIKE ? COLLATE NOCASE) AND (?='' OR EXISTS (SELECT 1 FROM atendimento_servico l WHERE l.atendimento_id=a.id AND l.nome_snapshot LIKE ? COLLATE NOCASE))",
              1,
              true,
              true);
      case RECEIPTS_BY_METHOD ->
          new Sql(
              "WITH receipt_events AS (SELECT r.recebido_em_utc occurred_at,r.forma method,r.valor_centavos amount_cents FROM recebimento r WHERE r.recebido_em_utc>=? AND r.recebido_em_utc<? UNION ALL SELECT e.estornado_em_utc occurred_at,r.forma method,-e.valor_centavos amount_cents FROM estorno_recebimento e JOIN recebimento r ON r.id=e.recebimento_id WHERE e.estornado_em_utc>=? AND e.estornado_em_utc<?) SELECT NULL occurred_at,method title,'Recebimentos líquidos por forma' detail,'LÍQUIDO' status,COUNT(*) quantity,SUM(amount_cents) amount_cents,method sort_at,method sort_title,method sort_id FROM receipt_events GROUP BY method",
              2);
      case EXPENSES ->
          new Sql(
              "SELECT m.criado_em_utc occurred_at,m.motivo title,COALESCE(u.nome,'') detail,m.tipo status,1 quantity,m.valor_centavos amount_cents,m.criado_em_utc sort_at,m.motivo sort_title,m.id sort_id FROM movimento_caixa m LEFT JOIN usuario u ON u.id=m.criado_por_usuario_id WHERE m.tipo='SAIDA' AND m.criado_em_utc>=? AND m.criado_em_utc<?",
              1);
      case STOCK_POSITION ->
          new Sql(
              stockSql()
                  + "SELECT NULL occurred_at,p.nome title,p.unidade||' · mínimo: '||printf('%.3f',p.estoque_minimo_milesimos/1000.0) detail,'ESTOQUE' status,COALESCE(m.saldo,0) quantity,(COALESCE(m.saldo,0)*p.custo_unitario_centavos/1000) amount_cents,p.nome sort_at,p.nome sort_title,p.id sort_id FROM produto p LEFT JOIN stock_balance m ON m.produto_id=p.id WHERE p.ativo=1",
              0);
      case LOW_STOCK ->
          new Sql(
              stockSql()
                  + "SELECT NULL occurred_at,p.nome title,p.unidade||' · mínimo: '||printf('%.3f',p.estoque_minimo_milesimos/1000.0) detail,'BAIXO' status,COALESCE(m.saldo,0) quantity,(COALESCE(m.saldo,0)*p.custo_unitario_centavos/1000) amount_cents,p.nome sort_at,p.nome sort_title,p.id sort_id FROM produto p LEFT JOIN stock_balance m ON m.produto_id=p.id WHERE p.ativo=1 AND COALESCE(m.saldo,0)<=p.estoque_minimo_milesimos",
              0);
      case STOCK_CONSUMPTION ->
          new Sql(
              "SELECT m.criado_em_utc occurred_at,pr.nome title,COALESCE(l.nome_snapshot,'')||' · '||COALESCE(p.nome,'')||' · '||u.nome detail,m.tipo status,m.quantidade_milesimos quantity,(m.quantidade_milesimos*m.custo_unitario_centavos/1000) amount_cents,m.criado_em_utc sort_at,pr.nome sort_title,m.id sort_id FROM movimento_estoque m JOIN produto pr ON pr.id=m.produto_id JOIN usuario u ON u.id=m.criado_por_usuario_id LEFT JOIN atendimento_servico l ON l.id=m.atendimento_servico_id LEFT JOIN atendimento a ON a.id=m.atendimento_id LEFT JOIN profissional p ON p.id=a.profissional_id WHERE m.tipo='CONSUMO_SERVICO' AND m.criado_em_utc>=? AND m.criado_em_utc<? AND (?='' OR COALESCE(p.nome,'') LIKE ? COLLATE NOCASE) AND (?='' OR COALESCE(l.nome_snapshot,'') LIKE ? COLLATE NOCASE)",
              1,
              true,
              true);
      case COMMISSION_STATEMENTS ->
          new Sql(
              "SELECT a.concluido_em_utc occurred_at,COALESCE(c.profissional_nome_snapshot,p.nome) title,l.nome_snapshot detail,c.estado status,1 quantity,c.valor_centavos amount_cents,a.concluido_em_utc sort_at,COALESCE(c.profissional_nome_snapshot,p.nome) sort_title,c.id sort_id FROM comissao c JOIN atendimento_servico l ON l.id=c.atendimento_servico_id JOIN atendimento a ON a.id=l.atendimento_id JOIN profissional p ON p.id=c.profissional_id WHERE a.concluido_em_utc>=? AND a.concluido_em_utc<? AND (?='' OR COALESCE(c.profissional_nome_snapshot,p.nome) LIKE ? COLLATE NOCASE) AND (?='' OR l.nome_snapshot LIKE ? COLLATE NOCASE)",
              1,
              true,
              true);
      case CASH_CLOSINGS ->
          new Sql(
              "SELECT MAX(cc.conferido_em_utc) occurred_at,'Fechamento '||substr(cc.fechamento_id,1,8) title,u.nome||' · diferença: R$ '||printf('%.2f',(SUM(cc.conferido_centavos)-SUM(cc.esperado_centavos))/100.0) detail,'FECHADO' status,SUM(cc.esperado_centavos) quantity,SUM(cc.conferido_centavos) amount_cents,MAX(cc.conferido_em_utc) sort_at,cc.fechamento_id sort_title,cc.fechamento_id sort_id FROM conferencia_caixa cc JOIN caixa_turno t ON t.id=cc.caixa_turno_id JOIN usuario u ON u.id=cc.conferido_por_usuario_id WHERE cc.conferido_em_utc>=? AND cc.conferido_em_utc<? GROUP BY cc.fechamento_id,u.nome");
    };
  }

  private static void bindPeriod(
      PreparedStatement statement, CommercialReportPeriod period, int start) throws SQLException {
    statement.setString(start, period.fromUtc());
    statement.setString(start + 1, period.untilUtc());
  }

  private static String stockSql() {
    return "WITH stock_balance AS (SELECT produto_id,SUM(CASE WHEN tipo='ENTRADA' OR (tipo='AJUSTE' AND direcao_ajuste='ENTRADA') THEN quantidade_milesimos WHEN tipo IN ('SAIDA','CONSUMO_SERVICO') OR (tipo='AJUSTE' AND direcao_ajuste='SAIDA') THEN -quantidade_milesimos ELSE 0 END) saldo FROM movimento_estoque GROUP BY produto_id) ";
  }

  private static String contains(String value) {
    return "%" + value + "%";
  }

  private record Sql(
      String rows, int periodPairs, boolean professionalFilter, boolean serviceFilter) {
    private Sql(String rows) {
      this(rows, 1, false, false);
    }

    private Sql(String rows, int periodPairs) {
      this(rows, periodPairs, false, false);
    }
  }
}
