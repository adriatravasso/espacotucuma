package br.com.espacotucuma.desktop;

import br.com.espacotucuma.desktop.application.client.RegisterClientUseCase;
import br.com.espacotucuma.desktop.application.config.DesktopEnvironment;
import br.com.espacotucuma.desktop.application.config.DesktopPreferences;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.ChangeLocalPasswordUseCase;
import br.com.espacotucuma.desktop.application.identity.ChangeUserRoleUseCase;
import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.DeactivateUserUseCase;
import br.com.espacotucuma.desktop.application.identity.LoginUseCase;
import br.com.espacotucuma.desktop.application.identity.LogoutUseCase;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteClientRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteInitialSetupRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteLocalCredentialRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteOutboxRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteUnitOfWork;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteUserAdministrationRepository;
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
  private final CreateFirstAdministratorUseCase createFirstAdministrator;
  private final ChangeLocalPasswordUseCase changeLocalPassword;
  private final SessionContext sessions;
  private final LoginUseCase login;
  private final LogoutUseCase logout;
  private final DeactivateUserUseCase deactivateUser;
  private final ChangeUserRoleUseCase changeUserRole;
  private final boolean testProfile;

  private DesktopRuntime(
      LocalDatabaseSession database,
      FileHandler logHandler,
      RegisterClientUseCase registerClient,
      CreateFirstAdministratorUseCase createFirstAdministrator,
      ChangeLocalPasswordUseCase changeLocalPassword,
      SessionContext sessions,
      LoginUseCase login,
      LogoutUseCase logout,
      DeactivateUserUseCase deactivateUser,
      ChangeUserRoleUseCase changeUserRole,
      boolean testProfile) {
    this.database = database;
    this.logHandler = logHandler;
    this.registerClient = registerClient;
    this.createFirstAdministrator = createFirstAdministrator;
    this.changeLocalPassword = changeLocalPassword;
    this.sessions = sessions;
    this.login = login;
    this.logout = logout;
    this.deactivateUser = deactivateUser;
    this.changeUserRole = changeUserRole;
    this.testProfile = testProfile;
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
      SessionContext sessions = new SessionContext();
      AuthorizationService authorization = new AuthorizationService(sessions);
      SqliteLocalCredentialRepository credentials =
          new SqliteLocalCredentialRepository(database.connection(), unitOfWork);
      UUID stationId = stationId(database.databaseFile());
      RegisterClientUseCase registerClient =
          new RegisterClientUseCase(
              unitOfWork,
              new SqliteClientRepository(database.connection(), unitOfWork),
              new SqliteOutboxRepository(database.connection(), unitOfWork),
              stationId,
              authorization);
      CreateFirstAdministratorUseCase createFirstAdministrator =
          new CreateFirstAdministratorUseCase(
              unitOfWork,
              new SqliteInitialSetupRepository(database.connection(), unitOfWork),
              new Pbkdf2PasswordHasher());
      ChangeLocalPasswordUseCase changeLocalPassword =
          new ChangeLocalPasswordUseCase(unitOfWork, credentials, new Pbkdf2PasswordHasher());
      LoginUseCase login =
          new LoginUseCase(
              unitOfWork,
              credentials,
              new br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteLoginAttemptRepository(
                  database.connection(), unitOfWork),
              new Pbkdf2PasswordHasher(),
              sessions);
      SqliteUserAdministrationRepository userRepository =
          new SqliteUserAdministrationRepository(database.connection(), unitOfWork);
      DeactivateUserUseCase deactivateUser =
          new DeactivateUserUseCase(unitOfWork, userRepository, authorization, sessions);
      ChangeUserRoleUseCase changeUserRole =
          new ChangeUserRoleUseCase(unitOfWork, userRepository, authorization, sessions);
      LOGGER.info(() -> "Aplicação iniciada com perfil " + environment.profile().directoryName());
      return new DesktopRuntime(
          database,
          logHandler,
          registerClient,
          createFirstAdministrator,
          changeLocalPassword,
          sessions,
          login,
          new LogoutUseCase(sessions),
          deactivateUser,
          changeUserRole,
          environment.profile()
              == br.com.espacotucuma.desktop.application.config.EnvironmentProfile.TEST);
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

  public CreateFirstAdministratorUseCase createFirstAdministrator() {
    return createFirstAdministrator;
  }

  public ChangeLocalPasswordUseCase changeLocalPassword() {
    return changeLocalPassword;
  }

  public SessionContext sessions() {
    return sessions;
  }

  public LoginUseCase login() {
    return login;
  }

  public LogoutUseCase logout() {
    return logout;
  }

  public DeactivateUserUseCase deactivateUser() {
    return deactivateUser;
  }

  public ChangeUserRoleUseCase changeUserRole() {
    return changeUserRole;
  }

  public boolean isTestProfile() {
    return testProfile;
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
