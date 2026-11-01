package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.Page;
import br.com.espacotucuma.desktop.application.client.Client;
import br.com.espacotucuma.desktop.application.client.ClientContactFilter;
import br.com.espacotucuma.desktop.application.client.ClientPageQuery;
import br.com.espacotucuma.desktop.application.client.ClientSortField;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.port.client.ClientRepository;
import br.com.espacotucuma.desktop.application.port.prepaid.PrepaidPackageRepository;
import br.com.espacotucuma.desktop.application.prepaid.AttendanceOption;
import br.com.espacotucuma.desktop.application.prepaid.MaintainPrepaidPackagesUseCase;
import br.com.espacotucuma.desktop.application.prepaid.PackagePageQuery;
import br.com.espacotucuma.desktop.application.prepaid.PackageUsage;
import br.com.espacotucuma.desktop.application.prepaid.PaymentForm;
import br.com.espacotucuma.desktop.application.prepaid.PrepaidPackage;
import br.com.espacotucuma.desktop.application.prepaid.SessionBalance;
import br.com.espacotucuma.desktop.domain.money.Money;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

/** Local catalog and prepaid session balances. */
public final class PackageRegistryPane extends BorderPane {
  private final PrepaidPackageRepository repository;
  private final ClientRepository clients;
  private final MaintainPrepaidPackagesUseCase useCase;
  private final AuthorizationService authorization;
  private final TableView<PrepaidPackage> packageTable = new TableView<>();
  private final TableView<SessionBalance> balanceTable = new TableView<>();
  private final TextField packageSearch = new TextField();
  private final TextField balanceSearch = new TextField();
  private final Button createPackage = new Button("Novo pacote");
  private final Button editPackage = new Button("Editar");
  private final Button deactivatePackage = new Button("Inativar");
  private final Button contractPackage = new Button("Contratar");
  private final Button consumeSession = new Button("Consumir sessão");
  private final Button showHistory = new Button("Extrato");
  private final Label message = new Label();

  public PackageRegistryPane(
      PrepaidPackageRepository repository,
      ClientRepository clients,
      MaintainPrepaidPackagesUseCase useCase,
      AuthorizationService authorization) {
    this.repository = repository;
    this.clients = clients;
    this.useCase = useCase;
    this.authorization = authorization;
    packageTable.setId("packageCatalogTable");
    balanceTable.setId("packageBalanceTable");
    packageSearch.setId("packageSearch");
    balanceSearch.setId("packageBalanceSearch");
    createPackage.setId("packageNew");
    contractPackage.setId("packageContract");
    consumeSession.setId("packageConsume");
    showHistory.setId("packageHistory");
    createPackage.setOnAction(event -> edit(null));
    editPackage.setOnAction(event -> edit(packageTable.getSelectionModel().getSelectedItem()));
    deactivatePackage.setOnAction(event -> deactivate());
    contractPackage.setOnAction(event -> contract());
    consumeSession.setOnAction(event -> consume());
    showHistory.setOnAction(event -> history());
    packageSearch.textProperty().addListener((o, old, value) -> refreshPackages());
    balanceSearch.textProperty().addListener((o, old, value) -> refreshBalances());
    packageTable
        .getSelectionModel()
        .selectedItemProperty()
        .addListener((o, old, value) -> updateButtons());
    balanceTable
        .getSelectionModel()
        .selectedItemProperty()
        .addListener((o, old, value) -> updateButtons());
    configureTables();

    Tab catalog = new Tab("Catálogo", new BorderPane(packageTable));
    catalog.setId("packageCatalogTab");
    catalog.setClosable(false);
    HBox catalogTools =
        new HBox(
            6,
            createPackage,
            editPackage,
            deactivatePackage,
            contractPackage,
            new Label("Pesquisar:"),
            packageSearch);
    catalogTools.setPadding(new Insets(7));
    ((BorderPane) catalog.getContent()).setTop(catalogTools);
    Tab balances = new Tab("Saldos de sessões", new BorderPane(balanceTable));
    balances.setId("packageBalancesTab");
    balances.setClosable(false);
    Label balanceRules =
        new Label(
            "Uma sessão por atendimento em andamento; validade indicada no saldo; reversão somente para atendimento cancelado (administrador).");
    balanceRules.setWrapText(true);
    HBox balanceTools =
        new HBox(
            6, new Label("Cliente:"), balanceSearch, consumeSession, showHistory, balanceRules);
    balanceTools.setPadding(new Insets(7));
    ((BorderPane) balances.getContent()).setTop(balanceTools);
    TabPane tabs = new TabPane(catalog, balances);
    tabs.setId("packageTabs");
    message.setId("packageMessage");
    message.setPadding(new Insets(6));
    setCenter(tabs);
    setBottom(message);
    refresh();
    refreshAccess();
  }

  public void refresh() {
    refreshPackages();
    refreshBalances();
  }

  public void refreshAccess() {
    boolean manage = authorization.isAllowed(Permission.PACKAGE_MANAGE);
    createPackage.setDisable(!manage);
    editPackage.setDisable(!manage || packageTable.getSelectionModel().getSelectedItem() == null);
    deactivatePackage.setDisable(
        !manage || packageTable.getSelectionModel().getSelectedItem() == null);
    contractPackage.setDisable(
        !authorization.isAllowed(Permission.PACKAGE_SELL)
            || packageTable.getSelectionModel().getSelectedItem() == null);
    consumeSession.setDisable(
        !authorization.isAllowed(Permission.SESSION_CONSUME)
            || balanceTable.getSelectionModel().getSelectedItem() == null);
    showHistory.setDisable(
        !authorization.isAllowed(Permission.PACKAGE_READ)
            || balanceTable.getSelectionModel().getSelectedItem() == null);
  }

  private void configureTables() {
    packageTable
        .getColumns()
        .setAll(
            column("Pacote", PrepaidPackage::name, 220),
            column("Sessões", PrepaidPackage::sessions, 80),
            column("Validade (dias)", PrepaidPackage::validityDays, 110),
            column("Valor", item -> item.price().amount().toPlainString(), 110),
            column("Ativo", item -> item.active() ? "Sim" : "Não", 70));
    balanceTable
        .getColumns()
        .setAll(
            column("Cliente", SessionBalance::clientName, 180),
            column("Pacote", SessionBalance::packageName, 170),
            column("Saldo", SessionBalance::sessionsRemaining, 70),
            column("Contratadas", SessionBalance::sessionsContracted, 90),
            column("Validade", SessionBalance::expiresOn, 100),
            column("Pago", item -> item.paidPrice().amount().toPlainString(), 100),
            column("Pagamento", SessionBalance::paymentForm, 100),
            column("Estado", SessionBalance::state, 90));
    packageTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    balanceTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    packageTable.setPlaceholder(new Label("Nenhum pacote encontrado."));
    balanceTable.setPlaceholder(new Label("Nenhum saldo encontrado."));
  }

  private <T, V> TableColumn<T, V> column(
      String title, java.util.function.Function<T, V> value, double width) {
    TableColumn<T, V> column = new TableColumn<>(title);
    column.setCellValueFactory(cell -> new SimpleObjectProperty<>(value.apply(cell.getValue())));
    column.setPrefWidth(width);
    column.setSortable(false);
    return column;
  }

  private void refreshPackages() {
    try {
      Page<PrepaidPackage> page =
          useCase.searchPackages(new PackagePageQuery(packageSearch.getText(), true, 0, 100));
      packageTable.setItems(FXCollections.observableArrayList(page.items()));
    } catch (RuntimeException failure) {
      failed(failure);
    }
    updateButtons();
  }

  private void refreshBalances() {
    try {
      Page<SessionBalance> page = useCase.searchBalances(balanceSearch.getText(), 0, 100);
      balanceTable.setItems(FXCollections.observableArrayList(page.items()));
    } catch (RuntimeException failure) {
      failed(failure);
    }
    updateButtons();
  }

  private void updateButtons() {
    refreshAccess();
  }

  private void edit(PrepaidPackage existing) {
    TextField name = new TextField(existing == null ? "" : existing.name());
    name.setId("packageName");
    TextField sessions =
        new TextField(existing == null ? "" : Integer.toString(existing.sessions()));
    sessions.setId("packageSessions");
    TextField days =
        new TextField(existing == null ? "" : Integer.toString(existing.validityDays()));
    days.setId("packageValidityDays");
    TextField price =
        new TextField(existing == null ? "" : existing.price().amount().toPlainString());
    price.setId("packagePrice");
    GridPane form =
        form(
            "Nome",
            name,
            "Quantidade de sessões",
            sessions,
            "Validade em dias",
            days,
            "Valor total (R$)",
            price);
    Dialog<ButtonType> dialog = dialog(existing == null ? "Novo pacote" : "Editar pacote", form);
    dialog
        .showAndWait()
        .filter(ButtonType.OK::equals)
        .ifPresent(
            ignored -> {
              try {
                int count = Integer.parseInt(sessions.getText().strip());
                int validity = Integer.parseInt(days.getText().strip());
                Money amount = new Money(new BigDecimal(price.getText().strip().replace(',', '.')));
                if (existing == null) useCase.create(name.getText(), count, validity, amount);
                else useCase.update(existing.id(), name.getText(), count, validity, amount);
                message.setText("Pacote salvo localmente.");
                refreshPackages();
              } catch (RuntimeException failure) {
                failed(failure);
              }
            });
  }

  private void deactivate() {
    PrepaidPackage selected = packageTable.getSelectionModel().getSelectedItem();
    if (selected == null) return;
    if (confirm("Inativar o pacote selecionado? Contratações existentes serão preservadas.")) {
      try {
        useCase.deactivate(selected.id());
        message.setText("Pacote inativado.");
        refreshPackages();
      } catch (RuntimeException failure) {
        failed(failure);
      }
    }
  }

  private void contract() {
    PrepaidPackage selected = packageTable.getSelectionModel().getSelectedItem();
    if (selected == null) return;
    ComboBox<Client> client = new ComboBox<>();
    client.setId("packageClient");
    client.setItems(
        FXCollections.observableArrayList(
            clients
                .searchPage(
                    new ClientPageQuery(
                        "", ClientContactFilter.ALL, ClientSortField.NAME, true, 0, 100))
                .items()));
    ComboBox<PaymentForm> payment =
        new ComboBox<>(FXCollections.observableArrayList(PaymentForm.values()));
    payment.setId("packagePaymentForm");
    payment.getSelectionModel().selectFirst();
    Dialog<ButtonType> dialog =
        dialog(
            "Contratar " + selected.name() + " — " + selected.price(),
            form("Cliente", client, "Forma de pagamento", payment));
    dialog
        .showAndWait()
        .filter(ButtonType.OK::equals)
        .ifPresent(
            ignored -> {
              try {
                if (client.getValue() == null)
                  throw new IllegalArgumentException("Selecione o cliente.");
                useCase.contract(selected.id(), client.getValue().id(), payment.getValue());
                message.setText("Pacote contratado e registrado localmente.");
                refreshBalances();
              } catch (RuntimeException failure) {
                failed(failure);
              }
            });
  }

  private void consume() {
    SessionBalance selected = balanceTable.getSelectionModel().getSelectedItem();
    if (selected == null) return;
    ComboBox<AttendanceOption> attendance = new ComboBox<>();
    attendance.setId("packageAttendance");
    attendance.setItems(
        FXCollections.observableArrayList(useCase.eligibleAttendances(selected.clientId())));
    if (attendance.getItems().isEmpty()) {
      message.setText("Não há atendimento em andamento elegível para este cliente.");
      return;
    }
    Dialog<ButtonType> dialog =
        dialog("Consumir uma sessão", form("Atendimento em andamento", attendance));
    dialog.getDialogPane().lookupButton(ButtonType.OK).setId("packageConsumeConfirm");
    dialog
        .showAndWait()
        .filter(ButtonType.OK::equals)
        .ifPresent(
            ignored -> {
              try {
                if (attendance.getValue() == null)
                  throw new IllegalArgumentException("Selecione o atendimento.");
                useCase.consume(selected.id(), attendance.getValue().id());
                message.setText("Uma sessão consumida e vinculada ao atendimento.");
                refreshBalances();
              } catch (RuntimeException failure) {
                failed(failure);
              }
            });
  }

  private void history() {
    SessionBalance selected = balanceTable.getSelectionModel().getSelectedItem();
    if (selected == null) return;
    try {
      List<PackageUsage> usages = useCase.history(selected.id());
      String text =
          usages.isEmpty()
              ? "Nenhuma sessão utilizada."
              : usages.stream()
                  .map(
                      u ->
                          DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                                  .withZone(java.time.ZoneId.of("America/Manaus"))
                                  .format(u.usedAt())
                              + " — atendimento "
                              + u.attendanceId()
                              + " — "
                              + u.sessionsUsed()
                              + " sessão(ões)"
                              + (u.reversed() ? " — revertida: " + u.reversalReason() : "")
                              + " — atendimento "
                              + u.attendanceState())
                  .reduce((a, b) -> a + "\n" + b)
                  .orElse("");
      Alert alert = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
      alert.setTitle("Extrato de sessões");
      alert.setHeaderText(selected.clientName() + " — " + selected.packageName());
      TucumaTheme.apply(alert.getDialogPane());
      List<PackageUsage> reversible =
          authorization.isAllowed(Permission.SESSION_REVERSE)
              ? usages.stream()
                  .filter(usage -> !usage.reversed() && "CANCELADO".equals(usage.attendanceState()))
                  .toList()
              : List.of();
      if (reversible.isEmpty()) {
        alert.showAndWait();
      } else {
        ButtonType reverse =
            new ButtonType("Reverter utilização", javafx.scene.control.ButtonBar.ButtonData.OTHER);
        alert.getButtonTypes().setAll(ButtonType.CLOSE, reverse);
        if (alert.showAndWait().filter(reverse::equals).isPresent()) {
          java.util.Map<String, PackageUsage> choices = new java.util.LinkedHashMap<>();
          reversible.forEach(
              usage ->
                  choices.put(usage.usedAt() + " — atendimento " + usage.attendanceId(), usage));
          ChoiceDialog<String> choice =
              new ChoiceDialog<>(choices.keySet().iterator().next(), choices.keySet());
          choice.setTitle("Reverter utilização");
          choice.setHeaderText("Selecione a sessão a devolver ao saldo.");
          TucumaTheme.apply(choice.getDialogPane());
          choice.showAndWait().map(choices::get).ifPresent(this::requestReversalReason);
        }
      }
    } catch (RuntimeException failure) {
      failed(failure);
    }
  }

  private void requestReversalReason(PackageUsage usage) {
    TextInputDialog reason = new TextInputDialog();
    reason.setTitle("Motivo da reversão");
    reason.setHeaderText("Informe por que a sessão será devolvida.");
    reason.setContentText("Motivo:");
    TucumaTheme.apply(reason.getDialogPane());
    reason
        .showAndWait()
        .ifPresent(
            value -> {
              try {
                useCase.reverseUsage(usage.id(), value);
                message.setText("Sessão devolvida ao saldo com reversão auditada.");
                refreshBalances();
              } catch (RuntimeException failure) {
                failed(failure);
              }
            });
  }

  private GridPane form(Object... rows) {
    GridPane grid = new GridPane();
    grid.setHgap(8);
    grid.setVgap(8);
    grid.setPadding(new Insets(12));
    for (int i = 0; i < rows.length; i += 2) {
      grid.add(new Label(rows[i].toString() + ":"), 0, i / 2);
      grid.add((javafx.scene.Node) rows[i + 1], 1, i / 2);
    }
    return grid;
  }

  private Dialog<ButtonType> dialog(String title, GridPane content) {
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.setTitle(title);
    dialog.getDialogPane().setContent(content);
    dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
    TucumaTheme.apply(dialog.getDialogPane());
    return dialog;
  }

  private boolean confirm(String text) {
    Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
    TucumaTheme.apply(alert.getDialogPane());
    return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
  }

  private void failed(RuntimeException failure) {
    message.setText(
        Optional.ofNullable(failure.getMessage()).orElse("Operação local não concluída."));
  }
}
