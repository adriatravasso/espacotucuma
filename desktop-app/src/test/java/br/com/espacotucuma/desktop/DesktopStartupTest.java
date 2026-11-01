package br.com.espacotucuma.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.config.DesktopEnvironment;
import br.com.espacotucuma.desktop.application.config.DesktopPreferences;
import br.com.espacotucuma.desktop.application.config.EnvironmentProfile;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.ui.TucumaDesktopApplication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class DesktopStartupTest {

  @Test
  void startupCreatesLocalPreferencesDatabaseAndLogsWithoutRemoteServices(@TempDir Path temp)
      throws Exception {
    DesktopEnvironment environment = environment(temp);

    try (DesktopRuntime runtime = DesktopRuntime.open(environment)) {
      assertEquals(environment.databaseFile(), runtime.databaseFile());
      assertTrue(Files.isRegularFile(environment.preferencesFile()));
      assertTrue(Files.isDirectory(environment.logsDirectory()));
      try (var logFiles = Files.list(environment.logsDirectory())) {
        assertTrue(logFiles.anyMatch(Files::isRegularFile));
      }
      assertTrue(Files.isRegularFile(environment.databaseFile()));
    }
  }

  @Test
  @Timeout(30)
  void measuresFirstStartupAndLocalClientRegistration(@TempDir Path temp) throws Exception {
    DesktopEnvironment environment = environment(temp);
    long startupStarted = System.nanoTime();
    try (DesktopRuntime runtime = DesktopRuntime.open(environment)) {
      long startupMillis =
          java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startupStarted);
      runtime
          .createFirstAdministrator()
          .create(
              new InitialAdministratorSetup(
                  "Espaço Tucumã",
                  "Admin de teste",
                  "admin",
                  "SenhaTesteOffline2026!".toCharArray()));
      assertTrue(
          runtime.login().login("admin", "SenhaTesteOffline2026!".toCharArray()).isPresent());

      long registrationStarted = System.nanoTime();
      runtime.registerClient().register("Cliente de desempenho", "92900000002");
      long registrationMillis =
          java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
              System.nanoTime() - registrationStarted);

      assertTrue(startupMillis < 10000, "Cold start took " + startupMillis + " ms");
      assertTrue(
          registrationMillis < 2000, "Client registration took " + registrationMillis + " ms");
      System.out.println("TUC53_COLD_START_MS=" + startupMillis);
      System.out.println("TUC53_CLIENT_REGISTRATION_MS=" + registrationMillis);
    }
  }

  @Test
  void refusesASecondInstanceForTheSameDatabase(@TempDir Path temp) throws Exception {
    DesktopEnvironment environment = environment(temp);

    try (DesktopRuntime first = DesktopRuntime.open(environment)) {
      assertThrows(SQLException.class, () -> DesktopRuntime.open(environment));
      assertTrue(Files.isRegularFile(first.databaseFile()));
    }
    try (DesktopRuntime reopened = DesktopRuntime.open(environment)) {
      assertTrue(Files.isRegularFile(reopened.databaseFile()));
    }
  }

  @Test
  void loadsTheConfiguredAbsoluteDatabasePathFromLocalPreferences(@TempDir Path temp)
      throws Exception {
    DesktopEnvironment environment = environment(temp);
    Files.createDirectories(environment.dataDirectory());
    Path configuredDatabase = temp.resolve("restored").resolve("tucuma.sqlite").toAbsolutePath();
    java.util.Properties properties = new java.util.Properties();
    properties.setProperty("database.file", configuredDatabase.toString());
    try (var output = Files.newOutputStream(environment.preferencesFile())) {
      properties.store(output, "test");
    }

    DesktopPreferences preferences = DesktopPreferences.loadOrCreate(environment);

    assertEquals(configuredDatabase, preferences.databaseFile());
  }

  @Test
  void inaccessiblePathShowsRecoveryGuidanceWithoutChangingExistingData(@TempDir Path temp)
      throws Exception {
    Path blocker = temp.resolve("not-a-directory");
    byte[] originalContents =
        "preserve-this-file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(blocker, originalContents);
    Path databaseFile = blocker.resolve("tucuma.sqlite");

    IOException failure =
        assertThrows(IOException.class, () -> LocalDatabaseSession.open(databaseFile));

    assertArrayEquals(originalContents, Files.readAllBytes(blocker));
    String message = TucumaDesktopApplication.startupErrorMessage(failure);
    assertTrue(message.contains("não foi substituído nem apagado"));
    assertTrue(message.contains("recuperação"));
  }

  @Test
  void corruptDatabaseIsRejectedAndPreserved(@TempDir Path temp) throws Exception {
    Path databaseFile = temp.resolve("tucuma.sqlite");
    byte[] originalContents =
        "not-a-valid-sqlite-database".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(databaseFile, originalContents);

    assertThrows(SQLException.class, () -> LocalDatabaseSession.open(databaseFile));

    assertArrayEquals(originalContents, Files.readAllBytes(databaseFile));
  }

  @Test
  @Timeout(20)
  void lockedDatabaseIsDiagnosedPreservedAndCanBeReopened(@TempDir Path temp) throws Exception {
    Path databaseFile = temp.resolve("locked.sqlite");
    try (LocalDatabaseSession database = LocalDatabaseSession.open(databaseFile);
        var statement = database.connection().createStatement()) {
      statement.executeUpdate("CREATE TABLE test_preserved_data (value TEXT NOT NULL)");
      statement.executeUpdate("INSERT INTO test_preserved_data VALUES ('confirmed-before-lock')");
    }

    try (var blocker = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        var statement = blocker.createStatement()) {
      statement.execute("BEGIN EXCLUSIVE");
      Exception failure =
          assertThrows(Exception.class, () -> LocalDatabaseSession.open(databaseFile));
      String guidance = TucumaDesktopApplication.startupErrorMessage(failure);
      assertTrue(guidance.contains("não foi substituído nem apagado"));
      assertTrue(Files.isRegularFile(databaseFile));
    }

    try (LocalDatabaseSession reopened = LocalDatabaseSession.open(databaseFile);
        var statement = reopened.connection().createStatement();
        var result = statement.executeQuery("SELECT value FROM test_preserved_data")) {
      assertTrue(result.next());
      assertEquals("confirmed-before-lock", result.getString(1));
    }
  }

  @Test
  void startupFailureDoesNotWritePersonalPathsOrExceptionDetailsToLogs(@TempDir Path temp)
      throws Exception {
    Path privateRoot = temp.resolve("PII_CANARY_Cliente_JoaoDaSilva");
    DesktopEnvironment environment = environment(privateRoot);
    Files.createDirectories(environment.dataDirectory());
    Files.createDirectory(environment.databaseFile());

    assertThrows(Exception.class, () -> DesktopRuntime.open(environment));

    try (var logFiles = Files.list(environment.logsDirectory())) {
      String logs =
          logFiles
              .filter(Files::isRegularFile)
              .map(
                  path -> {
                    try {
                      return Files.readString(path);
                    } catch (IOException failure) {
                      throw new IllegalStateException(failure);
                    }
                  })
              .collect(java.util.stream.Collectors.joining("\n"));
      assertFalse(logs.contains("PII_CANARY"));
      assertFalse(logs.contains(privateRoot.toString()));
      assertFalse(logs.contains("Caused by:"));
      assertTrue(logs.contains("Falha ao preparar a aplicação desktop"));
    }
  }

  private DesktopEnvironment environment(Path root) {
    return new DesktopEnvironment(EnvironmentProfile.TEST, root.resolve("profile"));
  }
}
