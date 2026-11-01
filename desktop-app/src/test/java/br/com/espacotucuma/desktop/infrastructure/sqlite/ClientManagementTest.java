package br.com.espacotucuma.desktop.infrastructure.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.client.Client;
import br.com.espacotucuma.desktop.application.client.ClientContactFilter;
import br.com.espacotucuma.desktop.application.client.ClientPageQuery;
import br.com.espacotucuma.desktop.application.client.ClientSortField;
import br.com.espacotucuma.desktop.application.client.MaintainClientUseCase;
import br.com.espacotucuma.desktop.application.client.RegisterClientUseCase;
import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationException;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.application.outbox.ServerConfirmation;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientManagementTest {

  private static final UUID STATION = UUID.fromString("f169a389-5907-4be8-a5f7-e791f07ff10b");

  @Test
  void receptionCanMaintainClientsButOnlyAdministratorsCanDeactivate() {
    SessionContext sessions = new SessionContext();
    sessions.establish(
        new AuthenticatedUser(UUID.randomUUID(), "Recepção", "recepcao", UserRole.RECEPCAO));
    var authorization = new AuthorizationService(sessions);

    authorization.require(Permission.CLIENT_READ);
    authorization.require(Permission.CLIENT_UPDATE);
    assertThrows(
        AuthorizationException.class, () -> authorization.require(Permission.CLIENT_DEACTIVATE));
  }

  @Test
  void registersSearchesUpdatesAndInactivatesWithoutRemovingAttendance(@TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("clients.sqlite"))) {
      Client first =
          fixture.register.register(
              "Maria Oliveira",
              "(92) 99999-0001",
              "Contato da responsável",
              "Prefere horário da manhã");
      Client homonym = fixture.register.register("Maria Oliveira", "92999990001");

      assertEquals("CLI-000001", first.internalCode());
      assertEquals("CLI-000002", homonym.internalCode());
      assertEquals("92999990001", first.phone());
      assertEquals(
          2,
          fixture
              .clients
              .findPotentialDuplicates("Maria Oliveira", "(92) 99999-0001", null)
              .size());
      assertEquals(
          first.id(),
          fixture.clients.searchPage(query("CLI-000001", false)).items().getFirst().id());

      UUID attendanceId = fixture.addAttendance(first.id());
      Client updated =
          fixture.maintain.update(
              first.id(),
              "Maria Oliveira Santos",
              "(92) 98888-0002",
              "WhatsApp",
              "Sem preferência registrada");
      assertEquals("CLI-000001", updated.internalCode());
      assertEquals("92988880002", updated.phone());
      assertEquals("WhatsApp", updated.optionalContact());
      assertEquals("Sem preferência registrada", updated.preferences());

      fixture.maintain.deactivate(first.id());
      assertFalse(fixture.clients.findById(first.id()).orElseThrow().active());
      assertEquals(
          0, fixture.clients.searchPage(query("Maria Oliveira Santos", false)).totalItems());
      Client inactive =
          fixture.clients.searchPage(query("Maria Oliveira Santos", true)).items().getFirst();
      assertEquals(first.id(), inactive.id());
      assertFalse(inactive.active());
      assertEquals(1, fixture.clients.attendanceHistory(first.id()).size());
      assertEquals(
          attendanceId, fixture.clients.attendanceHistory(first.id()).getFirst().attendanceId());
      assertEquals(4, fixture.outbox.findPending(10).size());
      assertEquals(
          java.util.List.of(0L, 1L, 2L),
          expectedVersions(fixture.database.connection(), first.id()));
      assertEquals(1, scalar(fixture.database.connection(), "SELECT count(*) FROM atendimento"));
    }
  }

  @Test
  void invalidDataAndFailedOutboxWriteDoNotLeavePartialClientChanges(@TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("client-rollback.sqlite"))) {
      assertThrows(
          IllegalArgumentException.class, () -> fixture.register.register("A", "92999990001"));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.register.register("Nome Válido", "telefone inválido"));
      assertEquals(0, fixture.clients.searchPage(query("", true)).totalItems());

      Client saved = fixture.register.register("Nome Válido", null);
      try (Statement statement = fixture.database.connection().createStatement()) {
        statement.execute(
            "CREATE TRIGGER reject_client_outbox BEFORE INSERT ON operacao_pendente "
                + "BEGIN SELECT RAISE(ABORT, 'outbox unavailable'); END");
      }

      assertThrows(
          RuntimeException.class,
          () -> fixture.maintain.update(saved.id(), "Nome Alterado", null, null, null));
      assertEquals("Nome Válido", fixture.clients.findById(saved.id()).orElseThrow().name());
      assertEquals(1, fixture.outbox.findPending(10).size());
      assertEquals(
          0,
          scalar(
              fixture.database.connection(),
              "SELECT count(*) FROM evento_auditoria WHERE acao = 'CLIENTE_ATUALIZADO'"));
    }
  }

  @Test
  void clientsCannotBeSavedOutsideALocalTransaction(@TempDir Path temp) throws Exception {
    try (LocalDatabaseSession database = LocalDatabaseSession.open(temp.resolve("guard.sqlite"))) {
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      SqliteClientRepository clients =
          new SqliteClientRepository(database.connection(), unitOfWork);
      Client client =
          new Client(UUID.randomUUID(), "Cliente", null, Instant.parse("2026-01-01T00:00:00Z"));
      assertThrows(IllegalStateException.class, () -> clients.save(client));
      assertTrue(database.connection().getAutoCommit());
    }
  }

  @Test
  void newMutationUsesTheLastConfirmedRemoteVersion(@TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("client-version.sqlite"))) {
      Client saved = fixture.register.register("Cliente Versionada", null);
      var create = fixture.outbox.findPending(1).getFirst();
      fixture.unitOfWork.execute(
          () ->
              fixture.outbox.markSynchronized(
                  new ServerConfirmation(create.id(), 7, Instant.parse("2026-11-01T12:00:00Z"))));

      fixture.maintain.update(saved.id(), "Cliente Versionada Editada", null, null, null);

      assertEquals(7L, fixture.outbox.findPending(1).getFirst().expectedVersion());
    }
  }

  private ClientPageQuery query(String search, boolean includeInactive) {
    return new ClientPageQuery(
        search, ClientContactFilter.ALL, ClientSortField.NAME, true, 0, 25, includeInactive);
  }

  private int scalar(java.sql.Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      return result.next() ? result.getInt(1) : -1;
    }
  }

  private java.util.List<Long> expectedVersions(java.sql.Connection connection, UUID clientId)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT versao_esperada FROM operacao_pendente WHERE entidade = 'cliente' "
                + "AND entidade_id = ? ORDER BY rowid"); ) {
      statement.setString(1, clientId.toString());
      try (ResultSet result = statement.executeQuery()) {
        java.util.List<Long> versions = new java.util.ArrayList<>();
        while (result.next()) versions.add(result.getLong(1));
        return java.util.List.copyOf(versions);
      }
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final LocalDatabaseSession database;
    private final SqliteUnitOfWork unitOfWork;
    private final SqliteClientRepository clients;
    private final SqliteOutboxRepository outbox;
    private final RegisterClientUseCase register;
    private final MaintainClientUseCase maintain;
    private final UUID administratorId;

    private Fixture(Path databasePath) throws Exception {
      database = LocalDatabaseSession.open(databasePath);
      unitOfWork = new SqliteUnitOfWork(database.connection());
      SqliteAuditRepository audit = new SqliteAuditRepository(database.connection(), unitOfWork);
      new CreateFirstAdministratorUseCase(
              unitOfWork,
              new SqliteInitialSetupRepository(database.connection(), unitOfWork),
              new Pbkdf2PasswordHasher(),
              audit,
              STATION)
          .create(
              new InitialAdministratorSetup(
                  "Espaço Tucumã",
                  "Admin de teste",
                  "admin",
                  "SenhaTesteOffline2026!".toCharArray()));
      try (Statement statement = database.connection().createStatement();
          ResultSet result =
              statement.executeQuery(
                  "SELECT id, nome, login FROM usuario WHERE perfil = 'ADMINISTRADOR'")) {
        if (!result.next()) throw new IllegalStateException("Conta de teste não criada.");
        administratorId = UUID.fromString(result.getString("id"));
        SessionContext sessions = new SessionContext();
        sessions.establish(
            new AuthenticatedUser(
                administratorId,
                result.getString("nome"),
                result.getString("login"),
                UserRole.ADMINISTRADOR));
        AuthorizationService authorization = new AuthorizationService(sessions);
        clients = new SqliteClientRepository(database.connection(), unitOfWork);
        outbox = new SqliteOutboxRepository(database.connection(), unitOfWork);
        register =
            new RegisterClientUseCase(unitOfWork, clients, outbox, STATION, authorization, audit);
        maintain =
            new MaintainClientUseCase(unitOfWork, clients, outbox, authorization, audit, STATION);
      }
    }

    private UUID addAttendance(UUID clientId) throws Exception {
      UUID professionalId = UUID.randomUUID();
      UUID attendanceId = UUID.randomUUID();
      String now = "2026-10-31T15:00:00Z";
      try (PreparedStatement professional =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO profissional (id, nome, telefone, ativo, criado_em_utc, atualizado_em_utc) "
                      + "VALUES (?, 'Profissional Sintética', NULL, 1, ?, ?)")) {
        professional.setString(1, professionalId.toString());
        professional.setString(2, now);
        professional.setString(3, now);
        professional.executeUpdate();
      }
      try (PreparedStatement attendance =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO atendimento (id, agendamento_id, cliente_id, profissional_id, estado, "
                      + "subtotal_centavos, desconto_centavos, total_centavos, iniciado_em_utc, concluido_em_utc, "
                      + "criado_por_usuario_id, criado_em_utc) VALUES (?, NULL, ?, ?, 'CONCLUIDO', "
                      + "5000, 0, 5000, ?, ?, ?, ?)")) {
        attendance.setString(1, attendanceId.toString());
        attendance.setString(2, clientId.toString());
        attendance.setString(3, professionalId.toString());
        attendance.setString(4, now);
        attendance.setString(5, now);
        attendance.setString(6, administratorId.toString());
        attendance.setString(7, now);
        attendance.executeUpdate();
      }
      return attendanceId;
    }

    @Override
    public void close() throws Exception {
      database.close();
    }
  }
}
