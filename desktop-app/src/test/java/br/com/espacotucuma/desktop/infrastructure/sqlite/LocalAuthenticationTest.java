package br.com.espacotucuma.desktop.infrastructure.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.client.RegisterClientUseCase;
import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationException;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.ChangeUserRoleUseCase;
import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.DeactivateUserUseCase;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.application.identity.LoginUseCase;
import br.com.espacotucuma.desktop.application.identity.LogoutUseCase;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalAuthenticationTest {
  private static final String ADMIN_PASSWORD = "SenhaOfflineForte2026!";
  private static final String RECEPTION_PASSWORD = "SenhaRecepcaoForte2026!";

  @Test
  void loginFailureIsAuditedWithoutPasswordAndClearsPreviousSession(@TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database = LocalDatabaseSession.open(temp.resolve("auth.sqlite"))) {
      Fixture fixture = fixture(database);
      AuthenticatedUser admin =
          fixture.login.login("ADMIN", ADMIN_PASSWORD.toCharArray()).orElseThrow().user();
      assertEquals(UserRole.ADMINISTRADOR, admin.role());
      char[] wrong = "incorrect-password".toCharArray();
      assertTrue(fixture.login.login("missing", wrong).isEmpty());
      assertTrue(java.util.Arrays.equals(new char[wrong.length], wrong));
      assertTrue(fixture.sessions.current().isEmpty());
      assertEquals(2, scalarInt(database, "SELECT count(*) FROM tentativa_login"));
      assertEquals(
          0,
          scalarInt(
              database, "SELECT count(*) FROM tentativa_login WHERE login LIKE '%password%'"));
      assertEquals(
          0,
          scalarInt(
              database,
              "SELECT count(*) FROM pragma_table_info('tentativa_login') WHERE name LIKE '%senha%'"));
    }
  }

  @Test
  void logoutAndAccountSwitchCreateIsolatedRoleSessionsAndPermissions(@TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database = LocalDatabaseSession.open(temp.resolve("switch.sqlite"))) {
      Fixture fixture = fixture(database);
      var admin = fixture.login.login("admin", ADMIN_PASSWORD.toCharArray()).orElseThrow();
      assertTrue(fixture.authorization.isAllowed(Permission.BACKUP_MANAGE));
      new LogoutUseCase(fixture.sessions).logout();
      assertThrows(
          AuthorizationException.class,
          () -> fixture.authorization.require(Permission.CLIENT_REGISTER));

      var reception =
          fixture.login.login("RECEPCAO", RECEPTION_PASSWORD.toCharArray()).orElseThrow();
      assertNotEquals(admin.id(), reception.id());
      assertEquals(UserRole.RECEPCAO, reception.user().role());
      assertTrue(fixture.authorization.isAllowed(Permission.CLIENT_REGISTER));
      assertFalse(fixture.authorization.isAllowed(Permission.FINANCIAL_REVERSAL));
      assertFalse(fixture.authorization.isAllowed(Permission.MANAGE_PERMISSIONS));
      assertFalse(fixture.authorization.isAllowed(Permission.BACKUP_MANAGE));
      assertThrows(
          AuthorizationException.class,
          () -> fixture.authorization.require(Permission.BACKUP_MANAGE));
    }
  }

  @Test
  void registerClientEnforcesPermissionInsideUseCase(@TempDir Path temp) throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("client-auth.sqlite"))) {
      Fixture fixture = fixture(database);
      var useCase =
          new RegisterClientUseCase(
              fixture.unitOfWork,
              new SqliteClientRepository(database.connection(), fixture.unitOfWork),
              new SqliteOutboxRepository(database.connection(), fixture.unitOfWork),
              UUID.randomUUID(),
              fixture.authorization);

      assertThrows(AuthorizationException.class, () -> useCase.register("Cliente sintética", null));
      assertEquals(0, scalarInt(database, "SELECT count(*) FROM cliente"));
      fixture.sessions.establish(
          new AuthenticatedUser(
              UUID.randomUUID(), "Profissional", "profissional", UserRole.PROFISSIONAL));
      assertThrows(
          AuthorizationException.class, () -> useCase.register("Cliente sem acesso", null));
      fixture.login.login("admin", ADMIN_PASSWORD.toCharArray());
      fixture.login.login("admin", ADMIN_PASSWORD.toCharArray());
      useCase.register("Cliente sintética", null);
      assertEquals(1, scalarInt(database, "SELECT count(*) FROM cliente"));
    }
  }

  @Test
  void adminCanManageRolesButCannotRemoveTheLastAdministrator(@TempDir Path temp) throws Exception {
    try (LocalDatabaseSession database = LocalDatabaseSession.open(temp.resolve("users.sqlite"))) {
      Fixture fixture = fixture(database);
      var admin = fixture.login.login("admin", ADMIN_PASSWORD.toCharArray()).orElseThrow();
      UUID adminId = admin.user().id();
      assertThrows(IllegalArgumentException.class, () -> fixture.deactivate.deactivate(adminId));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.changeRole.change(adminId, UserRole.RECEPCAO));
      fixture.changeRole.change(fixture.receptionId, UserRole.PROFISSIONAL);
      assertEquals(
          "PROFISSIONAL",
          scalarString(
              database, "SELECT perfil FROM usuario WHERE id = '" + fixture.receptionId + "'"));
      fixture.deactivate.deactivate(fixture.receptionId);
      assertEquals(
          0,
          scalarInt(
              database, "SELECT ativo FROM usuario WHERE id = '" + fixture.receptionId + "'"));
      assertTrue(fixture.login.login("recepcao", RECEPTION_PASSWORD.toCharArray()).isEmpty());
    }
  }

  @Test
  void nonAdministratorCannotChangeProfileOrDeactivateAccount(@TempDir Path temp) throws Exception {
    try (LocalDatabaseSession database = LocalDatabaseSession.open(temp.resolve("denied.sqlite"))) {
      Fixture fixture = fixture(database);
      fixture.login.login("recepcao", RECEPTION_PASSWORD.toCharArray()).orElseThrow();
      assertThrows(
          AuthorizationException.class,
          () -> fixture.changeRole.change(fixture.receptionId, UserRole.ADMINISTRADOR));
      assertThrows(
          AuthorizationException.class, () -> fixture.deactivate.deactivate(fixture.receptionId));
    }
  }

  private Fixture fixture(LocalDatabaseSession database) throws Exception {
    SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
    Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher();
    new CreateFirstAdministratorUseCase(
            unitOfWork, new SqliteInitialSetupRepository(database.connection(), unitOfWork), hasher)
        .create(
            new InitialAdministratorSetup(
                "Espaço Tucumã", "Admin Teste", "admin", ADMIN_PASSWORD.toCharArray()));
    UUID receptionId = UUID.randomUUID();
    String now = "2026-11-01T12:00:00Z";
    unitOfWork.execute(
        () -> {
          try (PreparedStatement statement =
              database
                  .connection()
                  .prepareStatement(
                      "INSERT INTO usuario (id, nome, login, senha_hash, perfil, ativo, criado_em_utc, atualizado_em_utc) VALUES (?, ?, ?, ?, ?, 1, ?, ?)")) {
            statement.setString(1, receptionId.toString());
            statement.setString(2, "Recepcionista Teste");
            statement.setString(3, "recepcao");
            statement.setString(4, hasher.hash(RECEPTION_PASSWORD.toCharArray()));
            statement.setString(5, UserRole.RECEPCAO.name());
            statement.setString(6, now);
            statement.setString(7, now);
            statement.executeUpdate();
          } catch (Exception failure) {
            throw new IllegalStateException(failure);
          }
          return null;
        });
    SessionContext sessions = new SessionContext();
    AuthorizationService authorization = new AuthorizationService(sessions);
    var users = new SqliteUserAdministrationRepository(database.connection(), unitOfWork);
    return new Fixture(
        unitOfWork,
        sessions,
        authorization,
        new LoginUseCase(
            unitOfWork,
            new SqliteLocalCredentialRepository(database.connection(), unitOfWork),
            new SqliteLoginAttemptRepository(database.connection(), unitOfWork),
            hasher,
            sessions),
        new DeactivateUserUseCase(unitOfWork, users, authorization, sessions),
        new ChangeUserRoleUseCase(unitOfWork, users, authorization, sessions),
        receptionId);
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

  private record Fixture(
      SqliteUnitOfWork unitOfWork,
      SessionContext sessions,
      AuthorizationService authorization,
      LoginUseCase login,
      DeactivateUserUseCase deactivate,
      ChangeUserRoleUseCase changeRole,
      UUID receptionId) {}
}
