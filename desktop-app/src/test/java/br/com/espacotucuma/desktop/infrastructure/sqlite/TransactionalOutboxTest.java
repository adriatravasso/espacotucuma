package br.com.espacotucuma.desktop.infrastructure.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.client.Client;
import br.com.espacotucuma.desktop.application.client.RegisterClientUseCase;
import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.outbox.ServerConfirmation;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransactionalOutboxTest {

  private static final UUID STATION_ID = UUID.fromString("4dc2279d-c890-458f-8ec3-c82fdf33c8a8");
  private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

  @Test
  void clientAndExactlyOnePendingOperationAreCommittedLocallyWithoutSending(@TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database = LocalDatabaseSession.open(temp.resolve("local.sqlite"))) {
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      SqliteClientRepository clients =
          new SqliteClientRepository(database.connection(), unitOfWork);
      SqliteOutboxRepository outbox = new SqliteOutboxRepository(database.connection(), unitOfWork);
      RegisterClientUseCase useCase = registerClientUseCase(unitOfWork, clients, outbox, database);

      Client saved = useCase.register("  Maria Oliveira  ", "92999990000");

      assertEquals(saved, clients.findById(saved.id()).orElseThrow());
      List<PendingOperation> pending = outbox.findPending(10);
      assertEquals(1, pending.size());
      PendingOperation event = pending.getFirst();
      assertEquals("cliente", event.entity());
      assertEquals(saved.id(), event.entityId());
      assertEquals("CLIENTE_CRIADO", event.type());
      assertEquals(event.id().toString(), event.idempotencyKey());
      assertEquals(1, event.payloadVersion());
      assertEquals(0, event.expectedVersion());
      assertEquals(STATION_ID, event.originStationId());
      assertEquals(0, event.attempts());
      assertEquals(1, scalarInt(database.connection(), "SELECT count(*) FROM operacao_pendente"));
      assertEquals(2, scalarInt(database.connection(), "SELECT count(*) FROM evento_auditoria"));
      assertEquals(
          "PENDENTE", scalarString(database.connection(), "SELECT estado FROM operacao_pendente"));
      assertEquals(
          "CLIENTE_CRIADO",
          scalarString(
              database.connection(),
              "SELECT acao FROM evento_auditoria WHERE acao = 'CLIENTE_CRIADO'"));
      assertEquals(
          STATION_ID.toString(),
          scalarString(
              database.connection(),
              "SELECT origem_estacao_id FROM evento_auditoria WHERE acao = 'CLIENTE_CRIADO'"));
      assertEquals(
          "{}",
          scalarString(
              database.connection(),
              "SELECT detalhes_json FROM evento_auditoria WHERE acao = 'CLIENTE_CRIADO'"));
    }
    try (LocalDatabaseSession reopened = LocalDatabaseSession.open(temp.resolve("local.sqlite"))) {
      assertEquals(1, scalarInt(reopened.connection(), "SELECT count(*) FROM cliente"));
      assertEquals(1, scalarInt(reopened.connection(), "SELECT count(*) FROM operacao_pendente"));
      assertEquals(2, scalarInt(reopened.connection(), "SELECT count(*) FROM evento_auditoria"));
      assertEquals(0, scalarInt(reopened.connection(), "SELECT tentativas FROM operacao_pendente"));
    }
  }

  @Test
  void realOutboxInsertFailureRollsBackTheClientInsert(@TempDir Path temp) throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("rollback.sqlite"))) {
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      SqliteClientRepository clients =
          new SqliteClientRepository(database.connection(), unitOfWork);
      SqliteOutboxRepository outbox = new SqliteOutboxRepository(database.connection(), unitOfWork);
      RegisterClientUseCase useCase = registerClientUseCase(unitOfWork, clients, outbox, database);
      try (Statement statement = database.connection().createStatement()) {
        statement.execute(
            "CREATE TRIGGER reject_outbox BEFORE INSERT ON operacao_pendente "
                + "BEGIN SELECT RAISE(ABORT, 'outbox unavailable'); END");
      }

      assertThrows(RuntimeException.class, () -> useCase.register("Cliente fictícia", null));

      assertEquals(0, scalarInt(database.connection(), "SELECT count(*) FROM cliente"));
      assertEquals(0, scalarInt(database.connection(), "SELECT count(*) FROM operacao_pendente"));
      assertTrue(database.connection().getAutoCommit());
    }
  }

  @Test
  void auditInsertFailureRollsBackClientAndOutboxTogether(@TempDir Path temp) throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("audit-rollback.sqlite"))) {
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      SqliteClientRepository clients =
          new SqliteClientRepository(database.connection(), unitOfWork);
      SqliteOutboxRepository outbox = new SqliteOutboxRepository(database.connection(), unitOfWork);
      RegisterClientUseCase useCase = registerClientUseCase(unitOfWork, clients, outbox, database);
      try (Statement statement = database.connection().createStatement()) {
        statement.execute(
            "CREATE TRIGGER reject_audit BEFORE INSERT ON evento_auditoria "
                + "BEGIN SELECT RAISE(ABORT, 'audit unavailable'); END");
      }

      assertThrows(RuntimeException.class, () -> useCase.register("Cliente fictícia", null));
      assertEquals(0, scalarInt(database.connection(), "SELECT count(*) FROM cliente"));
      assertEquals(0, scalarInt(database.connection(), "SELECT count(*) FROM operacao_pendente"));
      assertEquals(1, scalarInt(database.connection(), "SELECT count(*) FROM evento_auditoria"));
      assertTrue(database.connection().getAutoCommit());
    }
  }

  @Test
  void attemptAndSynchronizationStateChangeOnlyWhenExplicitlyRecordedWithServerConfirmation(
      @TempDir Path temp) throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("confirm.sqlite"))) {
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      SqliteClientRepository clients =
          new SqliteClientRepository(database.connection(), unitOfWork);
      SqliteOutboxRepository outbox = new SqliteOutboxRepository(database.connection(), unitOfWork);
      RegisterClientUseCase useCase = registerClientUseCase(unitOfWork, clients, outbox, database);
      Client saved = useCase.register("Ana Souza", null);
      PendingOperation pending = outbox.findPending(1).getFirst();

      assertTrue(unitOfWork.execute(() -> outbox.recordAttempt(pending.id())));
      assertEquals(1, outbox.findPending(1).getFirst().attempts());
      assertFalse(
          unitOfWork.execute(
              () -> outbox.markSynchronized(new ServerConfirmation(UUID.randomUUID(), 1, NOW))));
      assertTrue(
          unitOfWork.execute(
              () -> outbox.markSynchronized(new ServerConfirmation(pending.id(), 3, NOW))));
      assertFalse(
          unitOfWork.execute(
              () -> outbox.markSynchronized(new ServerConfirmation(pending.id(), 3, NOW))));
      assertEquals(0, outbox.findPending(10).size());
      assertEquals(
          1,
          scalarInt(
              database.connection(),
              "SELECT count(*) FROM cliente WHERE id = '" + saved.id() + "'"));
      assertEquals(
          3,
          scalarInt(
              database.connection(), "SELECT versao_remota_confirmada FROM operacao_pendente"));
    }
  }

  @Test
  void repositoriesRejectWritesOutsideUnitOfWork(@TempDir Path temp) throws Exception {
    try (LocalDatabaseSession database = LocalDatabaseSession.open(temp.resolve("guard.sqlite"))) {
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      SqliteClientRepository clients =
          new SqliteClientRepository(database.connection(), unitOfWork);
      Client client = new Client(UUID.randomUUID(), "Cliente", null, NOW);
      assertThrows(IllegalStateException.class, () -> clients.save(client));
      assertThrows(
          IllegalStateException.class,
          () ->
              new SqliteOutboxRepository(database.connection(), unitOfWork)
                  .recordAttempt(UUID.randomUUID()));
    }
  }

  private int scalarInt(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      return result.next() ? result.getInt(1) : -1;
    }
  }

  private RegisterClientUseCase registerClientUseCase(
      SqliteUnitOfWork unitOfWork,
      SqliteClientRepository clients,
      SqliteOutboxRepository outbox,
      LocalDatabaseSession database) {
    SessionContext sessions = new SessionContext();
    Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher();
    new CreateFirstAdministratorUseCase(
            unitOfWork,
            new SqliteInitialSetupRepository(database.connection(), unitOfWork),
            hasher,
            new SqliteAuditRepository(database.connection(), unitOfWork),
            STATION_ID)
        .create(
            new InitialAdministratorSetup(
                "Espaço Tucumã",
                "Admin de teste",
                "admin",
                "SenhaTesteOffline2026!".toCharArray()));
    try (var statement =
        database
            .connection()
            .prepareStatement(
                "SELECT id, nome, login FROM usuario WHERE perfil = 'ADMINISTRADOR'")) {
      try (var result = statement.executeQuery()) {
        if (!result.next()) throw new IllegalStateException("Conta de teste não criada.");
        UUID userId = UUID.fromString(result.getString("id"));
        sessions.establish(
            new AuthenticatedUser(
                userId,
                result.getString("nome"),
                result.getString("login"),
                UserRole.ADMINISTRADOR));
      }
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
    return new RegisterClientUseCase(
        unitOfWork,
        clients,
        outbox,
        STATION_ID,
        new AuthorizationService(sessions),
        new SqliteAuditRepository(database.connection(), unitOfWork));
  }

  private String scalarString(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      return result.next() ? result.getString(1) : null;
    }
  }
}
