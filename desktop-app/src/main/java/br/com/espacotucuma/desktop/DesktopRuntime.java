package br.com.espacotucuma.desktop;

import br.com.espacotucuma.desktop.application.client.MaintainClientUseCase;
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
import br.com.espacotucuma.desktop.application.port.client.ClientRepository;
import br.com.espacotucuma.desktop.application.port.professional.ProfessionalRepository;
import br.com.espacotucuma.desktop.application.port.service.ServiceRepository;
import br.com.espacotucuma.desktop.application.professional.MaintainProfessionalUseCase;
import br.com.espacotucuma.desktop.application.service.MaintainServiceUseCase;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteAuditRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteClientRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteInitialSetupRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteLocalCredentialRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteOutboxRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteProfessionalRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteProtectionStatusRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteServiceRepository;
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
  private final MaintainClientUseCase maintainClient;
  private final ClientRepository clients;
  private final ProfessionalRepository professionals;
  private final MaintainProfessionalUseCase maintainProfessional;
  private final ServiceRepository services;
  private final MaintainServiceUseCase maintainService;
  private final AuthorizationService authorization;
  private final CreateFirstAdministratorUseCase createFirstAdministrator;
  private final ChangeLocalPasswordUseCase changeLocalPassword;
  private final SessionContext sessions;
  private final LoginUseCase login;
  private final LogoutUseCase logout;
  private final DeactivateUserUseCase deactivateUser;
  private final ChangeUserRoleUseCase changeUserRole;
  private final boolean testProfile;
  private final SqliteProtectionStatusRepository protectionStatus;

  private DesktopRuntime(
      LocalDatabaseSession database,
      FileHandler logHandler,
      RegisterClientUseCase registerClient,
      MaintainClientUseCase maintainClient,
      ClientRepository clients,
      ProfessionalRepository professionals,
      MaintainProfessionalUseCase maintainProfessional,
      ServiceRepository services,
      MaintainServiceUseCase maintainService,
      AuthorizationService authorization,
      CreateFirstAdministratorUseCase createFirstAdministrator,
      ChangeLocalPasswordUseCase changeLocalPassword,
      SessionContext sessions,
      LoginUseCase login,
      LogoutUseCase logout,
      DeactivateUserUseCase deactivateUser,
      ChangeUserRoleUseCase changeUserRole,
      SqliteProtectionStatusRepository protectionStatus,
      boolean testProfile) {
    this.database = database;
    this.logHandler = logHandler;
    this.registerClient = registerClient;
    this.maintainClient = maintainClient;
    this.clients = clients;
    this.professionals = professionals;
    this.maintainProfessional = maintainProfessional;
    this.services = services;
    this.maintainService = maintainService;
    this.authorization = authorization;
    this.createFirstAdministrator = createFirstAdministrator;
    this.changeLocalPassword = changeLocalPassword;
    this.sessions = sessions;
    this.login = login;
    this.logout = logout;
    this.deactivateUser = deactivateUser;
    this.changeUserRole = changeUserRole;
    this.protectionStatus = protectionStatus;
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
      SqliteAuditRepository audit = new SqliteAuditRepository(database.connection(), unitOfWork);
      UUID stationId = stationId(database.databaseFile());
      SqliteClientRepository clients =
          new SqliteClientRepository(database.connection(), unitOfWork);
      RegisterClientUseCase registerClient =
          new RegisterClientUseCase(
              unitOfWork,
              clients,
              new SqliteOutboxRepository(database.connection(), unitOfWork),
              stationId,
              authorization,
              audit);
      MaintainClientUseCase maintainClient =
          new MaintainClientUseCase(
              unitOfWork,
              clients,
              new SqliteOutboxRepository(database.connection(), unitOfWork),
              authorization,
              audit,
              stationId);
      SqliteProfessionalRepository professionals =
          new SqliteProfessionalRepository(database.connection(), unitOfWork);
      MaintainProfessionalUseCase maintainProfessional =
          new MaintainProfessionalUseCase(
              unitOfWork,
              professionals,
              new SqliteOutboxRepository(database.connection(), unitOfWork),
              authorization,
              audit,
              stationId);
      SqliteServiceRepository services =
          new SqliteServiceRepository(database.connection(), unitOfWork);
      MaintainServiceUseCase maintainService =
          new MaintainServiceUseCase(
              unitOfWork,
              services,
              new SqliteOutboxRepository(database.connection(), unitOfWork),
              authorization,
              audit,
              stationId);
      CreateFirstAdministratorUseCase createFirstAdministrator =
          new CreateFirstAdministratorUseCase(
              unitOfWork,
              new SqliteInitialSetupRepository(database.connection(), unitOfWork),
              new Pbkdf2PasswordHasher(),
              audit,
              stationId);
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
      SqliteProtectionStatusRepository protectionStatus =
          new SqliteProtectionStatusRepository(database.connection(), unitOfWork);
      DeactivateUserUseCase deactivateUser =
          new DeactivateUserUseCase(unitOfWork, userRepository, authorization, audit, stationId);
      ChangeUserRoleUseCase changeUserRole =
          new ChangeUserRoleUseCase(unitOfWork, userRepository, authorization, audit, stationId);
      LOGGER.info(() -> "Aplicação iniciada com perfil " + environment.profile().directoryName());
      return new DesktopRuntime(
          database,
          logHandler,
          registerClient,
          maintainClient,
          clients,
          professionals,
          maintainProfessional,
          services,
          maintainService,
          authorization,
          createFirstAdministrator,
          changeLocalPassword,
          sessions,
          login,
          new LogoutUseCase(sessions),
          deactivateUser,
          changeUserRole,
          protectionStatus,
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

  public MaintainClientUseCase maintainClient() {
    return maintainClient;
  }

  public ClientRepository clients() {
    return clients;
  }

  public ProfessionalRepository professionals() {
    return professionals;
  }

  public MaintainProfessionalUseCase maintainProfessional() {
    return maintainProfessional;
  }

  public ServiceRepository services() {
    return services;
  }

  public MaintainServiceUseCase maintainService() {
    return maintainService;
  }

  public AuthorizationService authorization() {
    return authorization;
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

  public br.com.espacotucuma.desktop.application.ProtectionStatus protectionStatus() {
    String userName = sessions.current().map(session -> session.user().name()).orElse("Sem sessão");
    return protectionStatus.load(userName, database.databaseFile());
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
