package br.com.espacotucuma.desktop.infrastructure.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.identity.ChangeLocalPasswordUseCase;
import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitialAdministratorSetupTest {

  @Test
  void passwordHashUsesUniqueSaltsAndVerifiesWithoutStoringThePassword() {
    Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher();
    char[] password = "SenhaForteOffline2026!".toCharArray();

    String firstHash = hasher.hash(password);
    String secondHash = hasher.hash(password);

    assertNotEquals(firstHash, secondHash);
    assertTrue(firstHash.startsWith("pbkdf2-sha256$600000$"));
    assertTrue(hasher.verify(password, firstHash));
    assertFalse(hasher.verify("outraSenha2026!".toCharArray(), firstHash));
    assertFalse(hasher.verify(password, "texto puro"));
  }

  @Test
  void firstSetupCreatesOneOfflineAdministratorAndEstablishmentAndRejectsAnother(@TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database = LocalDatabaseSession.open(temp.resolve("setup.sqlite"))) {
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      SqliteInitialSetupRepository repository =
          new SqliteInitialSetupRepository(database.connection(), unitOfWork);
      Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher();
      CreateFirstAdministratorUseCase useCase =
          new CreateFirstAdministratorUseCase(
              unitOfWork,
              repository,
              hasher,
              new SqliteAuditRepository(database.connection(), unitOfWork),
              java.util.UUID.randomUUID());
      char[] password = "SenhaForteOffline2026!".toCharArray();

      assertTrue(useCase.isSetupRequired());
      assertEquals(0, scalarInt(database.connection(), "SELECT count(*) FROM usuario"));
      useCase.create(setup(password));
      assertFalse(useCase.isSetupRequired());

      assertEquals(1, scalarInt(database.connection(), "SELECT count(*) FROM estabelecimento"));
      assertEquals(1, scalarInt(database.connection(), "SELECT count(*) FROM usuario"));
      assertEquals(
          1,
          scalarInt(
              database.connection(),
              "SELECT count(*) FROM usuario WHERE perfil = 'ADMINISTRADOR'"));
      assertEquals("admin", scalarString(database.connection(), "SELECT login FROM usuario"));
      assertEquals(
          "Espaço Tucumã Teste",
          scalarString(database.connection(), "SELECT nome_fantasia FROM estabelecimento"));

      String storedHash = scalarString(database.connection(), "SELECT senha_hash FROM usuario");
      assertNotEquals(new String(password), storedHash);
      assertTrue(hasher.verify(password, storedHash));
      assertThrows(IllegalStateException.class, () -> useCase.create(setup(password)));
      assertEquals(1, scalarInt(database.connection(), "SELECT count(*) FROM usuario"));
      assertEquals(1, scalarInt(database.connection(), "SELECT count(*) FROM estabelecimento"));
      assertDoesNotThrow(
          () ->
              unitOfWork.execute(
                  () -> {
                    try (var statement =
                        database
                            .connection()
                            .prepareStatement(
                                "INSERT INTO usuario (id, nome, login, senha_hash, perfil, "
                                    + "criado_em_utc, atualizado_em_utc) VALUES (?, ?, ?, ?, 'ADMINISTRADOR', ?, ?)")) {
                      statement.setString(1, "b621b7a7-77f9-45cf-9950-73dccbbda61f");
                      statement.setString(2, "Segundo administrador sintético");
                      statement.setString(3, "outro-admin");
                      statement.setString(4, "hash-sintetico-de-teste-comprido");
                      statement.setString(5, "2026-11-01T12:00:00Z");
                      statement.setString(6, "2026-11-01T12:00:00Z");
                      statement.executeUpdate();
                    } catch (java.sql.SQLException failure) {
                      throw new IllegalStateException(failure);
                    }
                    return null;
                  }));
      assertEquals(2, scalarInt(database.connection(), "SELECT count(*) FROM usuario"));
    }
  }

  @Test
  void invalidPasswordAndFailedAdministratorInsertDoNotLeavePartialSetup(@TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("rollback.sqlite"))) {
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      SqliteInitialSetupRepository repository =
          new SqliteInitialSetupRepository(database.connection(), unitOfWork);
      CreateFirstAdministratorUseCase useCase =
          new CreateFirstAdministratorUseCase(
              unitOfWork,
              repository,
              new Pbkdf2PasswordHasher(),
              new SqliteAuditRepository(database.connection(), unitOfWork),
              java.util.UUID.randomUUID());

      assertThrows(
          IllegalArgumentException.class,
          () ->
              useCase.create(
                  new InitialAdministratorSetup(
                      "Espaço Tucumã Teste",
                      "Administradora Sintética",
                      "admin",
                      "curta".toCharArray())));
      assertEquals(0, scalarInt(database.connection(), "SELECT count(*) FROM estabelecimento"));
      assertEquals(0, scalarInt(database.connection(), "SELECT count(*) FROM usuario"));

      try (Statement statement = database.connection().createStatement()) {
        statement.execute(
            "CREATE TRIGGER reject_admin BEFORE INSERT ON usuario "
                + "BEGIN SELECT RAISE(ABORT, 'administrator unavailable'); END");
      }
      assertThrows(
          IllegalStateException.class,
          () -> useCase.create(setup("SenhaForteOffline2026!".toCharArray())));
      assertEquals(0, scalarInt(database.connection(), "SELECT count(*) FROM estabelecimento"));
      assertEquals(0, scalarInt(database.connection(), "SELECT count(*) FROM usuario"));
      assertTrue(database.connection().getAutoCommit());
    }
  }

  @Test
  void failedInitialAuditRollsBackEstablishmentAndAdministrator(@TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("audit-failure.sqlite"))) {
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      try (Statement statement = database.connection().createStatement()) {
        statement.execute(
            "CREATE TRIGGER reject_audit BEFORE INSERT ON evento_auditoria "
                + "BEGIN SELECT RAISE(ABORT, 'audit unavailable'); END");
      }
      CreateFirstAdministratorUseCase useCase =
          new CreateFirstAdministratorUseCase(
              unitOfWork,
              new SqliteInitialSetupRepository(database.connection(), unitOfWork),
              new Pbkdf2PasswordHasher(),
              new SqliteAuditRepository(database.connection(), unitOfWork),
              java.util.UUID.randomUUID());

      assertThrows(
          IllegalStateException.class,
          () -> useCase.create(setup("SenhaForteOffline2026!".toCharArray())));
      assertEquals(0, scalarInt(database.connection(), "SELECT count(*) FROM estabelecimento"));
      assertEquals(0, scalarInt(database.connection(), "SELECT count(*) FROM usuario"));
      assertEquals(0, scalarInt(database.connection(), "SELECT count(*) FROM evento_auditoria"));
      assertTrue(database.connection().getAutoCommit());
    }
  }

  @Test
  void localPasswordChangeRequiresTheCurrentCredentialAndWorksOffline(@TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("password.sqlite"))) {
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      SqliteInitialSetupRepository setupRepository =
          new SqliteInitialSetupRepository(database.connection(), unitOfWork);
      Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher();
      new CreateFirstAdministratorUseCase(
              unitOfWork,
              setupRepository,
              hasher,
              new SqliteAuditRepository(database.connection(), unitOfWork),
              java.util.UUID.randomUUID())
          .create(setup("SenhaAtualLocal2026!".toCharArray()));
      ChangeLocalPasswordUseCase changePassword =
          new ChangeLocalPasswordUseCase(
              unitOfWork,
              new SqliteLocalCredentialRepository(database.connection(), unitOfWork),
              hasher);

      char[] wrongCurrent = "SenhaErradaLocal2026!".toCharArray();
      char[] proposedAfterFailure = "NovaSenhaLocal2026!".toCharArray();
      assertThrows(
          SecurityException.class,
          () -> changePassword.change("ADMIN", wrongCurrent, proposedAfterFailure));
      assertTrue(java.util.Arrays.equals(new char[wrongCurrent.length], wrongCurrent));
      assertTrue(
          java.util.Arrays.equals(new char[proposedAfterFailure.length], proposedAfterFailure));

      char[] current = "SenhaAtualLocal2026!".toCharArray();
      char[] next = "NovaSenhaLocal2026!".toCharArray();
      changePassword.change("ADMIN", current, next);

      String updatedHash = scalarString(database.connection(), "SELECT senha_hash FROM usuario");
      assertTrue(hasher.verify("NovaSenhaLocal2026!".toCharArray(), updatedHash));
      assertFalse(hasher.verify("SenhaAtualLocal2026!".toCharArray(), updatedHash));
      assertEquals(1, scalarInt(database.connection(), "SELECT count(*) FROM usuario"));
    }
  }

  private InitialAdministratorSetup setup(char[] password) {
    return new InitialAdministratorSetup(
        "Espaço Tucumã Teste", "Administradora Sintética", "admin", password);
  }

  private int scalarInt(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      return result.next() ? result.getInt(1) : -1;
    }
  }

  private String scalarString(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      return result.next() ? result.getString(1) : null;
    }
  }
}
