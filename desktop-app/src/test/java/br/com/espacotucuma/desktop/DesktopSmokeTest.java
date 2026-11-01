package br.com.espacotucuma.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DesktopSmokeTest {

  @Test
  void opensAndClosesTheDesktopWithoutRemoteServices() throws Exception {
    String javaExecutable =
        Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
            .toString();
    String testClasspath = System.getProperty("surefire.test.class.path");
    assertTrue(
        testClasspath != null && !testClasspath.isBlank(), "Surefire test classpath is required");

    Process process =
        new ProcessBuilder(
                javaExecutable,
                "-cp",
                testClasspath,
                DesktopSmokeLauncher.class.getName(),
                "--smoke-test")
            .redirectErrorStream(true)
            .start();

    boolean exited = process.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS);
    if (!exited) {
      process.destroyForcibly();
    }

    String output = new String(process.getInputStream().readAllBytes());
    assertTrue(exited, "Desktop smoke process did not exit. Output: " + output);
    assertEquals(0, process.exitValue(), "Desktop smoke process failed. Output: " + output);
  }

  private boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }
}
