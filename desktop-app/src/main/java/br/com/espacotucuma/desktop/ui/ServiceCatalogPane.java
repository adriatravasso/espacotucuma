package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.port.service.ServiceRepository;
import br.com.espacotucuma.desktop.application.service.BeautyService;
import br.com.espacotucuma.desktop.application.service.MaintainServiceUseCase;
import br.com.espacotucuma.desktop.application.service.ServiceChange;
import br.com.espacotucuma.desktop.application.service.ServicePageQuery;
import br.com.espacotucuma.desktop.application.service.ServiceSortField;
import br.com.espacotucuma.desktop.domain.money.Money;
import br.com.espacotucuma.desktop.ui.components.PagedTablePane;
import br.com.espacotucuma.desktop.ui.components.TucumaEntryDialog;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
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
import javafx.scene.layout.HBox;
import javafx.stage.Window;

/** Offline catalog of non-medical beauty services and their current prices. */
public final class ServiceCatalogPane extends javafx.scene.layout.BorderPane {
  private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Manaus"));
  private final ServiceRepository services;
  private final MaintainServiceUseCase maintain;
  private final AuthorizationService authorization;
  private final AtomicBoolean includeInactive = new AtomicBoolean();
  private final PagedTablePane<BeautyService> table;
  private final CheckBox showInactive = new CheckBox("Mostrar inativos");
  private final Button history = new Button("Histórico");
  private final Button deactivate = new Button("Inativar");

  public ServiceCatalogPane(
      ServiceRepository services,
      MaintainServiceUseCase maintain,
      AuthorizationService authorization) {
    this.services = Objects.requireNonNull(services, "services");
    this.maintain = Objects.requireNonNull(maintain, "maintain");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    table = createTable();
    showInactive.setId("serviceShowInactive");
    showInactive.setOnAction(
        event -> {
          includeInactive.set(showInactive.isSelected());
          table.refresh();
        });
    history.setId("serviceHistory");
    history.setDisable(true);
    deactivate.setId("serviceDeactivate");
    deactivate.setDisable(true);
    table.addSelectionListener(
        selected -> {
          history.setDisable(selected == null);
          deactivate.setDisable(
              selected == null
                  || !selected.active()
                  || !authorization.isAllowed(Permission.SERVICE_DEACTIVATE));
        });
    table.setEditAvailability(BeautyService::active);
    history.setOnAction(event -> showHistory(table.selectedItem()));
    deactivate.setOnAction(event -> confirmDeactivate(table.selectedItem()));
    HBox actions =
        new HBox(
            6,
            new Label("Catálogo de serviços e preços vigentes"),
            showInactive,
            history,
            deactivate);
    actions.setPadding(new Insets(8));
    setTop(actions);
    setCenter(table);
    refreshAccess();
  }

  public void refreshAccess() {
    boolean canRead = authorization.isAllowed(Permission.SERVICE_READ);
    table.setNewActionEnabled(canRead && authorization.isAllowed(Permission.SERVICE_REGISTER));
    table.setEditActionEnabled(canRead && authorization.isAllowed(Permission.SERVICE_UPDATE));
    table.setEditAvailability(BeautyService::active);
    showInactive.setDisable(!canRead);
    history.setDisable(true);
    deactivate.setDisable(true);
    if (canRead) {
      setCenter(table);
      table.setFilterOptions(categoryFilters());
      table.refresh();
    } else {
      setCenter(new Label("Seu perfil não tem permissão para consultar o catálogo de serviços."));
    }
  }

  private PagedTablePane<BeautyService> createTable() {
    return new PagedTablePane<>(
        columns(),
        categoryFilters(),
        Map.of("NAME", "Nome", "CATEGORY", "Categoria", "PRICE", "Preço"),
        25,
        request -> {
          authorization.require(Permission.SERVICE_READ);
          return services.searchPage(
              new ServicePageQuery(
                  request.searchText(),
                  request.filter(),
                  includeInactive.get(),
                  ServiceSortField.valueOf(request.sortBy()),
                  request.ascending(),
                  request.pageIndex(),
                  request.pageSize()));
        },
        this::create,
        this::edit);
  }

  private Map<String, String> categoryFilters() {
    Map<String, String> filters = new LinkedHashMap<>();
    filters.put("ALL", "Todas as categorias");
    for (String category : services.categories()) filters.put(category, category);
    return filters;
  }

  private List<TableColumn<BeautyService, ?>> columns() {
    TableColumn<BeautyService, String> name = new TableColumn<>("Serviço");
    name.setCellValueFactory(
        row -> new javafx.beans.property.ReadOnlyStringWrapper(row.getValue().name()));
    TableColumn<BeautyService, String> category = new TableColumn<>("Categoria");
    category.setCellValueFactory(
        row -> new javafx.beans.property.ReadOnlyStringWrapper(row.getValue().category()));
    TableColumn<BeautyService, String> duration = new TableColumn<>("Duração");
    duration.setCellValueFactory(
        row ->
            new javafx.beans.property.ReadOnlyStringWrapper(
                row.getValue().durationMinutes() + " min"));
    TableColumn<BeautyService, String> price = new TableColumn<>("Preço atual");
    price.setCellValueFactory(
        row ->
            new javafx.beans.property.ReadOnlyStringWrapper(
                NumberFormat.getCurrencyInstance(PT_BR).format(row.getValue().price().amount())));
    TableColumn<BeautyService, String> commission = new TableColumn<>("Comissão padrão");
    commission.setCellValueFactory(
        row ->
            new javafx.beans.property.ReadOnlyStringWrapper(
                BigDecimal.valueOf(row.getValue().commissionBasisPoints(), 2)
                        .stripTrailingZeros()
                        .toPlainString()
                    + "%"));
    TableColumn<BeautyService, String> status = new TableColumn<>("Situação");
    status.setCellValueFactory(
        row ->
            new javafx.beans.property.ReadOnlyStringWrapper(
                row.getValue().active() ? "Ativo" : "Inativo"));
    return List.of(name, category, duration, price, commission, status);
  }

  private boolean create() {
    if (!authorization.isAllowed(Permission.SERVICE_REGISTER)) return false;
    Map<String, String> values = showForm(null);
    if (values == null) return false;
    try {
      maintain.register(
          values.get("serviceName"),
          values.get("serviceCategory"),
          Integer.parseInt(values.get("durationMinutes")),
          parseMoney(values.get("price")),
          parseCommission(values.get("commissionPercent")));
      refreshCatalog();
      return true;
    } catch (RuntimeException failure) {
      showError("Não foi possível cadastrar o serviço.", failure.getMessage());
      return false;
    }
  }

  private boolean edit(BeautyService service) {
    if (!authorization.isAllowed(Permission.SERVICE_UPDATE)) return false;
    Map<String, String> values = showForm(service);
    if (values == null) return false;
    try {
      maintain.update(
          service.id(),
          values.get("serviceName"),
          values.get("serviceCategory"),
          Integer.parseInt(values.get("durationMinutes")),
          parseMoney(values.get("price")),
          parseCommission(values.get("commissionPercent")));
      refreshCatalog();
      return true;
    } catch (RuntimeException failure) {
      showError("Não foi possível atualizar o serviço.", failure.getMessage());
      return false;
    }
  }

  private Map<String, String> showForm(BeautyService service) {
    Map<String, Boolean> fields = new LinkedHashMap<>();
    fields.put("serviceName", true);
    fields.put("serviceCategory", true);
    fields.put("durationMinutes", true);
    fields.put("price", true);
    fields.put("commissionPercent", true);
    Map<String, String> initial = new LinkedHashMap<>();
    if (service != null) {
      initial.put("serviceName", service.name());
      initial.put("serviceCategory", service.category());
      initial.put("durationMinutes", Integer.toString(service.durationMinutes()));
      initial.put("price", service.price().amount().toPlainString());
      initial.put(
          "commissionPercent",
          BigDecimal.valueOf(service.commissionBasisPoints(), 2).toPlainString());
    }
    Window owner = getScene() == null ? null : getScene().getWindow();
    TucumaEntryDialog dialog =
        new TucumaEntryDialog(
            owner, service == null ? "Novo serviço" : "Editar serviço", fields, initial);
    return dialog.showAndWait().orElse(null);
  }

  private void refreshCatalog() {
    table.setFilterOptions(categoryFilters());
    table.refresh();
  }

  private void confirmDeactivate(BeautyService service) {
    if (service == null || !service.active()) return;
    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
    confirm.setTitle("Inativar serviço");
    confirm.setHeaderText("Inativar " + service.name() + "?");
    confirm.setContentText(
        "O serviço deixará de aparecer para novos agendamentos. Atendimentos e preços anteriores serão preservados.");
    confirm.getDialogPane().lookupButton(ButtonType.OK).setId("serviceDeactivateConfirm");
    TucumaTheme.apply(confirm.getDialogPane());
    confirm
        .showAndWait()
        .filter(ButtonType.OK::equals)
        .ifPresent(
            ignored -> {
              try {
                maintain.deactivate(service.id());
                refreshCatalog();
              } catch (RuntimeException failure) {
                showError("Não foi possível inativar o serviço.", failure.getMessage());
              }
            });
  }

  private void showHistory(BeautyService service) {
    if (service == null) return;
    authorization.require(Permission.SERVICE_READ);
    TableView<ServiceChange> changes = new TableView<>();
    changes.setId("serviceChangeHistory");
    TableColumn<ServiceChange, String> date = new TableColumn<>("Data e hora");
    date.setCellValueFactory(
        row ->
            new javafx.beans.property.ReadOnlyStringWrapper(
                DATE_TIME.format(row.getValue().occurredAt())));
    TableColumn<ServiceChange, String> action = new TableColumn<>("Operação");
    action.setCellValueFactory(
        row ->
            new javafx.beans.property.ReadOnlyStringWrapper(
                switch (row.getValue().action()) {
                  case "SERVICO_CADASTRADO" -> "Cadastro";
                  case "SERVICO_ATUALIZADO" -> "Alteração";
                  case "SERVICO_INATIVADO" -> "Inativação";
                  default -> row.getValue().action();
                }));
    TableColumn<ServiceChange, String> summary = new TableColumn<>("Alterações");
    summary.setCellValueFactory(
        row -> new javafx.beans.property.ReadOnlyStringWrapper(row.getValue().summary()));
    changes.getColumns().setAll(date, action, summary);
    changes.getItems().setAll(services.changeHistory(service.id()));
    changes.setPlaceholder(new Label("Nenhuma alteração registrada."));
    Dialog<Void> dialog = new Dialog<>();
    dialog.setTitle("Histórico do serviço — " + service.name());
    if (getScene() != null) dialog.initOwner(getScene().getWindow());
    ButtonType close = new ButtonType("Fechar", ButtonBar.ButtonData.CANCEL_CLOSE);
    dialog.getDialogPane().getButtonTypes().add(close);
    dialog.getDialogPane().lookupButton(close).setId("serviceHistoryClose");
    dialog.getDialogPane().setContent(changes);
    dialog.getDialogPane().setPrefSize(740, 360);
    TucumaTheme.apply(dialog.getDialogPane());
    dialog.showAndWait();
  }

  private Money parseMoney(String value) {
    try {
      BigDecimal amount = new BigDecimal(value.strip().replace(',', '.'));
      return new Money(amount.setScale(2, RoundingMode.UNNECESSARY));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Informe um preço válido com até duas casas decimais.");
    }
  }

  private int parseCommission(String value) {
    try {
      return new BigDecimal(value.strip().replace(',', '.'))
          .setScale(2, RoundingMode.UNNECESSARY)
          .movePointRight(2)
          .intValueExact();
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Informe uma comissão válida entre 0 e 100%.");
    }
  }

  private void showError(String header, String detail) {
    Alert alert = new Alert(Alert.AlertType.ERROR);
    alert.setTitle("Tucumã — Gestão");
    alert.setHeaderText(header);
    alert.setContentText(
        detail == null || detail.isBlank() ? "Revise os dados e tente novamente." : detail);
    TucumaTheme.apply(alert.getDialogPane());
    alert.showAndWait();
  }
}
