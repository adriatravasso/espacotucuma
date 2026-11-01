package br.com.espacotucuma.desktop;

import br.com.espacotucuma.desktop.application.client.RegisterClientUseCase;
import br.com.espacotucuma.desktop.application.config.DesktopEnvironment;
import br.com.espacotucuma.desktop.application.config.DesktopPreferences;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteClientRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteOutboxRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteUnitOfWork;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DesktopRuntime implements AutoCloseable {

  private static final Logger LOGGER = Logger.getLogger("br.com.espacotucuma.desktop");

  private final LocalDatabaseSession database;
  private final FileHandler logHandler;
  private final RegisterClientUseCase registerClient;

  private DesktopRuntime(
      LocalDatabaseSession database, FileHandler logHandler, RegisterClientUseCase registerClient) {
    this.database = database;
    this.logHandler = logHandler;
    this.registerClient = registerClient;
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
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      UUID stationId = stationId(database.databaseFile());
      RegisterClientUseCase registerClient =
          new RegisterClientUseCase(
              unitOfWork,
              new SqliteClientRepository(database.connection(), unitOfWork),
              new SqliteOutboxRepository(database.connection(), unitOfWork),
              stationId);
      LOGGER.info(() -> "Aplicação iniciada com perfil " + environment.profile().directoryName());
      return new DesktopRuntime(database, logHandler, registerClient);
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

  public RegisterClientUseCase registerClient() {
    return registerClient;
  }

  private static UUID stationId(java.nio.file.Path databaseFile) {
    String stablePath =
        databaseFile.toAbsolutePath().normalize().toString().toLowerCase(java.util.Locale.ROOT);
    return UUID.nameUUIDFromBytes(stablePath.getBytes(StandardCharsets.UTF_8));
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
