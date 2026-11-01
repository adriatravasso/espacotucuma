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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class AbruptShutdownRecoveryTest {
  private static final String PASSWORD = "SenhaOfflineForte2026!";

  @Test
  @Timeout(90)
  void committedBusinessDataSurvivesCrashAndUncommittedTransactionRollsBack(@TempDir Path temp)
      throws Exception {
    Path dataRoot = temp.resolve("crash-recovery");
    String javaExecutable =
        Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
            .toString();
    String testClasspath = System.getProperty("surefire.test.class.path");
    Path outputFile = temp.resolve("abrupt-shutdown.log");
    assertTrue(testClasspath != null && !testClasspath.isBlank());

    Process process =
        new ProcessBuilder(
                javaExecutable,
                "-cp",
                testClasspath,
                AbruptShutdownRecoveryTest.class.getName(),
                "--crash-child",
                dataRoot.toString())
            .redirectErrorStream(true)
            .redirectOutput(outputFile.toFile())
            .start();
    boolean exited = process.waitFor(Duration.ofSeconds(60).toMillis(), TimeUnit.MILLISECONDS);
    if (!exited) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
    String output = new String(Files.readAllBytes(outputFile), Charset.defaultCharset());
    assertTrue(exited, "Crash probe did not exit. Output: " + output);
    assertEquals(
        0, process.exitValue(), "Crash probe failed before the abrupt exit. Output: " + output);

    DesktopEnvironment environment = new DesktopEnvironment(EnvironmentProfile.TEST, dataRoot);
    try (DesktopRuntime reopened = DesktopRuntime.open(environment)) {
      assertTrue(reopened.login().login("admin", PASSWORD.toCharArray()).isPresent());
      assertEquals(
          1,
          reopened
              .clients()
              .searchPage(
                  new br.com.espacotucuma.desktop.application.client.ClientPageQuery(
                      "Cliente confirmado antes do crash",
                      br.com.espacotucuma.desktop.application.client.ClientContactFilter.ALL,
                      br.com.espacotucuma.desktop.application.client.ClientSortField.NAME,
                      true,
                      0,
                      10))
              .totalItems());
      assertEquals(
          1,
          scalar(
              reopened.databaseFile(),
              "SELECT COUNT(*) FROM operacao_pendente WHERE entidade='cliente'"));
      assertEquals(
          1,
          scalar(
              reopened.databaseFile(),
              "SELECT COUNT(*) FROM evento_auditoria WHERE acao='CLIENTE_CRIADO'"));
      assertEquals(
          0,
          scalar(
              reopened.databaseFile(),
              "SELECT COUNT(*) FROM cliente WHERE nome='Cliente não confirmado'"));
      assertEquals("ok", scalarText(reopened.databaseFile(), "PRAGMA quick_check"));
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2 || !"--crash-child".equals(args[0])) {
      throw new IllegalArgumentException("Modo de execução inválido.");
    }
    Path dataRoot = Path.of(args[1]);
    DesktopEnvironment environment = new DesktopEnvironment(EnvironmentProfile.TEST, dataRoot);
    DesktopRuntime runtime = DesktopRuntime.open(environment);
    runtime
        .createFirstAdministrator()
        .create(
            new InitialAdministratorSetup(
                "Espaço Tucumã", "Admin de teste", "admin", PASSWORD.toCharArray()));
    if (runtime.login().login("admin", PASSWORD.toCharArray()).isEmpty()) {
      throw new IllegalStateException("A autenticação local de teste falhou.");
    }
    runtime.registerClient().register("Cliente confirmado antes do crash", "92900000001");

    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + runtime.databaseFile());
        var insert =
            connection.prepareStatement(
                "INSERT INTO cliente (id,codigo_interno,nome,ativo,criado_em_utc,atualizado_em_utc) "
                    + "VALUES (?,'CLI-999999','Cliente não confirmado',1,?,?)")) {
      connection.setAutoCommit(false);
      insert.setString(1, UUID.randomUUID().toString());
      String now =
          java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
      insert.setString(2, now);
      insert.setString(3, now);
      insert.executeUpdate();
      Runtime.getRuntime().halt(0);
    }
  }

  private static long scalar(Path databaseFile, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      return result.next() ? result.getLong(1) : -1;
    }
  }

  private static String scalarText(Path databaseFile, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      return result.next() ? result.getString(1) : "";
    }
  }

  private boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }
}
