package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.Page;
import br.com.espacotucuma.desktop.application.client.Client;
import br.com.espacotucuma.desktop.application.client.ClientAttendanceHistory;
import br.com.espacotucuma.desktop.application.client.ClientContactFilter;
import br.com.espacotucuma.desktop.application.client.ClientPageQuery;
import br.com.espacotucuma.desktop.application.client.ClientSortField;
import br.com.espacotucuma.desktop.application.client.MaintainClientUseCase;
import br.com.espacotucuma.desktop.application.client.RegisterClientUseCase;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.port.client.ClientRepository;
import br.com.espacotucuma.desktop.ui.components.PagedTablePane;
import br.com.espacotucuma.desktop.ui.components.TucumaEntryDialog;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Window;

/** Offline client register, lookup, maintenance, inactivation, and attendance history. */
public final class ClientRegistryPane extends BorderPane {

  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());
  private final ClientRepository clients;
  private final RegisterClientUseCase registerClient;
  private final MaintainClientUseCase maintainClient;
  private final AuthorizationService authorization;
  private final AtomicBoolean includeInactive = new AtomicBoolean();
  private final PagedTablePane<Client> table;
  private final CheckBox showInactive = new CheckBox("Mostrar inativos");
  private final Button history = new Button("Histórico");
  private final Button deactivate = new Button("Inativar");

  public ClientRegistryPane(
      ClientRepository clients,
      RegisterClientUseCase registerClient,
      MaintainClientUseCase maintainClient,
      AuthorizationService authorization) {
    this.clients = Objects.requireNonNull(clients, "clients");
    this.registerClient = Objects.requireNonNull(registerClient, "registerClient");
    this.maintainClient = Objects.requireNonNull(maintainClient, "maintainClient");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.table = createTable();

    showInactive.setId("clientShowInactive");
    showInactive.setOnAction(
        event -> {
          includeInactive.set(showInactive.isSelected());
          table.refresh();
        });
    history.setId("clientHistory");
    history.setDisable(true);
    history.setAccessibleHelp("Selecione um cliente para consultar atendimentos anteriores.");
    deactivate.setId("clientDeactivate");
    deactivate.setDisable(true);
    deactivate.setAccessibleHelp("Inativar o cliente selecionado sem remover o histórico.");
    deactivate.setTooltip(new Tooltip("Inativar o cliente selecionado."));
    table.addSelectionListener(
        selected -> {
          history.setDisable(selected == null);
          deactivate.setDisable(
              selected == null
                  || !authorization.isAllowed(Permission.CLIENT_DEACTIVATE)
                  || !selected.active());
        });
    table.setEditAvailability(Client::active);
    history.setOnAction(event -> showHistory(table.selectedItem()));
    deactivate.setOnAction(event -> confirmDeactivate(table.selectedItem()));

    HBox actions =
        new HBox(
            6, new Label("Cadastro e histórico de clientes"), showInactive, history, deactivate);
    actions.setPadding(new Insets(8));
    setTop(actions);
    setCenter(table);
    refreshAccess();
  }

  public void refreshAccess() {
    boolean canRead = authorization.isAllowed(Permission.CLIENT_READ);
    boolean canRegister = canRead && authorization.isAllowed(Permission.CLIENT_REGISTER);
    boolean canUpdate = canRead && authorization.isAllowed(Permission.CLIENT_UPDATE);
    boolean canDeactivate = canRead && authorization.isAllowed(Permission.CLIENT_DEACTIVATE);
    table.setNewActionEnabled(canRegister);
    table.setEditActionEnabled(canUpdate);
    table.setEditAvailability(Client::active);
    showInactive.setDisable(!canRead);
    history.setDisable(true);
    deactivate.setDisable(true);
    String deactivateHelp =
        canDeactivate
            ? "Inativar o cliente selecionado sem remover o histórico."
            : "Somente uma pessoa administradora pode inativar clientes.";
    deactivate.setAccessibleHelp(deactivateHelp);
    deactivate.setTooltip(new Tooltip(deactivateHelp));
    if (canRead) {
      setCenter(table);
      table.refresh();
    } else {
      Label denied =
          new Label("Seu perfil não tem permissão para consultar cadastros de clientes.");
      denied.setAccessibleHelp("A permissão de leitura de clientes é necessária nesta área.");
      setCenter(denied);
    }
  }

  private PagedTablePane<Client> createTable() {
    Map<String, String> filters = new LinkedHashMap<>();
    filters.put("ALL", "Todos os contatos");
    filters.put("WITH_PHONE", "Com telefone");
    filters.put("WITHOUT_PHONE", "Sem telefone");
    Map<String, String> sorts = new LinkedHashMap<>();
    sorts.put("NAME", "Nome");
    sorts.put("PHONE", "Telefone");
    sorts.put("CREATED_AT", "Cadastro");
    return new PagedTablePane<>(
        columns(),
        filters,
        sorts,
        25,
        request -> {
          authorization.require(Permission.CLIENT_READ);
          Page<Client> page =
              clients.searchPage(
                  new ClientPageQuery(
                      request.searchText(),
                      ClientContactFilter.valueOf(request.filter()),
                      ClientSortField.valueOf(request.sortBy()),
                      request.ascending(),
                      request.pageIndex(),
                      request.pageSize(),
                      includeInactive.get()));
          return page;
        },
        this::create,
        this::edit);
  }

  private List<TableColumn<Client, ?>> columns() {
    TableColumn<Client, String> code = new TableColumn<>("Código");
    code.setCellValueFactory(
        row -> new javafx.beans.property.ReadOnlyStringWrapper(row.getValue().internalCode()));
    TableColumn<Client, String> name = new TableColumn<>("Nome");
    name.setCellValueFactory(
        row -> new javafx.beans.property.ReadOnlyStringWrapper(row.getValue().name()));
    TableColumn<Client, String> phone = new TableColumn<>("Telefone");
    phone.setCellValueFactory(
        row ->
            new javafx.beans.property.ReadOnlyStringWrapper(
                row.getValue().phone() == null ? "" : row.getValue().phone()));
    TableColumn<Client, String> status = new TableColumn<>("Situação");
    status.setCellValueFactory(
        row ->
            new javafx.beans.property.ReadOnlyStringWrapper(
                row.getValue().active() ? "Ativo" : "Inativo"));
    return List.of(code, name, phone, status);
  }

  private boolean create() {
    Map<String, String> values = showClientForm("Novo cliente", Map.of());
    if (values == null) return false;
    try {
      if (!confirmPossibleDuplicate(values, null)) return false;
      registerClient.register(
          values.get("name"),
          values.get("phone"),
          values.get("contact"),
          values.get("preferences"));
      return true;
    } catch (IllegalArgumentException invalid) {
      showError("Confira os dados do cliente", invalid.getMessage());
      return false;
    }
  }

  private boolean edit(Client client) {
    Map<String, String> values =
        showClientForm(
            "Editar cliente " + client.internalCode(),
            Map.of(
                "name", client.name(),
                "phone", Objects.toString(client.phone(), ""),
                "contact", Objects.toString(client.optionalContact(), ""),
                "preferences", Objects.toString(client.preferences(), "")));
    if (values == null) return false;
    try {
      if (!confirmPossibleDuplicate(values, client.id())) return false;
      maintainClient.update(
          client.id(),
          values.get("name"),
          values.get("phone"),
          values.get("contact"),
          values.get("preferences"));
      return true;
    } catch (IllegalArgumentException invalid) {
      showError("Confira os dados do cliente", invalid.getMessage());
      return false;
    }
  }

  private Map<String, String> showClientForm(String title, Map<String, String> initialValues) {
    Window owner = getScene() == null ? null : getScene().getWindow();
    Map<String, Boolean> fields = new LinkedHashMap<>();
    fields.put("name", true);
    fields.put("phone", false);
    fields.put("contact", false);
    fields.put("preferences", false);
    TucumaEntryDialog dialog = new TucumaEntryDialog(owner, title, fields, initialValues);
    dialog
        .getDialogPane()
        .lookup("#form-phone")
        .setAccessibleHelp("Opcional. Aceita números, espaços, +, parênteses, ponto e hífen.");
    dialog
        .getDialogPane()
        .lookup("#form-preferences")
        .setAccessibleHelp(
            "Somente preferências administrativas. Não registre dados clínicos ou diagnósticos.");
    return dialog.showAndWait().orElse(null);
  }

  private boolean confirmPossibleDuplicate(Map<String, String> values, UUID excludingId) {
    authorization.require(Permission.CLIENT_READ);
    List<Client> matches =
        clients.findPotentialDuplicates(values.get("name"), values.get("phone"), excludingId);
    if (matches.isEmpty()) return true;
    String candidates =
        matches.stream()
            .limit(5)
            .map(client -> client.internalCode() + " — " + client.name())
            .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    ButtonType goBack = new ButtonType("Voltar ao cadastro", ButtonBar.ButtonData.CANCEL_CLOSE);
    ButtonType continueAnyway =
        new ButtonType(
            excludingId == null ? "Cadastrar mesmo assim" : "Salvar mesmo assim",
            ButtonBar.ButtonData.OK_DONE);
    Alert warning = new Alert(Alert.AlertType.WARNING);
    warning.setTitle("Tucumã — Gestão");
    warning.setHeaderText("Possível cliente já cadastrado");
    warning.setContentText(
        "Confira o nome ou telefone antes de continuar. Pessoas homônimas podem ser diferentes.\n\n"
            + candidates);
    warning.getButtonTypes().setAll(goBack, continueAnyway);
    Window owner = getScene() == null ? null : getScene().getWindow();
    if (owner != null) warning.initOwner(owner);
    TucumaTheme.apply(warning.getDialogPane());
    warning.getDialogPane().lookupButton(goBack).setId("clientDuplicateBack");
    warning.getDialogPane().lookupButton(continueAnyway).setId("clientDuplicateContinue");
    return warning.showAndWait().filter(continueAnyway::equals).isPresent();
  }

  private void confirmDeactivate(Client client) {
    if (client == null || !client.active()) return;
    ButtonType cancel = new ButtonType("Manter ativo", ButtonBar.ButtonData.CANCEL_CLOSE);
    ButtonType confirm = new ButtonType("Inativar cliente", ButtonBar.ButtonData.OK_DONE);
    Alert warning = new Alert(Alert.AlertType.CONFIRMATION);
    warning.setTitle("Tucumã — Gestão");
    warning.setHeaderText("Inativar " + client.name() + "?");
    warning.setContentText(
        "O cadastro deixa de aparecer na lista ativa. O histórico permanece preservado.");
    warning.getButtonTypes().setAll(cancel, confirm);
    Window owner = getScene() == null ? null : getScene().getWindow();
    if (owner != null) warning.initOwner(owner);
    TucumaTheme.apply(warning.getDialogPane());
    warning.getDialogPane().lookupButton(confirm).setId("clientDeactivateConfirm");
    if (warning.showAndWait().filter(confirm::equals).isPresent()) {
      try {
        maintainClient.deactivate(client.id());
        table.refresh();
      } catch (RuntimeException failure) {
        showError(
            "Não foi possível inativar o cliente", "A operação local falhou. Tente novamente.");
      }
    }
  }

  private void showHistory(Client client) {
    if (client == null) return;
    authorization.require(Permission.CLIENT_READ);
    List<ClientAttendanceHistory> historyRows;
    try {
      historyRows = clients.attendanceHistory(client.id());
    } catch (RuntimeException failure) {
      showError("Não foi possível consultar o histórico", "Tente novamente.");
      return;
    }
    Dialog<Void> dialog = new Dialog<>();
    dialog.setTitle("Tucumã — Histórico de atendimentos");
    dialog.setHeaderText(client.internalCode() + " — " + client.name());
    Window owner = getScene() == null ? null : getScene().getWindow();
    if (owner != null) dialog.initOwner(owner);
    dialog
        .getDialogPane()
        .getButtonTypes()
        .add(new ButtonType("Fechar", ButtonBar.ButtonData.CANCEL_CLOSE));
    TableView<ClientAttendanceHistory> records =
        new TableView<>(FXCollections.observableArrayList(historyRows));
    records.setId("clientAttendanceHistory");
    records.setPlaceholder(new Label("Nenhum atendimento registrado."));
    TableColumn<ClientAttendanceHistory, String> date = new TableColumn<>("Data");
    date.setCellValueFactory(
        row ->
            new javafx.beans.property.ReadOnlyStringWrapper(
                DATE_TIME.format(row.getValue().startedAt())));
    TableColumn<ClientAttendanceHistory, String> services = new TableColumn<>("Serviços");
    services.setCellValueFactory(
        row -> new javafx.beans.property.ReadOnlyStringWrapper(row.getValue().services()));
    TableColumn<ClientAttendanceHistory, String> status = new TableColumn<>("Situação");
    status.setCellValueFactory(
        row ->
            new javafx.beans.property.ReadOnlyStringWrapper(
                attendanceStatus(row.getValue().status())));
    TableColumn<ClientAttendanceHistory, String> total = new TableColumn<>("Total");
    total.setCellValueFactory(
        row ->
            new javafx.beans.property.ReadOnlyStringWrapper(
                NumberFormat.getCurrencyInstance(java.util.Locale.forLanguageTag("pt-BR"))
                    .format(row.getValue().totalCents() / 100.0)));
    records.getColumns().setAll(date, services, status, total);
    records.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    dialog.getDialogPane().setContent(records);
    dialog.getDialogPane().setPrefSize(760, 440);
    dialog
        .getDialogPane()
        .lookupButton(dialog.getDialogPane().getButtonTypes().getFirst())
        .setId("clientHistoryClose");
    TucumaTheme.apply(dialog.getDialogPane());
    dialog.showAndWait();
  }

  private String attendanceStatus(String status) {
    return switch (status) {
      case "EM_ANDAMENTO" -> "Em andamento";
      case "CONCLUIDO" -> "Concluído";
      case "CANCELADO" -> "Cancelado";
      default -> status;
    };
  }

  private void showError(String title, String message) {
    Alert alert = new Alert(Alert.AlertType.ERROR);
    alert.setTitle("Tucumã — Gestão");
    alert.setHeaderText(title);
    alert.setContentText(message == null ? "Revise os dados informados." : message);
    Window owner = getScene() == null ? null : getScene().getWindow();
    if (owner != null) alert.initOwner(owner);
    TucumaTheme.apply(alert.getDialogPane());
    alert.showAndWait();
  }
}
