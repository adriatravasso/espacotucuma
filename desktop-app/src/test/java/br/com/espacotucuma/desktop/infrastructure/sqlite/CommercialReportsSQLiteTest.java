package br.com.espacotucuma.desktop.infrastructure.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.espacotucuma.desktop.application.reports.CommercialReportPeriod;
import br.com.espacotucuma.desktop.application.reports.CommercialReportType;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommercialReportsSQLiteTest {
  private static final String NOW = "2026-11-01T12:00:00Z";
  private static final String USER = "10000000-0000-4000-8000-000000000001";
  private static final String CLIENT = "20000000-0000-4000-8000-000000000001";
  private static final String OTHER_CLIENT = "20000000-0000-4000-8000-000000000002";
  private static final String PROFESSIONAL = "30000000-0000-4000-8000-000000000001";
  private static final String SERVICE = "40000000-0000-4000-8000-000000000001";
  private static final String CASH_TURN = "50000000-0000-4000-8000-000000000001";

  @Test
  void reportsUseManausBoundariesAggregateTransactionsAndRemainPaginated(@TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("reports.sqlite"))) {
      seed(database.connection());
      var unit = new SqliteUnitOfWork(database.connection());
      var reports = new SqliteCommercialReportRepository(database.connection(), unit);
      var period =
          new CommercialReportPeriod(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-01"));

      var agenda = reports.find(CommercialReportType.DAILY_AGENDA, period, 0, 1);
      assertEquals(2, agenda.totalRows());
      assertEquals(1, agenda.rows().size());
      assertEquals("Cliente Recorrente", agenda.rows().getFirst().title());
      assertEquals(1, reports.find(CommercialReportType.ABSENCES, period, 0, 10).totalRows());

      var recurring = reports.find(CommercialReportType.RECURRING_CLIENTS, period, 0, 10);
      assertEquals(1, recurring.totalRows());
      assertEquals(2, recurring.rows().getFirst().quantity());
      assertEquals(25_000, recurring.rows().getFirst().amountCents());

      var byProfessional =
          reports.find(CommercialReportType.ATTENDANCES_BY_PROFESSIONAL, period, 0, 10);
      assertEquals(2, byProfessional.rows().getFirst().quantity());
      assertEquals(25_000, byProfessional.rows().getFirst().amountCents());
      assertEquals(
          1, reports.find(CommercialReportType.SERVICES_PERFORMED, period, 0, 10).totalRows());
      assertEquals(2, reports.find(CommercialReportType.REVENUE, period, 0, 10).totalRows());

      var receipts = reports.find(CommercialReportType.RECEIPTS_BY_METHOD, period, 0, 10);
      assertEquals(1, receipts.totalRows());
      assertEquals("PIX", receipts.rows().getFirst().title());
      assertEquals(3_000, receipts.rows().getFirst().amountCents());
      assertEquals(1, reports.find(CommercialReportType.EXPENSES, period, 0, 10).totalRows());

      var summary = reports.summarize(period);
      assertEquals(25_000, summary.revenueCents());
      assertEquals(3_000, summary.receivedCents());
      assertEquals(22_000, summary.pendingCents());
      assertEquals(500, summary.expensesCents());
    }
  }

  private static void seed(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO usuario (id,nome,login,senha_hash,perfil,ativo,criado_em_utc,atualizado_em_utc) VALUES ('"
              + USER
              + "','Administradora','admin','01234567890123456789012345678901','ADMINISTRADOR',1,'"
              + NOW
              + "','"
              + NOW
              + "')");
      insertClient(statement, CLIENT, "CLI-000001", "Cliente Recorrente");
      insertClient(statement, OTHER_CLIENT, "CLI-000002", "Outro Cliente");
      statement.executeUpdate(
          "INSERT INTO profissional (id,nome,ativo,criado_em_utc,atualizado_em_utc) VALUES ('"
              + PROFESSIONAL
              + "','Profissional Teste',1,'"
              + NOW
              + "','"
              + NOW
              + "')");
      statement.executeUpdate(
          "INSERT INTO servico (id,nome,categoria,duracao_minutos,preco_centavos,comissao_pontos_base,ativo,criado_em_utc,atualizado_em_utc) VALUES ('"
              + SERVICE
              + "','Manicure','Unhas',60,12500,3000,1,'"
              + NOW
              + "','"
              + NOW
              + "')");
      insertAppointment(
          statement,
          "60000000-0000-4000-8000-000000000001",
          CLIENT,
          "2026-11-01T03:59:59Z",
          "2026-11-01T04:29:59Z",
          "AGENDADO");
      insertAppointment(
          statement,
          "60000000-0000-4000-8000-000000000002",
          CLIENT,
          "2026-11-01T04:00:00Z",
          "2026-11-01T05:00:00Z",
          "AGENDADO");
      insertAppointment(
          statement,
          "60000000-0000-4000-8000-000000000003",
          OTHER_CLIENT,
          "2026-11-01T05:00:00Z",
          "2026-11-01T06:00:00Z",
          "NAO_COMPARECEU");
      insertAttendance(
          statement,
          "70000000-0000-4000-8000-000000000001",
          CLIENT,
          "2026-11-01T04:30:00Z",
          10_000);
      insertAttendance(
          statement,
          "70000000-0000-4000-8000-000000000002",
          CLIENT,
          "2026-11-01T06:00:00Z",
          15_000);
      statement.executeUpdate(
          "INSERT INTO atendimento_servico (id,atendimento_id,servico_id,nome_snapshot,quantidade,preco_unitario_centavos,comissao_pontos_base) VALUES ('80000000-0000-4000-8000-000000000001','70000000-0000-4000-8000-000000000001','"
              + SERVICE
              + "','Manicure',1,10000,3000),('80000000-0000-4000-8000-000000000002','70000000-0000-4000-8000-000000000002','"
              + SERVICE
              + "','Manicure',1,15000,3000)");
      statement.executeUpdate(
          "INSERT INTO caixa_turno (id,operador_usuario_id,estado,abertura_em_utc,saldo_abertura_centavos) VALUES ('"
              + CASH_TURN
              + "','"
              + USER
              + "','ABERTO','"
              + NOW
              + "',0)");
      statement.executeUpdate(
          "INSERT INTO recebimento (id,atendimento_id,forma,valor_centavos,estado,chave_idempotencia,recebido_em_utc,recebido_por_usuario_id) VALUES ('90000000-0000-4000-8000-000000000001','70000000-0000-4000-8000-000000000001','PIX',4000,'CONFIRMADO','report-payment-key','2026-11-01T07:00:00Z','"
              + USER
              + "')");
      statement.executeUpdate(
          "INSERT INTO movimento_caixa (id,caixa_turno_id,tipo,valor_centavos,motivo,criado_por_usuario_id,criado_em_utc) VALUES ('a0000000-0000-4000-8000-000000000001','"
              + CASH_TURN
              + "','SAIDA',500,'Material de limpeza','"
              + USER
              + "','2026-11-01T08:00:00Z')");
      statement.executeUpdate(
          "INSERT INTO movimento_caixa (id,caixa_turno_id,recebimento_id,tipo,valor_centavos,motivo,criado_por_usuario_id,criado_em_utc) VALUES ('a0000000-0000-4000-8000-000000000002','"
              + CASH_TURN
              + "','90000000-0000-4000-8000-000000000001','ESTORNO',1000,'Estorno parcial','"
              + USER
              + "','2026-11-01T09:00:00Z')");
      statement.executeUpdate(
          "INSERT INTO estorno_recebimento (id,recebimento_id,movimento_caixa_id,valor_centavos,chave_idempotencia,motivo,estornado_por_usuario_id,estornado_em_utc) VALUES ('b0000000-0000-4000-8000-000000000001','90000000-0000-4000-8000-000000000001','a0000000-0000-4000-8000-000000000002',1000,'report-refund-key','Estorno parcial','"
              + USER
              + "','2026-11-01T09:00:00Z')");
    }
  }

  private static void insertClient(Statement statement, String id, String code, String name)
      throws Exception {
    statement.executeUpdate(
        "INSERT INTO cliente (id,codigo_interno,nome,ativo,criado_em_utc,atualizado_em_utc) VALUES ('"
            + id
            + "','"
            + code
            + "','"
            + name
            + "',1,'"
            + NOW
            + "','"
            + NOW
            + "')");
  }

  private static void insertAppointment(
      Statement statement, String id, String client, String start, String end, String status)
      throws Exception {
    statement.executeUpdate(
        "INSERT INTO agendamento (id,cliente_id,profissional_id,servico_id,inicio_em_utc,fim_em_utc,estado,criado_em_utc,atualizado_em_utc) VALUES ('"
            + id
            + "','"
            + client
            + "','"
            + PROFESSIONAL
            + "','"
            + SERVICE
            + "','"
            + start
            + "','"
            + end
            + "','"
            + status
            + "','"
            + NOW
            + "','"
            + NOW
            + "')");
  }

  private static void insertAttendance(
      Statement statement, String id, String client, String completedAt, long total)
      throws Exception {
    statement.executeUpdate(
        "INSERT INTO atendimento (id,cliente_id,profissional_id,estado,subtotal_centavos,desconto_centavos,total_centavos,iniciado_em_utc,concluido_em_utc,criado_em_utc,atualizado_em_utc) VALUES ('"
            + id
            + "','"
            + client
            + "','"
            + PROFESSIONAL
            + "','CONCLUIDO',"
            + total
            + ",0,"
            + total
            + ",'"
            + completedAt
            + "','"
            + completedAt
            + "','"
            + NOW
            + "','"
            + NOW
            + "')");
  }
}
