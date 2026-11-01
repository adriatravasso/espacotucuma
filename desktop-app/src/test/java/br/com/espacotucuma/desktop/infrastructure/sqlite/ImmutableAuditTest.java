package br.com.espacotucuma.desktop.infrastructure.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImmutableAuditTest {
  private static final UUID ORIGIN = UUID.fromString("12d71a76-fdc9-42c7-9038-b0fa0b4ea7c1");
  private static final Instant OCCURRED_AT = Instant.parse("2026-10-31T18:42:13Z");

  @Test
  void auditRecordRetainsActorTimeOriginAndReasonAndRejectsUpdateAndDelete(@TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database = LocalDatabaseSession.open(temp.resolve("audit.sqlite"))) {
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      new CreateFirstAdministratorUseCase(
              unitOfWork,
              new SqliteInitialSetupRepository(database.connection(), unitOfWork),
              new Pbkdf2PasswordHasher(),
              new SqliteAuditRepository(database.connection(), unitOfWork),
              ORIGIN)
          .create(
              new InitialAdministratorSetup(
                  "Espaço Tucumã",
                  "Administradora Teste",
                  "admin",
                  "SenhaForteAudit2026!".toCharArray()));
      UUID actorId = UUID.fromString(scalarString(database, "SELECT id FROM usuario"));
      UUID eventId = UUID.randomUUID();
      UUID operationId = UUID.randomUUID();
      UUID targetId = UUID.randomUUID();
      AuditEvent event =
          new AuditEvent(
              eventId,
              actorId,
              operationId,
              "PERFIL_ALTERADO",
              "usuario",
              targetId,
              ORIGIN,
              "{}",
              "Adequação de acesso aprovada",
              OCCURRED_AT);
      SqliteAuditRepository audit = new SqliteAuditRepository(database.connection(), unitOfWork);

      assertThrows(
          IllegalStateException.class,
          () ->
              unitOfWork.execute(
                  () -> {
                    audit.record(event);
                    throw new IllegalStateException("falha após gravar auditoria");
                  }));
      assertEquals(1, scalarInt(database, "SELECT count(*) FROM evento_auditoria"));

      unitOfWork.execute(
          () -> {
            audit.record(event);
            return null;
          });
      String eventFilter = " WHERE id = '" + eventId + "'";
      assertEquals(
          actorId.toString(),
          scalarString(database, "SELECT usuario_id FROM evento_auditoria" + eventFilter));
      assertEquals(
          operationId.toString(),
          scalarString(database, "SELECT operacao_id FROM evento_auditoria" + eventFilter));
      assertEquals(
          ORIGIN.toString(),
          scalarString(database, "SELECT origem_estacao_id FROM evento_auditoria" + eventFilter));
      assertEquals(
          "2026-10-31T18:42:13Z",
          scalarString(database, "SELECT ocorrido_em_utc FROM evento_auditoria" + eventFilter));
      assertEquals(
          "Adequação de acesso aprovada",
          scalarString(database, "SELECT motivo FROM evento_auditoria" + eventFilter));
      assertEquals(
          "{}", scalarString(database, "SELECT detalhes_json FROM evento_auditoria" + eventFilter));
      assertThrows(
          SQLException.class,
          () -> execute(database, "UPDATE evento_auditoria SET motivo = 'alterado'"));
      assertThrows(SQLException.class, () -> execute(database, "DELETE FROM evento_auditoria"));
      assertEquals(
          1,
          scalarInt(
              database, "SELECT count(*) FROM evento_auditoria WHERE id = '" + eventId + "'"));
    }
  }

  @Test
  void auditRepositoryRejectsWritesOutsideBusinessTransaction(@TempDir Path temp) throws Exception {
    try (LocalDatabaseSession database = LocalDatabaseSession.open(temp.resolve("guard.sqlite"))) {
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      AuditEvent event =
          new AuditEvent(
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              "CLIENTE_CRIADO",
              "cliente",
              UUID.randomUUID(),
              ORIGIN,
              "{}",
              null,
              OCCURRED_AT);
      assertThrows(
          IllegalStateException.class,
          () -> new SqliteAuditRepository(database.connection(), unitOfWork).record(event));
      assertEquals(0, scalarInt(database, "SELECT count(*) FROM evento_auditoria"));
    }
  }

  private int scalarInt(LocalDatabaseSession database, String sql) throws Exception {
    try (var statement = database.connection().createStatement();
        var result = statement.executeQuery(sql)) {
      return result.next() ? result.getInt(1) : -1;
    }
  }

  private String scalarString(LocalDatabaseSession database, String sql) throws Exception {
    try (var statement = database.connection().createStatement();
        var result = statement.executeQuery(sql)) {
      return result.next() ? result.getString(1) : null;
    }
  }

  private void execute(LocalDatabaseSession database, String sql) throws SQLException {
    try (var statement = database.connection().createStatement()) {
      statement.executeUpdate(sql);
    }
  }
}
