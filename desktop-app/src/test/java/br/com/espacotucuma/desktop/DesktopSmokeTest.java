package br.com.espacotucuma.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.config.DesktopEnvironment;
import br.com.espacotucuma.desktop.application.config.EnvironmentProfile;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSmokeTest {

  @Test
  void opensAndClosesTheDesktopWithoutRemoteServices(@TempDir Path tempDirectory) throws Exception {
    DesktopEnvironment environment =
        new DesktopEnvironment(EnvironmentProfile.TEST, tempDirectory.resolve("test"));
    try (DesktopRuntime runtime = DesktopRuntime.open(environment)) {
      runtime
          .createFirstAdministrator()
          .create(
              new InitialAdministratorSetup(
                  "Espaço Tucumã",
                  "Admin de teste",
                  "admin",
                  "SenhaOfflineForte2026!".toCharArray()));
    }

    String javaExecutable =
        Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
            .toString();
    String testClasspath = System.getProperty("surefire.test.class.path");
    Path outputFile = tempDirectory.resolve("desktop-smoke.log");
    assertTrue(
        testClasspath != null && !testClasspath.isBlank(), "Surefire test classpath is required");

    Process process =
        new ProcessBuilder(
                javaExecutable,
                "-Dtucuma.profile=test",
                "-Dtucuma.data-root=" + tempDirectory,
                "-cp",
                testClasspath,
                DesktopLauncher.class.getName(),
                "--smoke-test")
            .redirectErrorStream(true)
            .redirectOutput(outputFile.toFile())
            .start();

    boolean exited = process.waitFor(Duration.ofSeconds(60).toMillis(), TimeUnit.MILLISECONDS);
    if (!exited) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }

    String output = new String(Files.readAllBytes(outputFile), Charset.defaultCharset());
    assertTrue(exited, "Desktop smoke process did not exit. Output: " + output);
    assertEquals(0, process.exitValue(), "Desktop smoke process failed. Output: " + output);

    Path databaseFile = tempDirectory.resolve("test").resolve("tucuma.sqlite");
    assertTrue(Files.isRegularFile(databaseFile), "The local SQLite database was not created");
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        var statement = connection.createStatement();
        var result = statement.executeQuery("PRAGMA quick_check")) {
      assertTrue(result.next());
      assertEquals("ok", result.getString(1));
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        var statement = connection.createStatement();
        var result =
            statement.executeQuery(
                "SELECT COUNT(*) FROM cliente WHERE nome = 'Cliente de validação do pacote'")) {
      assertTrue(result.next());
      assertEquals(1, result.getInt(1), "Smoke startup should persist its synthetic client");
    }
  }

  private boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }
}
