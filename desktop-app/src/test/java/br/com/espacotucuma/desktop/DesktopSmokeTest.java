package br.com.espacotucuma.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                DesktopSmokeLauncher.class.getName(),
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
  }

  private boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }
}
