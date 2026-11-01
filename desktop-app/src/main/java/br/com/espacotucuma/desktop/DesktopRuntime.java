package br.com.espacotucuma.desktop;

import br.com.espacotucuma.desktop.application.config.DesktopEnvironment;
import br.com.espacotucuma.desktop.application.config.DesktopPreferences;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DesktopRuntime implements AutoCloseable {

  private static final Logger LOGGER = Logger.getLogger("br.com.espacotucuma.desktop");

  private final LocalDatabaseSession database;
  private final FileHandler logHandler;

  private DesktopRuntime(LocalDatabaseSession database, FileHandler logHandler) {
    this.database = database;
    this.logHandler = logHandler;
  }

  public static DesktopRuntime open() throws IOException, java.sql.SQLException {
    DesktopEnvironment environment = DesktopEnvironment.current();
    return open(environment);
  }

  public static DesktopRuntime open(DesktopEnvironment environment)
      throws IOException, java.sql.SQLException {
    java.nio.file.Files.createDirectories(environment.dataDirectory());
    java.nio.file.Files.createDirectories(environment.logsDirectory());
    FileHandler logHandler =
        new FileHandler(
            environment.logsDirectory().resolve("tucuma-%g.log").toString(), 1_000_000, 3, true);
    logHandler.setLevel(Level.INFO);
    LOGGER.addHandler(logHandler);

    try {
      DesktopPreferences preferences = DesktopPreferences.loadOrCreate(environment);
      LocalDatabaseSession database = LocalDatabaseSession.open(preferences.databaseFile());
      LOGGER.info(() -> "Aplicação iniciada com perfil " + environment.profile().directoryName());
      return new DesktopRuntime(database, logHandler);
    } catch (IOException | java.sql.SQLException | RuntimeException error) {
      LOGGER.log(Level.SEVERE, "Falha ao preparar a aplicação desktop", error);
      LOGGER.removeHandler(logHandler);
      logHandler.close();
      throw error;
    }
  }

  public java.nio.file.Path databaseFile() {
    return database.databaseFile();
  }

  @Override
  public void close() throws Exception {
    try {
      database.close();
    } finally {
      LOGGER.info("Aplicação encerrada");
      LOGGER.removeHandler(logHandler);
      logHandler.close();
    }
  }
}
