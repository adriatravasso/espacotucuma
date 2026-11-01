package br.com.espacotucuma.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.config.DesktopEnvironment;
import br.com.espacotucuma.desktop.application.config.DesktopPreferences;
import br.com.espacotucuma.desktop.application.config.EnvironmentProfile;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.ui.TucumaDesktopApplication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
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

  private DesktopEnvironment environment(Path root) {
    return new DesktopEnvironment(EnvironmentProfile.TEST, root.resolve("profile"));
  }
}
