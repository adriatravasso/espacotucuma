package br.com.espacotucuma.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.DesktopRuntime;
import br.com.espacotucuma.desktop.application.config.DesktopEnvironment;
import br.com.espacotucuma.desktop.application.config.EnvironmentProfile;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.application.inventory.UnitOfMeasure;
import br.com.espacotucuma.desktop.application.professional.ProfessionalSchedule;
import br.com.espacotucuma.desktop.application.professional.WeeklyInterval;
import br.com.espacotucuma.desktop.application.reports.CommercialReportPeriod;
import br.com.espacotucuma.desktop.application.reports.CommercialReportType;
import br.com.espacotucuma.desktop.application.service.BeautyService;
import br.com.espacotucuma.desktop.domain.money.Money;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.DatePicker;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Real SQLite business journey with HTTP(S) outbound access rejected for the test duration. */
@ExtendWith(ApplicationExtension.class)
class OfflineBusinessJourneyTest {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Manaus");
  private static final char[] ADMIN_PASSWORD = "SenhaOfflineForte2026!".toCharArray();
  private Stage stage;
  private ProxySelector previousProxySelector;
  private DesktopRuntime openRuntime;
  private TucumaMainShell shell;

  @Start
  void start(Stage stage) {
    this.stage = stage;
    stage.setScene(new Scene(new StackPane(), 1180, 720));
    stage.show();
  }

  @Test
  @Timeout(120)
  void completesLocalBusinessJourneyAndReopensWithDurableAuditAndOutbox(
      FxRobot robot, @TempDir Path tempDirectory) throws Exception {
    blockHttpAndHttpsRequests();
    DesktopEnvironment environment =
        new DesktopEnvironment(EnvironmentProfile.TEST, tempDirectory.resolve("offline-business"));
    openRuntime = DesktopRuntime.open(environment);
    openRuntime
        .createFirstAdministrator()
        .create(
            new InitialAdministratorSetup(
                "Espaço Tucumã", "Administradora offline", "admin", ADMIN_PASSWORD));
    assertTrue(openRuntime.login().login("admin", ADMIN_PASSWORD.clone()).isPresent());

    ProfessionalSchedule schedule =
        new ProfessionalSchedule(
            java.util.Arrays.stream(DayOfWeek.values())
                .map(day -> new WeeklyInterval(day, LocalTime.of(0, 0), LocalTime.of(23, 59)))
                .toList(),
            List.of());
    var professional =
        openRuntime
            .maintainProfessional()
            .register("Profissional Offline", "92999990000", schedule);
    BeautyService service =
        openRuntime
            .maintainService()
            .register("Hidratação Offline", "Cabelo", 30, Money.of("85.00"), 1500);
    openRuntime.maintainCommissionRule().setRule(professional.id(), service.id(), 1500);
    var supplier = openRuntime.maintainInventory().registerSupplier("Fornecedor Local", null, null);
    var product =
        openRuntime
            .maintainInventory()
            .registerProduct(
                supplier.id(),
                "OLEO-001",
                "Óleo de teste",
                "Cabelo",
                UnitOfMeasure.MILILITRO,
                Money.of("0.25"),
                Money.of("1.00"),
                1000);
    openRuntime
        .maintainInventory()
        .registerEntry(product.id(), 10000, "Estoque inicial sintético", "offline-journey-entry");
    openRuntime.maintainInventory().configureServiceMaterial(service.id(), product.id(), 500);
    var turn = openRuntime.manageCashTurn().open(0);

    ClientRegistryPane clientsPane =
        new ClientRegistryPane(
            openRuntime.clients(),
            openRuntime.registerClient(),
            openRuntime.maintainClient(),
            openRuntime.authorization());
    AppointmentRegistryPane appointmentPane =
        new AppointmentRegistryPane(
            openRuntime.maintainAppointment(),
            openRuntime.clients(),
            openRuntime.professionals(),
            openRuntime.services(),
            openRuntime.authorization(),
            openRuntime.maintainAttendance(),
            openRuntime.prepaidPackages(),
            openRuntime.registerPayment(),
            openRuntime.refundPayment());
    CashRegisterPane cashPane =
        new CashRegisterPane(openRuntime.manageCashTurn(), openRuntime.authorization());
    CommercialReportsPane commercialReportsPane =
        new CommercialReportsPane(openRuntime.commercialReports(), openRuntime.authorization());
    ReportsModulePane reportsPane =
        new ReportsModulePane(
            commercialReportsPane,
            new CommissionStatementPane(
                openRuntime.closeCommissionCompetency(), openRuntime.authorization()));
    InventoryPane inventoryPane =
        new InventoryPane(
            openRuntime.inventory(),
            openRuntime.maintainInventory(),
            openRuntime.authorization(),
            openRuntime.services());
    shell =
        new TucumaMainShell(
            openRuntime::protectionStatus,
            clientsPane,
            new ProfessionalRegistryPane(
                openRuntime.professionals(),
                openRuntime.maintainProfessional(),
                openRuntime.authorization()),
            new ServiceCatalogPane(
                openRuntime.services(),
                openRuntime.maintainService(),
                openRuntime.authorization(),
                openRuntime.commissions(),
                openRuntime.maintainCommissionRule(),
                openRuntime.commissionRules(),
                openRuntime.professionals()),
            new PackageRegistryPane(
                openRuntime.prepaidPackages(),
                openRuntime.clients(),
                openRuntime.maintainPrepaidPackages(),
                openRuntime.authorization()),
            appointmentPane,
            cashPane,
            reportsPane,
            inventoryPane);
    appointmentPane.setMutationListener(shell::refreshStatus);
    robot.interact(() -> stage.setScene(new Scene(shell.root(), 1180, 720)));

    robot.clickOn("#module-clients");
    robot.clickOn("#tableNew");
    waitForNode(robot, "#form-name");
    robot.interact(
        () -> robot.lookup("#form-name").queryTextInputControl().setText("Cliente Offline"));
    robot.interact(() -> robot.lookup("Salvar").queryButton().fire());
    WaitForAsyncUtils.waitFor(
        10,
        TimeUnit.SECONDS,
        () ->
            openRuntime
                    .clients()
                    .searchPage(
                        new br.com.espacotucuma.desktop.application.client.ClientPageQuery(
                            "Cliente Offline",
                            br.com.espacotucuma.desktop.application.client.ClientContactFilter.ALL,
                            br.com.espacotucuma.desktop.application.client.ClientSortField.NAME,
                            true,
                            0,
                            10))
                    .items()
                    .size()
                == 1);
    var client =
        openRuntime
            .clients()
            .searchPage(
                new br.com.espacotucuma.desktop.application.client.ClientPageQuery(
                    "Cliente Offline",
                    br.com.espacotucuma.desktop.application.client.ClientContactFilter.ALL,
                    br.com.espacotucuma.desktop.application.client.ClientSortField.NAME,
                    true,
                    0,
                    10))
            .items()
            .getFirst();

    LocalDate appointmentDay = LocalDate.now(BUSINESS_ZONE).plusDays(1);
    robot.clickOn("#module-agenda");
    robot.interact(() -> appointmentPane.datePicker().setValue(appointmentDay));
    robot.clickOn("#appointmentNew");
    waitForNode(robot, "#appointmentFormTime");
    robot.interact(
        () -> {
          robot.lookup("#appointmentClient").queryComboBox().getSelectionModel().select(client);
          robot
              .lookup("#appointmentProfessional")
              .queryComboBox()
              .getSelectionModel()
              .select(professional);
          robot.lookup("#appointmentService").queryComboBox().getSelectionModel().select(service);
          robot.lookup("#appointmentFormDate").queryAs(DatePicker.class).setValue(appointmentDay);
          robot.lookup("#appointmentFormTime").queryTextInputControl().setText("12:00");
        });
    robot.interact(() -> robot.lookup("OK").queryButton().fire());
    WaitForAsyncUtils.waitFor(
        10, TimeUnit.SECONDS, () -> appointmentPane.appointmentTable().getItems().size() == 1);
    robot.interact(() -> appointmentPane.appointmentTable().getSelectionModel().selectFirst());
    robot.clickOn("#appointmentConfirm");
    WaitForAsyncUtils.waitFor(
        10,
        TimeUnit.SECONDS,
        () ->
            appointmentPane
                .appointmentTable()
                .getItems()
                .getFirst()
                .appointment()
                .status()
                .name()
                .equals("CONFIRMADO"));
    robot.interact(() -> appointmentPane.appointmentTable().getSelectionModel().selectFirst());
    WaitForAsyncUtils.waitFor(
        10, TimeUnit.SECONDS, () -> !robot.lookup("#appointmentStart").queryButton().isDisabled());
    robot.clickOn("#appointmentStart");
    waitForNode(robot, "#attendanceAddService");
    robot.interact(
        () ->
            robot
                .lookup("#attendanceServiceChoice")
                .queryComboBox()
                .getSelectionModel()
                .select(service));
    robot.clickOn("#attendanceAddService");
    WaitForAsyncUtils.waitFor(
        10,
        TimeUnit.SECONDS,
        () -> robot.lookup("#attendanceServices").queryTableView().getItems().size() == 1);
    robot.clickOn("#attendanceComplete");
    WaitForAsyncUtils.waitFor(
        10,
        TimeUnit.SECONDS,
        () ->
            appointmentPane
                .appointmentTable()
                .getItems()
                .getFirst()
                .appointment()
                .status()
                .name()
                .equals("CONCLUIDO"));

    var completedAttendance =
        openRuntime
            .registerPayment()
            .findAttendanceByAppointment(
                appointmentPane.appointmentTable().getItems().getFirst().appointment().id());
    openRuntime
        .registerPayment()
        .register(
            completedAttendance.id(),
            br.com.espacotucuma.desktop.application.finance.PaymentMethod.PIX,
            completedAttendance.total(),
            "offline-journey-payment");
    YearMonth completedMonth =
        YearMonth.from(completedAttendance.completedAt().atZone(BUSINESS_ZONE));
    assertFalse(openRuntime.closeCommissionCompetency().close(completedMonth).isEmpty());
    Map<br.com.espacotucuma.desktop.application.finance.PaymentMethod, Long> expectedCash =
        openRuntime.manageCashTurn().expectedTotals(turn.id());
    openRuntime.manageCashTurn().close(expectedCash, "Conferência do cenário offline");
    assertEquals(9500, openRuntime.inventory().stockOnHand(product.id()));

    robot.clickOn("#module-reports");
    robot.interact(
        () ->
            commercialReportsPane
                .reportTypeSelector()
                .setValue(CommercialReportType.ATTENDANCES_BY_PROFESSIONAL));
    WaitForAsyncUtils.waitFor(
        10, TimeUnit.SECONDS, () -> !commercialReportsPane.reportTable().getItems().isEmpty());
    var report =
        openRuntime
            .commercialReports()
            .find(
                CommercialReportType.ATTENDANCES_BY_PROFESSIONAL,
                new CommercialReportPeriod(
                    LocalDate.now(BUSINESS_ZONE), LocalDate.now(BUSINESS_ZONE)),
                0,
                20);
    assertFalse(report.rows().isEmpty());

    Map<String, Long> beforeRestart = databaseCounts(environment.databaseFile());
    assertTrue(beforeRestart.get("operacao_pendente") >= 8);
    assertTrue(beforeRestart.get("evento_auditoria") >= 8);
    assertEquals(1, beforeRestart.get("recebimento"));
    assertEquals(8500, beforeRestart.get("recebimento_centavos"));
    assertEquals(1, beforeRestart.get("comissao"));
    assertEquals(1275, beforeRestart.get("comissao_centavos"));
    assertEquals(500, beforeRestart.get("movimento_estoque_consumo"));

    robot.interact(shell::stopStatusRefresh);
    robot.interact(() -> stage.setScene(new Scene(new StackPane(), 1180, 720)));
    openRuntime.close();
    openRuntime = DesktopRuntime.open(environment);
    assertTrue(openRuntime.login().login("admin", ADMIN_PASSWORD.clone()).isPresent());
    assertTrue(openRuntime.manageCashTurn().currentTurn().isPresent());
    assertFalse(openRuntime.manageCashTurn().currentTurn().orElseThrow().open());
    assertEquals(9500, openRuntime.inventory().stockOnHand(product.id()));
    assertTrue(openRuntime.closeCommissionCompetency().isClosed(completedMonth));
    assertEquals(
        1,
        openRuntime
            .clients()
            .searchPage(
                new br.com.espacotucuma.desktop.application.client.ClientPageQuery(
                    "Cliente Offline",
                    br.com.espacotucuma.desktop.application.client.ClientContactFilter.ALL,
                    br.com.espacotucuma.desktop.application.client.ClientSortField.NAME,
                    true,
                    0,
                    10))
            .items()
            .size());
    assertFalse(
        openRuntime
            .commercialReports()
            .find(
                CommercialReportType.ATTENDANCES_BY_PROFESSIONAL,
                new CommercialReportPeriod(
                    LocalDate.now(BUSINESS_ZONE), LocalDate.now(BUSINESS_ZONE)),
                0,
                20)
            .rows()
            .isEmpty());
    assertEquals(beforeRestart, databaseCounts(environment.databaseFile()));
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + environment.databaseFile());
        var statement = connection.createStatement();
        var result = statement.executeQuery("PRAGMA quick_check")) {
      assertTrue(result.next());
      assertEquals("ok", result.getString(1));
    }
    robot.interact(shell::stopStatusRefresh);
    openRuntime.close();
    openRuntime = null;
  }

  @AfterEach
  void cleanup() throws Exception {
    if (shell != null) Platform.runLater(shell::stopStatusRefresh);
    if (openRuntime != null) openRuntime.close();
    ProxySelector.setDefault(previousProxySelector);
  }

  private void blockHttpAndHttpsRequests() {
    previousProxySelector = ProxySelector.getDefault();
    ProxySelector.setDefault(
        new ProxySelector() {
          @Override
          public List<Proxy> select(URI uri) {
            if (uri.getScheme() != null
                && (uri.getScheme().equalsIgnoreCase("http")
                    || uri.getScheme().equalsIgnoreCase("https"))) {
              throw new IllegalStateException("Rede externa bloqueada no cenário offline: " + uri);
            }
            return List.of(Proxy.NO_PROXY);
          }

          @Override
          public void connectFailed(URI uri, SocketAddress address, java.io.IOException failure) {
            throw new IllegalStateException(
                "A tentativa de rede externa falhou no cenário offline.", failure);
          }
        });
  }

  private static Map<String, Long> databaseCounts(Path databaseFile) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
      Map<String, Long> result = new java.util.LinkedHashMap<>();
      for (String table :
          List.of(
              "cliente",
              "agendamento",
              "atendimento",
              "recebimento",
              "comissao",
              "caixa_turno",
              "conferencia_caixa",
              "movimento_estoque",
              "evento_auditoria")) {
        try (var statement = connection.createStatement();
            var count = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
          count.next();
          result.put(table, count.getLong(1));
        }
      }
      try (var statement = connection.createStatement();
          var count =
              statement.executeQuery(
                  "SELECT COUNT(*) FROM operacao_pendente WHERE estado='PENDENTE'")) {
        count.next();
        result.put("operacao_pendente", count.getLong(1));
      }
      try (var statement = connection.createStatement();
          var count =
              statement.executeQuery(
                  "SELECT COALESCE(SUM(quantidade_milesimos),0) FROM movimento_estoque WHERE tipo='CONSUMO_SERVICO'")) {
        count.next();
        result.put("movimento_estoque_consumo", count.getLong(1));
      }
      try (var statement = connection.createStatement();
          var count =
              statement.executeQuery("SELECT COALESCE(SUM(valor_centavos),0) FROM recebimento")) {
        count.next();
        result.put("recebimento_centavos", count.getLong(1));
      }
      try (var statement = connection.createStatement();
          var count =
              statement.executeQuery("SELECT COALESCE(SUM(valor_centavos),0) FROM comissao")) {
        count.next();
        result.put("comissao_centavos", count.getLong(1));
      }
      return Map.copyOf(result);
    }
  }

  private static void waitForNode(FxRobot robot, String selector) throws Exception {
    WaitForAsyncUtils.waitFor(
        10, TimeUnit.SECONDS, () -> !robot.lookup(selector).queryAll().isEmpty());
  }
}
