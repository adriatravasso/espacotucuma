package br.com.espacotucuma.desktop;

import br.com.espacotucuma.desktop.application.attendance.MaintainAttendanceUseCase;
import br.com.espacotucuma.desktop.application.client.MaintainClientUseCase;
import br.com.espacotucuma.desktop.application.client.RegisterClientUseCase;
import br.com.espacotucuma.desktop.application.commission.CloseCommissionCompetencyUseCase;
import br.com.espacotucuma.desktop.application.commission.CommissionService;
import br.com.espacotucuma.desktop.application.commission.MaintainCommissionRuleUseCase;
import br.com.espacotucuma.desktop.application.config.DesktopEnvironment;
import br.com.espacotucuma.desktop.application.config.DesktopPreferences;
import br.com.espacotucuma.desktop.application.finance.ManageCashTurnUseCase;
import br.com.espacotucuma.desktop.application.finance.RefundPaymentUseCase;
import br.com.espacotucuma.desktop.application.finance.RegisterPaymentUseCase;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.ChangeLocalPasswordUseCase;
import br.com.espacotucuma.desktop.application.identity.ChangeUserRoleUseCase;
import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.DeactivateUserUseCase;
import br.com.espacotucuma.desktop.application.identity.LoginUseCase;
import br.com.espacotucuma.desktop.application.identity.LogoutUseCase;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.application.inventory.MaintainInventoryUseCase;
import br.com.espacotucuma.desktop.application.port.attendance.AttendanceRepository;
import br.com.espacotucuma.desktop.application.port.client.ClientRepository;
import br.com.espacotucuma.desktop.application.port.commission.CommissionRuleRepository;
import br.com.espacotucuma.desktop.application.port.commission.CommissionStatementRepository;
import br.com.espacotucuma.desktop.application.port.finance.CashTurnRepository;
import br.com.espacotucuma.desktop.application.port.finance.PaymentRepository;
import br.com.espacotucuma.desktop.application.port.inventory.InventoryRepository;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.prepaid.PrepaidPackageRepository;
import br.com.espacotucuma.desktop.application.port.professional.ProfessionalRepository;
import br.com.espacotucuma.desktop.application.port.scheduling.AppointmentRepository;
import br.com.espacotucuma.desktop.application.port.service.ServiceRepository;
import br.com.espacotucuma.desktop.application.port.synchronization.DeviceCredentialStore;
import br.com.espacotucuma.desktop.application.port.synchronization.ManualSynchronizationPort;
import br.com.espacotucuma.desktop.application.port.synchronization.RemoteChangeRepository;
import br.com.espacotucuma.desktop.application.prepaid.MaintainPrepaidPackagesUseCase;
import br.com.espacotucuma.desktop.application.professional.MaintainProfessionalUseCase;
import br.com.espacotucuma.desktop.application.reports.QueryCommercialReportsUseCase;
import br.com.espacotucuma.desktop.application.scheduling.MaintainAppointmentUseCase;
import br.com.espacotucuma.desktop.application.service.MaintainServiceUseCase;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import br.com.espacotucuma.desktop.infrastructure.security.WindowsCredentialStore;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteAppointmentRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteAttendanceRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteAuditRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteCashTurnRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteClientRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteCommercialReportRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteCommissionRuleRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteCommissionStatementRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteInitialSetupRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteInventoryRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteLocalCredentialRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteOpenCashTurnRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteOutboxRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqlitePaymentRefundRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqlitePaymentRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqlitePrepaidPackageRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteProfessionalRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteProtectionStatusRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteRemoteChangeRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteServiceRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteUnitOfWork;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteUserAdministrationRepository;
import br.com.espacotucuma.desktop.infrastructure.synchronization.HttpManualSynchronizationClient;
import java.io.IOException;
import java.net.URI;
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
  private final CommissionRuleRepository commissionRules;
  private final MaintainCommissionRuleUseCase maintainCommissionRule;
  private final CommissionService commissions;
  private final CloseCommissionCompetencyUseCase closeCommissionCompetency;
  private final PrepaidPackageRepository prepaidPackages;
  private final MaintainPrepaidPackagesUseCase maintainPrepaidPackages;
  private final AppointmentRepository appointments;
  private final MaintainAppointmentUseCase maintainAppointment;
  private final MaintainAttendanceUseCase maintainAttendance;
  private final RegisterPaymentUseCase registerPayment;
  private final RefundPaymentUseCase refundPayment;
  private final ManageCashTurnUseCase manageCashTurn;
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
  private final InventoryRepository inventory;
  private final MaintainInventoryUseCase maintainInventory;
  private final QueryCommercialReportsUseCase commercialReports;
  private final ManualSynchronizationPort manualSynchronization;

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
      CommissionRuleRepository commissionRules,
      MaintainCommissionRuleUseCase maintainCommissionRule,
      CommissionService commissions,
      CloseCommissionCompetencyUseCase closeCommissionCompetency,
      PrepaidPackageRepository prepaidPackages,
      MaintainPrepaidPackagesUseCase maintainPrepaidPackages,
      AppointmentRepository appointments,
      MaintainAppointmentUseCase maintainAppointment,
      MaintainAttendanceUseCase maintainAttendance,
      RegisterPaymentUseCase registerPayment,
      RefundPaymentUseCase refundPayment,
      ManageCashTurnUseCase manageCashTurn,
      AuthorizationService authorization,
      CreateFirstAdministratorUseCase createFirstAdministrator,
      ChangeLocalPasswordUseCase changeLocalPassword,
      SessionContext sessions,
      LoginUseCase login,
      LogoutUseCase logout,
      DeactivateUserUseCase deactivateUser,
      ChangeUserRoleUseCase changeUserRole,
      SqliteProtectionStatusRepository protectionStatus,
      InventoryRepository inventory,
      MaintainInventoryUseCase maintainInventory,
      QueryCommercialReportsUseCase commercialReports,
      ManualSynchronizationPort manualSynchronization,
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
    this.commissionRules = commissionRules;
    this.maintainCommissionRule = maintainCommissionRule;
    this.commissions = commissions;
    this.closeCommissionCompetency = closeCommissionCompetency;
    this.prepaidPackages = prepaidPackages;
    this.maintainPrepaidPackages = maintainPrepaidPackages;
    this.appointments = appointments;
    this.maintainAppointment = maintainAppointment;
    this.maintainAttendance = maintainAttendance;
    this.registerPayment = registerPayment;
    this.refundPayment = refundPayment;
    this.manageCashTurn = manageCashTurn;
    this.authorization = authorization;
    this.createFirstAdministrator = createFirstAdministrator;
    this.changeLocalPassword = changeLocalPassword;
    this.sessions = sessions;
    this.login = login;
    this.logout = logout;
    this.deactivateUser = deactivateUser;
    this.changeUserRole = changeUserRole;
    this.protectionStatus = protectionStatus;
    this.inventory = inventory;
    this.maintainInventory = maintainInventory;
    this.commercialReports = commercialReports;
    this.manualSynchronization = manualSynchronization;
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
      InventoryRepository inventory =
          new SqliteInventoryRepository(database.connection(), unitOfWork);
      MaintainInventoryUseCase maintainInventory =
          new MaintainInventoryUseCase(
              unitOfWork,
              inventory,
              new SqliteOutboxRepository(database.connection(), unitOfWork),
              authorization,
              audit,
              stationId);
      QueryCommercialReportsUseCase commercialReports =
          new QueryCommercialReportsUseCase(
              new SqliteCommercialReportRepository(database.connection(), unitOfWork),
              authorization);
      CommissionRuleRepository commissionRules =
          new SqliteCommissionRuleRepository(database.connection(), unitOfWork);
      MaintainCommissionRuleUseCase maintainCommissionRule =
          new MaintainCommissionRuleUseCase(
              unitOfWork,
              commissionRules,
              new SqliteOutboxRepository(database.connection(), unitOfWork),
              authorization,
              audit,
              stationId);
      CommissionService commissions =
          new CommissionService(commissionRules, unitOfWork, authorization);
      CommissionStatementRepository commissionStatements =
          new SqliteCommissionStatementRepository(database.connection(), unitOfWork);
      CloseCommissionCompetencyUseCase closeCommissionCompetency =
          new CloseCommissionCompetencyUseCase(
              unitOfWork,
              commissionStatements,
              new SqliteOutboxRepository(database.connection(), unitOfWork),
              authorization,
              audit,
              stationId);
      PrepaidPackageRepository prepaidPackages =
          new SqlitePrepaidPackageRepository(database.connection(), unitOfWork);
      MaintainPrepaidPackagesUseCase maintainPrepaidPackages =
          new MaintainPrepaidPackagesUseCase(
              unitOfWork,
              prepaidPackages,
              clients,
              new SqliteOutboxRepository(database.connection(), unitOfWork),
              authorization,
              audit,
              stationId);
      AppointmentRepository appointments =
          new SqliteAppointmentRepository(database.connection(), unitOfWork);
      MaintainAppointmentUseCase maintainAppointment =
          new MaintainAppointmentUseCase(
              unitOfWork,
              appointments,
              clients,
              professionals,
              services,
              new SqliteOutboxRepository(database.connection(), unitOfWork),
              authorization,
              audit,
              stationId);
      AttendanceRepository attendances =
          new SqliteAttendanceRepository(database.connection(), unitOfWork);
      MaintainAttendanceUseCase maintainAttendance =
          new MaintainAttendanceUseCase(
              unitOfWork,
              attendances,
              appointments,
              services,
              commissionRules,
              new SqliteOutboxRepository(database.connection(), unitOfWork),
              authorization,
              audit,
              stationId,
              maintainInventory);
      PaymentRepository payments = new SqlitePaymentRepository(database.connection(), unitOfWork);
      RegisterPaymentUseCase registerPayment =
          new RegisterPaymentUseCase(
              unitOfWork,
              attendances,
              payments,
              new SqliteOpenCashTurnRepository(database.connection(), unitOfWork),
              new SqliteOutboxRepository(database.connection(), unitOfWork),
              authorization,
              audit,
              stationId);
      CashTurnRepository cashTurns =
          new SqliteCashTurnRepository(database.connection(), unitOfWork);
      RefundPaymentUseCase refundPayment =
          new RefundPaymentUseCase(
              unitOfWork,
              payments,
              new SqlitePaymentRefundRepository(database.connection(), unitOfWork),
              cashTurns,
              new SqliteOutboxRepository(database.connection(), unitOfWork),
              authorization,
              audit,
              stationId);
      ManageCashTurnUseCase manageCashTurn =
          new ManageCashTurnUseCase(
              unitOfWork,
              cashTurns,
              new SqliteOutboxRepository(database.connection(), unitOfWork),
              audit,
              authorization,
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
      OutboxRepository outbox = new SqliteOutboxRepository(database.connection(), unitOfWork);
      ManualSynchronizationPort manualSynchronization =
          createManualSynchronization(outbox, unitOfWork, database.connection());
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
          commissionRules,
          maintainCommissionRule,
          commissions,
          closeCommissionCompetency,
          prepaidPackages,
          maintainPrepaidPackages,
          appointments,
          maintainAppointment,
          maintainAttendance,
          registerPayment,
          refundPayment,
          manageCashTurn,
          authorization,
          createFirstAdministrator,
          changeLocalPassword,
          sessions,
          login,
          new LogoutUseCase(sessions),
          deactivateUser,
          changeUserRole,
          protectionStatus,
          inventory,
          maintainInventory,
          commercialReports,
          manualSynchronization,
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

  public CommissionRuleRepository commissionRules() {
    return commissionRules;
  }

  public MaintainCommissionRuleUseCase maintainCommissionRule() {
    return maintainCommissionRule;
  }

  public CommissionService commissions() {
    return commissions;
  }

  public CloseCommissionCompetencyUseCase closeCommissionCompetency() {
    return closeCommissionCompetency;
  }

  public PrepaidPackageRepository prepaidPackages() {
    return prepaidPackages;
  }

  public MaintainPrepaidPackagesUseCase maintainPrepaidPackages() {
    return maintainPrepaidPackages;
  }

  public AppointmentRepository appointments() {
    return appointments;
  }

  public MaintainAppointmentUseCase maintainAppointment() {
    return maintainAppointment;
  }

  public MaintainAttendanceUseCase maintainAttendance() {
    return maintainAttendance;
  }

  public RegisterPaymentUseCase registerPayment() {
    return registerPayment;
  }

  public RefundPaymentUseCase refundPayment() {
    return refundPayment;
  }

  public ManageCashTurnUseCase manageCashTurn() {
    return manageCashTurn;
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

  public InventoryRepository inventory() {
    return inventory;
  }

  public MaintainInventoryUseCase maintainInventory() {
    return maintainInventory;
  }

  public QueryCommercialReportsUseCase commercialReports() {
    return commercialReports;
  }

  public ManualSynchronizationPort manualSynchronization() {
    return manualSynchronization;
  }

  private static ManualSynchronizationPort createManualSynchronization(
      OutboxRepository outbox, SqliteUnitOfWork unitOfWork, java.sql.Connection connection) {
    String baseUrl = System.getenv("TUCUMA_SYNC_BASE_URL");
    String deviceId = System.getenv("TUCUMA_SYNC_DEVICE_ID");
    if (baseUrl == null || baseUrl.isBlank() || deviceId == null || deviceId.isBlank()) {
      return () -> {
        throw new IllegalStateException(
            "Configure TUCUMA_SYNC_BASE_URL e TUCUMA_SYNC_DEVICE_ID após parear esta estação.");
      };
    }
    UUID configuredDeviceId = UUID.fromString(deviceId.trim());
    RemoteChangeRepository remoteChanges =
        new SqliteRemoteChangeRepository(
            connection,
            unitOfWork,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            configuredDeviceId);
    return new HttpManualSynchronizationClient(
        URI.create(baseUrl.trim()),
        configuredDeviceId,
        (DeviceCredentialStore) new WindowsCredentialStore(),
        outbox,
        remoteChanges,
        unitOfWork);
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
