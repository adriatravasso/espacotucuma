package br.com.espacotucuma.desktop.infrastructure.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

class SQLiteMigrationTest {

  private static final String CLIENT_ID = "e427bb6d-8972-4122-9ad0-f055e0f217b8";
  private static final String USER_ID = "1f63b8f9-dd18-4c7d-bf1c-25fefbd702a3";
  private static final String NOW = "2026-11-01T12:00:00Z";

  @Test
  void emptyDatabaseInstallsTheVersionedSchemaAndEnablesForeignKeys(@TempDir Path temp)
      throws Exception {
    Path databaseFile = temp.resolve("empty.sqlite");
    try (Connection ignored = SQLiteSchemaMigrator.dataSource(databaseFile).getConnection()) {
      assertTrue(java.nio.file.Files.isRegularFile(databaseFile));
    }

    try (LocalDatabaseSession database = LocalDatabaseSession.open(databaseFile)) {
      Connection connection = database.connection();
      assertEquals(1, pragmaInt(connection, "foreign_keys"));
      assertTrue(tableExists(connection, "cliente"));
      assertTrue(tableExists(connection, "estabelecimento"));
      assertTrue(tableExists(connection, "agendamento"));
      assertTrue(tableExists(connection, "recebimento"));
      assertTrue(tableExists(connection, "conferencia_caixa"));
      assertTrue(tableExists(connection, "competencia_comissao"));
      assertTrue(columnExists(connection, "comissao", "competencia"));
      assertTrue(columnExists(connection, "comissao", "profissional_nome_snapshot"));
      assertTrue(tableExists(connection, "estorno_recebimento"));
      assertTrue(triggerExists(connection, "impedir_exclusao_recebimento_confirmado"));
      assertTrue(tableExists(connection, "movimento_estoque"));
      assertTrue(columnExists(connection, "produto", "categoria"));
      assertTrue(columnExists(connection, "movimento_estoque", "chave_idempotencia"));
      assertTrue(columnExists(connection, "movimento_estoque", "direcao_ajuste"));
      assertTrue(columnExists(connection, "movimento_estoque", "atendimento_servico_id"));
      assertTrue(tableExists(connection, "material_servico"));
      assertTrue(triggerExists(connection, "trg_movimento_estoque_no_update"));
      assertTrue(triggerExists(connection, "trg_movimento_estoque_no_delete"));
      assertTrue(tableExists(connection, "evento_auditoria"));
      assertTrue(triggerExists(connection, "trg_evento_auditoria_no_update"));
      assertTrue(triggerExists(connection, "trg_evento_auditoria_no_delete"));
      assertTrue(triggerExists(connection, "trg_tentativa_login_no_update"));
      assertTrue(triggerExists(connection, "trg_tentativa_login_no_delete"));
      assertTrue(triggerExists(connection, "trg_cliente_codigo_interno_no_insert"));
      assertTrue(triggerExists(connection, "trg_cliente_codigo_interno_immutable"));
      assertTrue(tableExists(connection, "tentativa_login"));
      assertTrue(tableExists(connection, "operacao_pendente"));
      assertTrue(tableExists(connection, "status_protecao"));
      assertTrue(tableExists(connection, "cursor_alteracao_remota"));
      assertTrue(tableExists(connection, "alteracao_remota_recebida"));
      assertTrue(columnExists(connection, "alteracao_remota_recebida", "payload_local_json"));
      assertTrue(columnExists(connection, "alteracao_remota_recebida", "estado_reconciliacao"));
      assertTrue(tableExists(connection, "indisponibilidade_profissional"));
      assertTrue(tableExists(connection, "regra_comissao_profissional_servico"));
      assertTrue(tableExists(connection, "reversao_utilizacao_pacote"));
      assertTrue(columnExists(connection, "saldo_sessoes", "nome_pacote_snapshot"));
      assertTrue(columnExists(connection, "saldo_sessoes", "preco_pago_centavos"));
      assertTrue(columnExists(connection, "saldo_sessoes", "forma_pagamento"));
      assertTrue(columnExists(connection, "atendimento_servico", "comissao_regra_id"));
      assertTrue(columnExists(connection, "atendimento_servico", "comissao_origem"));
      assertTrue(columnExists(connection, "atendimento", "observacoes_administrativas"));
      assertTrue(columnExists(connection, "atendimento", "atualizado_em_utc"));
      assertTrue(indexExists(connection, "idx_servico_categoria_ativo_nome"));
      assertTrue(indexExists(connection, "idx_regra_comissao_profissional_ativo"));
      assertTrue(indexExists(connection, "idx_atendimento_concluido"));
      assertTrue(indexExists(connection, "idx_recebimento_data_forma"));
      assertTrue(indexExists(connection, "idx_movimento_caixa_tipo_data"));
      assertTrue(indexExists(connection, "idx_movimento_estoque_tipo_data"));
      assertTrue(indexExists(connection, "idx_comissao_servico_status"));
      assertTrue(indexExists(connection, "idx_conferencia_caixa_data_fechamento"));
      assertTrue(triggerExists(connection, "trg_agendamento_profissional_ativo_insert"));
      assertTrue(triggerExists(connection, "trg_agendamento_profissional_ativo_update"));
      assertEquals(
          22,
          scalarInt(connection, "SELECT count(*) FROM flyway_schema_history WHERE success = 1"));
    }
  }

  @Test
  void repeatingMigrationPreservesRowsAndDoesNotReapplyVersionedScripts(@TempDir Path temp)
      throws Exception {
    Path databaseFile = temp.resolve("repeat.sqlite");

    try (LocalDatabaseSession database = LocalDatabaseSession.open(databaseFile)) {
      insertClient(database.connection(), CLIENT_ID, "Cliente preservada");

      SQLiteSchemaMigrator.migrate(databaseFile);

      assertEquals(
          1,
          scalarInt(
              database.connection(),
              "SELECT count(*) FROM cliente WHERE id = '" + CLIENT_ID + "'"));
      assertEquals(
          22,
          scalarInt(
              database.connection(),
              "SELECT count(*) FROM flyway_schema_history WHERE success = 1"));
    }
  }

  @Test
  void foreignKeysAndCheckConstraintsRejectInvalidRows(@TempDir Path temp) throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("constraints.sqlite"))) {
      Connection connection = database.connection();
      assertThrows(SQLException.class, () -> insertClient(connection, CLIENT_ID, " "));
      assertThrows(
          SQLException.class,
          () ->
              insertClient(
                  connection,
                  "ab0ca52a-8cf3-4563-9daf-b32af6df7a2a",
                  "CLI-INVALID",
                  "Cliente com código inválido"));
      assertThrows(SQLException.class, () -> insertInvalidAppointment(connection));
      assertEquals(0, scalarInt(connection, "SELECT count(*) FROM cliente"));
      assertEquals(0, scalarInt(connection, "SELECT count(*) FROM agendamento"));
    }
  }

  @Test
  void schemaAllowsOnlyOneActiveStation(@TempDir Path temp) throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("one-station.sqlite"))) {
      Connection connection = database.connection();
      insertStation(connection, "6f37f434-53ba-4da2-8ef6-2d223b8544c1", "estacao-principal");

      assertThrows(
          SQLException.class,
          () ->
              insertStation(connection, "fbe181ca-e31c-4ce7-9b55-00f703a37f19", "segunda-estacao"));
      assertEquals(
          1,
          scalarInt(connection, "SELECT count(*) FROM estacao_autorizada WHERE estado = 'ATIVA'"));
    }
  }

  @Test
  void invalidForeignKeyRollsBackTheWholeTransaction(@TempDir Path temp) throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("rollback.sqlite"))) {
      Connection connection = database.connection();
      connection.setAutoCommit(false);
      try {
        insertClient(connection, CLIENT_ID, "Cliente temporária");
        assertThrows(SQLException.class, () -> insertInvalidAppointment(connection));
        connection.rollback();
      } finally {
        connection.setAutoCommit(true);
      }

      assertEquals(0, scalarInt(connection, "SELECT count(*) FROM cliente"));
      assertEquals(0, scalarInt(connection, "SELECT count(*) FROM agendamento"));
    }
  }

  @Test
  @Timeout(30)
  void sqliteFullConditionRejectsNewDataAndPreservesCommittedRows(@TempDir Path temp)
      throws Exception {
    Path databaseFile = temp.resolve("disk-full.sqlite");
    try (LocalDatabaseSession database = LocalDatabaseSession.open(databaseFile);
        Statement statement = database.connection().createStatement()) {
      statement.executeUpdate("CREATE TABLE test_disk_capacity (value BLOB)");
      statement.executeUpdate("INSERT INTO test_disk_capacity VALUES (X'01')");
      long pageLimit = scalarInt(database.connection(), "PRAGMA page_count") + 1L;
      statement.execute("PRAGMA max_page_count=" + pageLimit);

      SQLiteException failure =
          assertThrows(
              SQLiteException.class,
              () ->
                  statement.executeUpdate(
                      "INSERT INTO test_disk_capacity VALUES (randomblob(1048576))"));

      assertEquals(SQLiteErrorCode.SQLITE_FULL, failure.getResultCode());
      assertTrue(Files.isRegularFile(databaseFile));
      assertEquals(1, scalarInt(database.connection(), "SELECT COUNT(*) FROM test_disk_capacity"));
      try (ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
        assertTrue(result.next());
        assertEquals("ok", result.getString(1));
      }
    }
  }

  private void insertClient(Connection connection, String id, String name) throws SQLException {
    insertClient(connection, id, "CLI-000001", name);
  }

  private void insertClient(Connection connection, String id, String code, String name)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO cliente (id, codigo_interno, nome, criado_em_utc, atualizado_em_utc) "
                + "VALUES (?, ?, ?, ?, ?)")) {
      statement.setString(1, id);
      statement.setString(2, code);
      statement.setString(3, name);
      statement.setString(4, NOW);
      statement.setString(5, NOW);
      statement.executeUpdate();
    }
  }

  private void insertInvalidAppointment(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO agendamento (id, cliente_id, profissional_id, servico_id, inicio_em_utc, "
                + "fim_em_utc, estado, criado_em_utc, atualizado_em_utc) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      statement.setString(1, "b0ca52a7-8cf3-4563-9daf-b32af6df7a2a");
      statement.setString(2, CLIENT_ID);
      statement.setString(3, "da36de0c-3437-4fe0-b39e-72d042288ac1");
      statement.setString(4, "1f8fef1e-cc79-43da-b042-64753d82cd37");
      statement.setString(5, NOW);
      statement.setString(6, "2026-11-01T13:00:00Z");
      statement.setString(7, "AGENDADO");
      statement.setString(8, NOW);
      statement.setString(9, NOW);
      statement.executeUpdate();
    }
  }

  private void insertStation(Connection connection, String id, String identifier)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO estacao_autorizada (id, identificador, estado, autorizada_em_utc) "
                + "VALUES (?, ?, 'ATIVA', ?)")) {
      statement.setString(1, id);
      statement.setString(2, identifier);
      statement.setString(3, NOW);
      statement.executeUpdate();
    }
  }

  private boolean tableExists(Connection connection, String tableName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?"); ) {
      statement.setString(1, tableName);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private boolean triggerExists(Connection connection, String triggerName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'trigger' AND name = ?")) {
      statement.setString(1, triggerName);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private boolean indexExists(Connection connection, String indexName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?")) {
      statement.setString(1, indexName);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private boolean columnExists(Connection connection, String table, String column)
      throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (result.next()) {
        if (column.equals(result.getString("name"))) return true;
      }
      return false;
    }
  }

  private int pragmaInt(Connection connection, String pragma) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("PRAGMA " + pragma)) {
      return result.next() ? result.getInt(1) : -1;
    }
  }

  private int scalarInt(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      return result.next() ? result.getInt(1) : -1;
    }
  }
}
