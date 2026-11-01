package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.ProtectionStatus;
import br.com.espacotucuma.desktop.application.port.synchronization.ManualSynchronizationPort;
import br.com.espacotucuma.desktop.application.port.synchronization.RemoteBackupHistoryPort;
import br.com.espacotucuma.desktop.application.port.synchronization.SynchronizationProgress;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Main application shell; module views are filled in by their individual delivery tasks. */
final class TucumaMainShell {

  private static final String UNAVAILABLE =
      "Disponível quando a área selecionada oferecer essa operação.";

  private final BorderPane root = new BorderPane();
  private final BorderPane workArea = new BorderPane();
  private final Label pageTitle = new Label();
  private final Map<String, Button> moduleButtons = new LinkedHashMap<>();
  private final StatusBar statusBar = new StatusBar();
  private final Supplier<ProtectionStatus> statusLoader;
  private final Parent clientsView;
  private final Parent professionalsView;
  private final Parent servicesView;
  private final Parent packagesView;
  private final Parent appointmentsView;
  private final Parent cashView;
  private final Parent reportsView;
  private final Parent inventoryView;
  private final Parent conflictsView;
  private final Timeline statusRefresh;
  private final ManualSynchronizationPort manualSynchronization;
  private final RemoteBackupHistoryPort remoteBackupHistory;
  private final AtomicBoolean synchronizationRunning = new AtomicBoolean();
  private final AtomicReference<Thread> synchronizationWorker = new AtomicReference<>();
  private Button synchronizationButton;
  private MenuItem synchronizationMenuItem;
  private Label synchronizationProgress;
  private Dialog<Void> synchronizationDialog;
  private Label dialogProtection;
  private Label dialogLastSync;
  private Label dialogLastBackup;
  private Label dialogState;
  private Label dialogNetwork;
  private Label dialogDetail;
  private Label dialogRecovery;
  private ProgressBar dialogProgressBar;
  private Button dialogSyncButton;
  private Button dialogRetryButton;
  private Button dialogBackupsButton;
  private ListView<String> dialogBackups;
  private Runnable logoutAction = () -> {};
  private String activeModule = "Início";

  TucumaMainShell(String userName) {
    this(
        () ->
            new ProtectionStatus(
                userName, Path.of("dados/tucuma.sqlite"), 0, 0, null, 0, null, null),
        null,
        null,
        null);
  }

  TucumaMainShell(Supplier<ProtectionStatus> statusLoader) {
    this(statusLoader, null, null, null);
  }

  TucumaMainShell(Supplier<ProtectionStatus> statusLoader, Parent clientsView) {
    this(statusLoader, clientsView, null, null);
  }

  TucumaMainShell(
      Supplier<ProtectionStatus> statusLoader, Parent clientsView, Parent professionalsView) {
    this(statusLoader, clientsView, professionalsView, null);
  }

  TucumaMainShell(
      Supplier<ProtectionStatus> statusLoader,
      Parent clientsView,
      Parent professionalsView,
      Parent servicesView) {
    this(statusLoader, clientsView, professionalsView, servicesView, null);
  }

  TucumaMainShell(
      Supplier<ProtectionStatus> statusLoader,
      Parent clientsView,
      Parent professionalsView,
      Parent servicesView,
      Parent packagesView) {
    this(statusLoader, clientsView, professionalsView, servicesView, packagesView, null);
  }

  TucumaMainShell(
      Supplier<ProtectionStatus> statusLoader,
      Parent clientsView,
      Parent professionalsView,
      Parent servicesView,
      Parent packagesView,
      Parent appointmentsView) {
    this(
        statusLoader,
        clientsView,
        professionalsView,
        servicesView,
        packagesView,
        appointmentsView,
        null);
  }

  TucumaMainShell(
      Supplier<ProtectionStatus> statusLoader,
      Parent clientsView,
      Parent professionalsView,
      Parent servicesView,
      Parent packagesView,
      Parent appointmentsView,
      Parent cashView) {
    this(
        statusLoader,
        clientsView,
        professionalsView,
        servicesView,
        packagesView,
        appointmentsView,
        cashView,
        null);
  }

  TucumaMainShell(
      Supplier<ProtectionStatus> statusLoader,
      Parent clientsView,
      Parent professionalsView,
      Parent servicesView,
      Parent packagesView,
      Parent appointmentsView,
      Parent cashView,
      Parent reportsView) {
    this(
        statusLoader,
        clientsView,
        professionalsView,
        servicesView,
        packagesView,
        appointmentsView,
        cashView,
        reportsView,
        null);
  }

  TucumaMainShell(
      Supplier<ProtectionStatus> statusLoader,
      Parent clientsView,
      Parent professionalsView,
      Parent servicesView,
      Parent packagesView,
      Parent appointmentsView,
      Parent cashView,
      Parent reportsView,
      Parent inventoryView) {
    this(
        statusLoader,
        clientsView,
        professionalsView,
        servicesView,
        packagesView,
        appointmentsView,
        cashView,
        reportsView,
        inventoryView,
        () -> {});
  }

  TucumaMainShell(
      Supplier<ProtectionStatus> statusLoader,
      Parent clientsView,
      Parent professionalsView,
      Parent servicesView,
      Parent packagesView,
      Parent appointmentsView,
      Parent cashView,
      Parent reportsView,
      Parent inventoryView,
      ManualSynchronizationPort manualSynchronization) {
    this(
        statusLoader,
        clientsView,
        professionalsView,
        servicesView,
        packagesView,
        appointmentsView,
        cashView,
        reportsView,
        inventoryView,
        manualSynchronization,
        null);
  }

  TucumaMainShell(
      Supplier<ProtectionStatus> statusLoader,
      Parent clientsView,
      Parent professionalsView,
      Parent servicesView,
      Parent packagesView,
      Parent appointmentsView,
      Parent cashView,
      Parent reportsView,
      Parent inventoryView,
      ManualSynchronizationPort manualSynchronization,
      Parent conflictsView) {
    this.statusLoader = statusLoader;
    this.clientsView = clientsView;
    this.professionalsView = professionalsView;
    this.servicesView = servicesView;
    this.packagesView = packagesView;
    this.appointmentsView = appointmentsView;
    this.cashView = cashView;
    this.reportsView = reportsView;
    this.inventoryView = inventoryView;
    this.manualSynchronization = manualSynchronization;
    this.remoteBackupHistory =
        manualSynchronization instanceof RemoteBackupHistoryPort history ? history : null;
    this.conflictsView = conflictsView;
    root.setTop(new VBox(createMenuBar(), createToolBar()));
    root.setLeft(createNavigation());
    root.setCenter(workArea);
    root.setBottom(statusBar);
    showModule("Início");
    refreshStatus();
    statusRefresh = new Timeline(new KeyFrame(Duration.seconds(3), event -> refreshStatus()));
    statusRefresh.setCycleCount(Timeline.INDEFINITE);
    statusRefresh.play();
    TucumaTheme.apply(root);
  }

  Parent root() {
    return root;
  }

  void setLogoutAction(Runnable action) {
    logoutAction = action;
  }

  void refreshStatus() {
    try {
      statusBar.show(statusLoader.get());
    } catch (RuntimeException failure) {
      statusBar.showReadFailure();
    }
  }

  void showStatus(ProtectionStatus status) {
    statusBar.show(status);
  }

  void stopStatusRefresh() {
    statusRefresh.stop();
    if (reportsView instanceof ReportsModulePane reportsPane) reportsPane.close();
  }

  void refreshActiveModuleAccess() {
    if (activeModule.equals("Clientes") && clientsView instanceof ClientRegistryPane clientPane) {
      clientPane.refreshAccess();
    }
    if (activeModule.equals("Profissionais")
        && professionalsView instanceof ProfessionalRegistryPane professionalPane) {
      professionalPane.refreshAccess();
    }
    if (activeModule.equals("Serviços") && servicesView instanceof ServiceCatalogPane servicePane) {
      servicePane.refreshAccess();
    }
    if (activeModule.equals("Pacotes") && packagesView instanceof PackageRegistryPane packagePane) {
      packagePane.refreshAccess();
    }
    if (activeModule.equals("Agenda")
        && appointmentsView instanceof AppointmentRegistryPane appointmentPane) {
      appointmentPane.refreshAccess();
    }
    if (activeModule.equals("Caixa") && cashView instanceof CashRegisterPane cashPane) {
      cashPane.refresh();
    }
    if (activeModule.equals("Relatórios") && reportsView instanceof ReportsModulePane reportsPane) {
      reportsPane.refresh();
    }
    if (activeModule.equals("Estoque") && inventoryView instanceof InventoryPane inventoryPane)
      inventoryPane.refreshAccess();
    if (activeModule.equals("Conflitos")
        && conflictsView instanceof ConflictResolutionPane conflictPane) conflictPane.refresh();
  }

  private MenuBar createMenuBar() {
    MenuBar menuBar = new MenuBar();
    menuBar.setId("mainMenuBar");
    menuBar
        .getMenus()
        .addAll(
            menu(
                "Arquivo",
                item("Encerrar sessão", "menu-logout", () -> logoutAction.run()),
                new SeparatorMenuItem(),
                item("Sair", "menu-exit", () -> root.getScene().getWindow().hide())),
            menu(
                "Cadastros",
                moduleItem("Clientes", "menu-clients"),
                moduleItem("Profissionais", "menu-professionals"),
                moduleItem("Serviços", "menu-services"),
                moduleItem("Pacotes", "menu-packages")),
            menu(
                "Agenda",
                moduleItem("Agenda", "menu-agenda"),
                moduleItem("Atendimento", "menu-appointments")),
            menu(
                "Movimentações",
                moduleItem("Caixa", "menu-cash"),
                moduleItem("Estoque", "menu-inventory")),
            menu("Relatórios", moduleItem("Relatórios", "menu-reports")),
            menu(
                "Ferramentas",
                createSynchronizationMenuItem(),
                moduleItem("Conflitos", "menu-sync-conflicts")),
            menu("Ajuda", item("Sobre", "menu-about", () -> showModule("Sobre"))));
    return menuBar;
  }

  private ToolBar createToolBar() {
    Button quickAgenda = new Button("Agenda");
    quickAgenda.setId("toolbar-agenda");
    quickAgenda.setOnAction(event -> showModule("Agenda"));

    Button logout = new Button("Encerrar sessão");
    logout.setId("toolbar-logout");
    logout.setOnAction(event -> logoutAction.run());

    synchronizationButton = new Button("Sincronizar");
    synchronizationButton.setId("command-sync");
    synchronizationButton.setTooltip(
        new javafx.scene.control.Tooltip("Envia alterações pendentes somente quando acionado."));
    synchronizationButton.setAccessibleHelp("Inicia a sincronização manual.");
    synchronizationButton.setOnAction(event -> startSynchronization());
    synchronizationProgress = new Label("Sincronização manual");
    synchronizationProgress.setId("syncProgress");

    ToolBar toolBar =
        new ToolBar(
            disabledButton("Novo", "command-new"),
            disabledButton("Editar", "command-edit"),
            disabledButton("Salvar", "command-save"),
            disabledButton("Cancelar", "command-cancel"),
            disabledButton("Imprimir", "command-print"),
            synchronizationButton,
            synchronizationProgress,
            quickAgenda,
            logout);
    toolBar.setId("commandToolbar");
    return toolBar;
  }

  private MenuItem createSynchronizationMenuItem() {
    synchronizationMenuItem = new MenuItem("Sincronizar");
    synchronizationMenuItem.setId("menu-sync");
    synchronizationMenuItem.setOnAction(event -> startSynchronization());
    return synchronizationMenuItem;
  }

  private void startSynchronization() {
    if (!synchronizationRunning.compareAndSet(false, true)) {
      synchronizationButton.setText("Cancelando…");
      synchronizationButton.setDisable(true);
      synchronizationMenuItem.setText("Cancelando sincronização…");
      synchronizationMenuItem.setDisable(true);
      if (dialogState != null)
        dialogState.setText("Cancelamento solicitado; aguardando retorno seguro...");
      if (dialogSyncButton != null) {
        dialogSyncButton.setText("Cancelando…");
        dialogSyncButton.setDisable(true);
      }
      Thread worker = synchronizationWorker.get();
      if (worker != null) worker.interrupt();
      return;
    }
    ensureSynchronizationDialog();
    synchronizationDialog.show();
    synchronizationButton.setText("Cancelar");
    synchronizationMenuItem.setText("Cancelar sincronização");
    synchronizationProgress.setText("Preparando sincronização manual...");
    dialogSyncButton.setText("Cancelar sincronização");
    dialogRetryButton.setDisable(true);
    dialogRetryButton.setVisible(false);
    dialogRecovery.setText("");
    dialogProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
    dialogState.setText("Sincronização em andamento");
    updateDialogProtection();
    AtomicReference<SynchronizationProgress> outcome =
        new AtomicReference<>(
            SynchronizationProgress.of(SynchronizationProgress.State.VERIFYING_ACCESS));
    Task<Void> task =
        new Task<>() {
          @Override
          protected Void call() {
            manualSynchronization.synchronizeNow(
                progress -> {
                  outcome.set(progress);
                  updateMessage(progress.detail());
                  javafx.application.Platform.runLater(() -> showSynchronizationProgress(progress));
                });
            return null;
          }
        };
    task.messageProperty()
        .addListener(
            (observable, previous, current) ->
                synchronizationProgress.setText(current == null ? "" : current));
    task.setOnSucceeded(
        event -> {
          finishSynchronization();
          refreshStatus();
          SynchronizationProgress result = outcome.get();
          showSynchronizationResult(result, null);
        });
    task.setOnFailed(
        event -> {
          finishSynchronization();
          refreshStatus();
          Throwable failure = task.getException();
          SynchronizationProgress result = outcome.get();
          synchronizationProgress.setText(result.detail());
          showSynchronizationResult(result, failure);
        });
    Thread worker = new Thread(task, "tucuma-manual-sync");
    worker.setDaemon(true);
    synchronizationWorker.set(worker);
    worker.start();
  }

  private void finishSynchronization() {
    synchronizationWorker.set(null);
    synchronizationRunning.set(false);
    synchronizationButton.setDisable(false);
    synchronizationMenuItem.setDisable(false);
    synchronizationButton.setText("Sincronizar");
    synchronizationMenuItem.setText("Sincronizar");
    if (dialogSyncButton != null) {
      dialogSyncButton.setDisable(false);
      dialogSyncButton.setText("Sincronizar novamente");
    }
    if (dialogRetryButton != null) dialogRetryButton.setDisable(false);
  }

  private void ensureSynchronizationDialog() {
    if (synchronizationDialog != null) return;
    synchronizationDialog = new Dialog<>();
    synchronizationDialog.setTitle("Sincronização e backup remoto");
    synchronizationDialog.setHeaderText("Estado local e proteção remota");
    synchronizationDialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    dialogProtection = new Label();
    dialogProtection.setId("syncDialogProtection");
    dialogLastSync = new Label();
    dialogLastSync.setId("syncDialogLastSync");
    dialogLastBackup = new Label();
    dialogLastBackup.setId("syncDialogLastBackup");
    dialogState = new Label("Pronto para sincronizar");
    dialogState.setId("syncDialogState");
    dialogNetwork = new Label("Serviço remoto: não consultado");
    dialogNetwork.setId("syncDialogNetwork");
    dialogDetail = new Label("Salvar grava os dados localmente. Sincronizar envia as pendências.");
    dialogDetail.setId("syncDialogDetail");
    dialogDetail.setWrapText(true);
    dialogRecovery = new Label();
    dialogRecovery.setId("syncDialogRecovery");
    dialogRecovery.setWrapText(true);
    dialogProgressBar = new ProgressBar(0);
    dialogProgressBar.setId("syncDialogProgressBar");
    dialogProgressBar.setMaxWidth(Double.MAX_VALUE);
    dialogSyncButton = new Button("Sincronizar agora");
    dialogSyncButton.setId("syncDialogAction");
    dialogSyncButton.setOnAction(event -> startSynchronization());
    dialogRetryButton = new Button("Tentar novamente");
    dialogRetryButton.setId("syncDialogRetry");
    dialogRetryButton.setVisible(false);
    dialogRetryButton.setManaged(false);
    dialogRetryButton.setOnAction(event -> startSynchronization());
    dialogBackupsButton = new Button("Consultar backups disponíveis");
    dialogBackupsButton.setId("syncDialogBackups");
    dialogBackups = new ListView<>();
    dialogBackups.setId("syncDialogBackupList");
    dialogBackups.setPrefHeight(110);
    boolean canQueryBackups = remoteBackupHistory != null && remoteBackupHistory.canListBackups();
    dialogBackupsButton.setVisible(canQueryBackups);
    dialogBackupsButton.setManaged(canQueryBackups);
    dialogBackups.setVisible(canQueryBackups);
    dialogBackups.setManaged(canQueryBackups);
    dialogBackupsButton.setOnAction(event -> queryRemoteBackups());
    HBox actions = new HBox(8, dialogSyncButton, dialogRetryButton, dialogBackupsButton);
    VBox content =
        new VBox(
            8,
            dialogProtection,
            dialogLastSync,
            dialogLastBackup,
            dialogNetwork,
            dialogState,
            dialogDetail,
            dialogProgressBar,
            dialogRecovery,
            actions,
            new Label("Pontos remotos validados (consulta não baixa nem restaura):"),
            dialogBackups);
    content.setPadding(new Insets(6));
    content.setPrefWidth(520);
    synchronizationDialog.getDialogPane().setContent(content);
    synchronizationDialog.setOnHidden(event -> synchronizationDialog = null);
    updateDialogProtection();
    TucumaTheme.apply(synchronizationDialog.getDialogPane());
  }

  private void updateDialogProtection() {
    if (dialogProtection == null) return;
    try {
      ProtectionStatus status = statusLoader.get();
      dialogProtection.setText(
          status.protectionMessage() + " — Pendentes: " + status.pendingOperations());
      dialogLastSync.setText(
          "Última confirmação remota: " + display(status.lastSynchronizationAt()));
      dialogLastBackup.setText(
          "Último backup validado: " + display(status.lastValidatedBackupAt()));
    } catch (RuntimeException failure) {
      dialogProtection.setText("Estado local indisponível; dados salvos continuam no computador.");
      dialogLastSync.setText("Última confirmação remota: indisponível");
      dialogLastBackup.setText("Último backup validado: indisponível");
    }
  }

  private void showSynchronizationProgress(SynchronizationProgress progress) {
    if (synchronizationDialog == null || !synchronizationDialog.isShowing()) return;
    dialogState.setText(progress.state().label());
    dialogNetwork.setText(
        switch (progress.state()) {
          case VERIFYING_ACCESS, CHECKING_CHECKPOINT -> "Serviço remoto: verificando acesso";
          case NO_CONNECTION -> "Serviço remoto: sem conexão";
          case FAILED -> "Serviço remoto: falha na comunicação";
          case CANCELLED -> "Serviço remoto: consulta cancelada";
          default -> "Serviço remoto: conectado";
        });
    dialogDetail.setText(
        progress.detail()
            + "\nEnviadas: "
            + progress.sentOperations()
            + " | Recebidas: "
            + progress.receivedChanges()
            + " | Conflitos: "
            + progress.conflicts()
            + " | Checkpoint: "
            + progress.checkpoint());
    updateDialogProtection();
  }

  private void showSynchronizationResult(SynchronizationProgress result, Throwable failure) {
    if (synchronizationDialog == null) return;
    dialogState.setText(result.state().label());
    dialogNetwork.setText(
        switch (result.state()) {
          case NO_CONNECTION -> "Serviço remoto: sem conexão";
          case FAILED -> "Serviço remoto: falha na comunicação";
          case CANCELLED -> "Serviço remoto: cancelada";
          default -> "Serviço remoto: conectado";
        });
    dialogDetail.setText(
        failure == null || failure.getMessage() == null
            ? result.detail()
            : result.detail() + "\n" + failure.getMessage());
    boolean successful = result.state() == SynchronizationProgress.State.BACKUP_VALIDATED;
    dialogProgressBar.setProgress(successful ? 1 : 0);
    dialogRetryButton.setVisible(!successful);
    dialogRetryButton.setManaged(!successful);
    dialogRecovery.setText(
        successful
            ? "Backup remoto concluído e validado. Os dados locais seguem disponíveis offline."
            : "Os dados locais permanecem salvos. Verifique a conexão e tente novamente; alterações não confirmadas continuam pendentes.");
    updateDialogProtection();
  }

  private void queryRemoteBackups() {
    if (remoteBackupHistory == null || dialogBackupsButton.isDisabled()) return;
    dialogBackupsButton.setDisable(true);
    dialogState.setText("Consultando pontos remotos validados...");
    Task<List<RemoteBackupHistoryPort.RemoteBackupSummary>> task =
        new Task<>() {
          @Override
          protected List<RemoteBackupHistoryPort.RemoteBackupSummary> call() {
            return remoteBackupHistory.listAvailableBackups();
          }
        };
    task.setOnSucceeded(
        event -> {
          dialogBackupsButton.setDisable(false);
          dialogBackups
              .getItems()
              .setAll(
                  task.getValue().stream()
                      .map(
                          backup ->
                              backup.backupId()
                                  + " — checkpoint "
                                  + backup.checkpoint()
                                  + " — "
                                  + display(backup.createdAt()))
                      .toList());
          dialogState.setText("Consulta concluída; nenhum backup foi baixado.");
        });
    task.setOnFailed(
        event -> {
          dialogBackupsButton.setDisable(false);
          dialogState.setText("Não foi possível consultar backups. Tente novamente.");
          dialogRecovery.setText("A consulta não alterou nem baixou dados locais ou remotos.");
        });
    Thread worker = new Thread(task, "tucuma-backup-history");
    worker.setDaemon(true);
    worker.start();
  }

  private static String display(java.time.Instant instant) {
    return instant == null ? "Nunca" : instant.toString();
  }

  private VBox createNavigation() {
    VBox navigation = new VBox(2);
    navigation.setId("moduleNavigation");
    navigation.setPadding(new Insets(8));
    navigation.setPrefWidth(174);
    navigation.getChildren().add(new Label("MÓDULOS"));
    for (String module :
        new String[] {
          "Início",
          "Clientes",
          "Profissionais",
          "Serviços",
          "Pacotes",
          "Agenda",
          "Atendimento",
          "Caixa",
          "Estoque",
          "Relatórios",
          "Conflitos",
          "Administração"
        }) {
      String id = "module-" + moduleId(module);
      Button button = new Button(module);
      button.setId(id);
      button.setMaxWidth(Double.MAX_VALUE);
      button.setOnAction(event -> showModule(module));
      moduleButtons.put(module, button);
      navigation.getChildren().add(button);
    }
    return navigation;
  }

  private void showModule(String module) {
    activeModule = module;
    pageTitle.setId("moduleTitle");
    pageTitle.setText(module);
    Label placeholder =
        new Label(
            module.equals("Início")
                ? "Selecione uma área de trabalho no menu ou na navegação lateral."
                : "Esta área será habilitada conforme a entrega do módulo.");
    placeholder.setId("moduleDescription");
    workArea.setTop(pageTitle);
    BorderPane.setMargin(pageTitle, new Insets(12, 14, 8, 14));
    if (module.equals("Clientes") && clientsView instanceof ClientRegistryPane clientPane) {
      clientPane.refreshAccess();
      workArea.setCenter(clientPane);
    } else if (module.equals("Profissionais")
        && professionalsView instanceof ProfessionalRegistryPane professionalPane) {
      professionalPane.refreshAccess();
      workArea.setCenter(professionalPane);
    } else if (module.equals("Serviços")
        && servicesView instanceof ServiceCatalogPane servicePane) {
      servicePane.refreshAccess();
      workArea.setCenter(servicePane);
    } else if (module.equals("Pacotes")
        && packagesView instanceof PackageRegistryPane packagePane) {
      packagePane.refreshAccess();
      packagePane.refresh();
      workArea.setCenter(packagePane);
    } else if (module.equals("Agenda")
        && appointmentsView instanceof AppointmentRegistryPane appointmentPane) {
      appointmentPane.refreshAccess();
      appointmentPane.refresh();
      workArea.setCenter(appointmentPane);
    } else if (module.equals("Caixa") && cashView instanceof CashRegisterPane cashPane) {
      cashPane.refresh();
      workArea.setCenter(cashPane);
    } else if (module.equals("Relatórios")
        && reportsView instanceof ReportsModulePane reportsPane) {
      reportsPane.refresh();
      workArea.setCenter(reportsPane);
    } else if (module.equals("Estoque") && inventoryView instanceof InventoryPane inventoryPane) {
      inventoryPane.refreshAccess();
      inventoryPane.refresh();
      workArea.setCenter(inventoryPane);
    } else if (module.equals("Conflitos")
        && conflictsView instanceof ConflictResolutionPane conflictPane) {
      conflictPane.refresh();
      workArea.setCenter(conflictPane);
    } else {
      workArea.setCenter(placeholder);
    }
    BorderPane.setMargin(placeholder, new Insets(8, 14, 14, 14));
    moduleButtons.forEach((name, button) -> button.setDisable(name.equals(module)));
  }

  private MenuItem moduleItem(String module, String id) {
    return item(module, id, () -> showModule(module));
  }

  private static Menu menu(String text, MenuItem... items) {
    Menu menu = new Menu(text);
    menu.getItems().addAll(items);
    return menu;
  }

  private static MenuItem item(String text, String id, Runnable action) {
    MenuItem item = new MenuItem(text);
    item.setId(id);
    item.setOnAction(event -> action.run());
    return item;
  }

  private static MenuItem disabledItem(String text, String id) {
    MenuItem item = new MenuItem(text);
    item.setId(id);
    item.setDisable(true);
    return item;
  }

  private static Button disabledButton(String text, String id) {
    return disabledButton(text, id, UNAVAILABLE);
  }

  private static Button disabledButton(String text, String id, String reason) {
    Button button = new Button(text);
    button.setId(id);
    button.setDisable(true);
    button.setTooltip(new javafx.scene.control.Tooltip(reason));
    button.setAccessibleHelp(reason);
    return button;
  }

  private static String moduleId(String module) {
    return switch (module) {
      case "Início" -> "home";
      case "Clientes" -> "clients";
      case "Profissionais" -> "professionals";
      case "Serviços" -> "services";
      case "Pacotes" -> "packages";
      case "Agenda" -> "agenda";
      case "Atendimento" -> "appointments";
      case "Caixa" -> "cash";
      case "Estoque" -> "inventory";
      case "Relatórios" -> "reports";
      case "Conflitos" -> "sync-conflicts";
      case "Administração" -> "administration";
      default -> module.toLowerCase(java.util.Locale.ROOT);
    };
  }
}
