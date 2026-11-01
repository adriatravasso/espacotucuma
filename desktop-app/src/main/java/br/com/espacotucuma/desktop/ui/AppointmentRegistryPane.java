package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.client.Client;
import br.com.espacotucuma.desktop.application.client.ClientContactFilter;
import br.com.espacotucuma.desktop.application.client.ClientPageQuery;
import br.com.espacotucuma.desktop.application.client.ClientSortField;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.port.client.ClientRepository;
import br.com.espacotucuma.desktop.application.port.professional.ProfessionalRepository;
import br.com.espacotucuma.desktop.application.port.service.ServiceRepository;
import br.com.espacotucuma.desktop.application.professional.Professional;
import br.com.espacotucuma.desktop.application.scheduling.Appointment;
import br.com.espacotucuma.desktop.application.scheduling.AppointmentStatus;
import br.com.espacotucuma.desktop.application.scheduling.MaintainAppointmentUseCase;
import br.com.espacotucuma.desktop.application.service.BeautyService;
import br.com.espacotucuma.desktop.application.service.ServicePageQuery;
import br.com.espacotucuma.desktop.application.service.ServiceSortField;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/** Offline-first local schedule for the reception desk. */
public final class AppointmentRegistryPane extends BorderPane {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Manaus");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private final MaintainAppointmentUseCase appointments;
  private final ClientRepository clients;
  private final ProfessionalRepository professionals;
  private final ServiceRepository services;
  private final AuthorizationService authorization;
  private final DatePicker date = new DatePicker(LocalDate.now(BUSINESS_ZONE));
  private final ComboBox<String> period = new ComboBox<>();
  private final ComboBox<ProfessionalChoice> professionalFilter = new ComboBox<>();
  private final ComboBox<String> statusFilter = new ComboBox<>();
  private final TableView<AppointmentRow> table = new TableView<>();
  private final Label availability = new Label();
  private final Label message = new Label();
  private final Button confirm = button("Confirmar", "appointmentConfirm");
  private final Button start = button("Iniciar atendimento", "appointmentStart");
  private final Button noShow = button("Não compareceu", "appointmentNoShow");
  private final Button reschedule = button("Remarcar", "appointmentReschedule");
  private final Button cancel = button("Cancelar", "appointmentCancel");
  private Runnable mutationListener = () -> {};

  public AppointmentRegistryPane(
      MaintainAppointmentUseCase appointments,
      ClientRepository clients,
      ProfessionalRepository professionals,
      ServiceRepository services,
      AuthorizationService authorization) {
    this.appointments = appointments;
    this.clients = clients;
    this.professionals = professionals;
    this.services = services;
    this.authorization = authorization;
    date.setId("appointmentDate");
    period.setId("appointmentPeriod");
    period.getItems().setAll("Dia", "Semana");
    period.getSelectionModel().selectFirst();
    professionalFilter.setId("appointmentProfessionalFilter");
    statusFilter.setId("appointmentStatusFilter");
    statusFilter
        .getItems()
        .setAll(
            "Todas",
            "Agendado",
            "Confirmado",
            "Em atendimento",
            "Concluído",
            "Cancelado",
            "Não compareceu");
    statusFilter.getSelectionModel().selectFirst();
    date.valueProperty().addListener((observable, oldValue, value) -> refresh());
    period.valueProperty().addListener((observable, oldValue, value) -> refresh());
    professionalFilter.valueProperty().addListener((observable, oldValue, value) -> refresh());
    statusFilter.valueProperty().addListener((observable, oldValue, value) -> refresh());

    Button previous = button("◀", "appointmentPrevious");
    Button today = button("Hoje", "appointmentToday");
    Button next = button("▶", "appointmentNext");
    previous.setOnAction(event -> shift(-1));
    next.setOnAction(event -> shift(1));
    today.setOnAction(event -> date.setValue(LocalDate.now(BUSINESS_ZONE)));
    Button create = button("Novo agendamento", "appointmentNew");
    create.setOnAction(event -> createAppointment());
    confirm.setOnAction(event -> confirmAppointment());
    start.setOnAction(event -> startAttendance());
    noShow.setOnAction(event -> markNoShow());
    reschedule.setOnAction(event -> rescheduleAppointment());
    cancel.setOnAction(event -> cancelAppointment());
    Button details = button("Detalhes", "appointmentDetails");
    details.setOnAction(event -> showDetails());
    table
        .getSelectionModel()
        .selectedItemProperty()
        .addListener((o, before, selected) -> updateActions());
    message.setId("appointmentMessage");
    availability.setId("appointmentAvailability");
    table.setId("appointmentTable");
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    table
        .getColumns()
        .setAll(
            column("Data", AppointmentRow::dateText, 95),
            column("Horário", AppointmentRow::timeText, 90),
            column("Cliente", AppointmentRow::clientName, 190),
            column("Profissional", AppointmentRow::professionalName, 165),
            column("Serviço", AppointmentRow::serviceName, 170),
            column("Situação", AppointmentRow::statusText, 115));

    HBox filters =
        new HBox(7, previous, today, next, date, period, professionalFilter, statusFilter);
    filters.setPadding(new Insets(8));
    HBox commands = new HBox(6, create, confirm, start, noShow, reschedule, cancel, details);
    commands.setPadding(new Insets(6, 8, 6, 8));
    availability.setWrapText(true);
    availability.setPadding(new Insets(7, 9, 7, 9));
    message.setPadding(new Insets(5, 9, 5, 9));
    setTop(new VBox(filters, commands));
    setCenter(table);
    setBottom(new VBox(availability, message));
    setPadding(new Insets(4));
    refreshProfessionals();
    refresh();
  }

  public void setMutationListener(Runnable listener) {
    mutationListener = listener == null ? () -> {} : listener;
  }

  public void refreshAccess() {
    boolean canRead = authorization.isAllowed(Permission.SCHEDULE_READ);
    boolean canManage = authorization.isAllowed(Permission.SCHEDULE_MANAGE);
    setDisable(!canRead);
    if (canRead) refresh();
    // Read-only users can still inspect the schedule while all write actions remain unavailable.
    if (!canRead && authorization.isAllowed(Permission.OWN_SCHEDULE_READ)) {
      setDisable(false);
      table.getItems().clear();
      message.setText(
          "A consulta da agenda própria será liberada na entrega de permissões por profissional.");
    }
    confirm.setDisable(
        !canManage
            || selected() == null
            || selected().appointment().status() != AppointmentStatus.AGENDADO);
    reschedule.setDisable(
        !canManage || selected() == null || !selected().appointment().occupiesTime());
    cancel.setDisable(!canManage || selected() == null || !selected().appointment().occupiesTime());
  }

  public void refresh() {
    if (!authorization.isAllowed(Permission.SCHEDULE_READ)) return;
    try {
      UUID professionalId =
          professionalFilter.getValue() == null ? null : professionalFilter.getValue().id();
      LocalDate first = date.getValue() == null ? LocalDate.now(BUSINESS_ZONE) : date.getValue();
      if ("Semana".equals(period.getValue()))
        first = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
      int count = "Semana".equals(period.getValue()) ? 7 : 1;
      List<AppointmentRow> rows = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        for (Appointment appointment :
            appointments.findForDay(first.plusDays(index), professionalId)) {
          if (!statusMatches(appointment.status())) continue;
          rows.add(
              new AppointmentRow(
                  appointment,
                  clients
                      .findById(appointment.clientId())
                      .map(Client::name)
                      .orElse("Cliente removido"),
                  professionalName(appointment.professionalId()),
                  services
                      .findById(appointment.serviceId())
                      .map(BeautyService::name)
                      .orElse("Serviço removido")));
        }
      }
      rows.sort(Comparator.comparing(row -> row.appointment().startsAt()));
      var unique =
          rows.stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      row -> row.appointment().id(),
                      row -> row,
                      (firstRow, duplicate) -> firstRow,
                      java.util.LinkedHashMap::new));
      table.getItems().setAll(unique.values());
      message.setText(
          "Horários locais de Manaus (America/Manaus). Alterações são salvas neste computador.");
      refreshAvailability(first, professionalId);
      updateActions();
    } catch (RuntimeException failure) {
      message.setText(
          failure.getMessage() == null
              ? "Não foi possível consultar a agenda local."
              : failure.getMessage());
    }
  }

  public TableView<AppointmentRow> appointmentTable() {
    return table;
  }

  public DatePicker datePicker() {
    return date;
  }

  public ComboBox<String> periodFilter() {
    return period;
  }

  public ComboBox<ProfessionalChoice> professionalFilter() {
    return professionalFilter;
  }

  private void refreshProfessionals() {
    List<ProfessionalChoice> choices = new ArrayList<>();
    choices.add(new ProfessionalChoice(null, "Todos os profissionais"));
    professionals.findActiveForScheduling().stream()
        .map(person -> new ProfessionalChoice(person.id(), person.name()))
        .forEach(choices::add);
    professionalFilter.setItems(FXCollections.observableArrayList(choices));
    professionalFilter.setConverter(
        new StringConverter<>() {
          @Override
          public String toString(ProfessionalChoice choice) {
            return choice == null ? "" : choice.name();
          }

          @Override
          public ProfessionalChoice fromString(String value) {
            return choices.stream()
                .filter(c -> c.name().equals(value))
                .findFirst()
                .orElse(choices.getFirst());
          }
        });
    professionalFilter.getSelectionModel().selectFirst();
  }

  private void refreshAvailability(LocalDate day, UUID professionalId) {
    if (professionalId == null) {
      availability.setText("Selecione um profissional para consultar horários livres.");
      return;
    }
    Professional person = professionals.findById(professionalId).orElse(null);
    if (person == null) {
      availability.setText("Profissional indisponível.");
      return;
    }
    List<String> free = new ArrayList<>();
    for (var shift : person.schedule().weeklyIntervals()) {
      if (shift.day() != day.getDayOfWeek()) continue;
      LocalTime start = shift.startsAt();
      while (!start.plusMinutes(30).isAfter(shift.endsAt())) {
        LocalDateTime from = LocalDateTime.of(day, start);
        LocalDateTime until = from.plusMinutes(30);
        boolean blocked =
            person.schedule().unavailabilities().stream().anyMatch(b -> b.overlaps(from, until));
        boolean occupied =
            appointments.findForDay(day, professionalId).stream()
                .filter(Appointment::occupiesTime)
                .anyMatch(a -> from.isBefore(a.endsAt()) && a.startsAt().isBefore(until));
        if (!blocked && !occupied) free.add(start.format(TIME));
        start = start.plusMinutes(30);
      }
    }
    availability.setText(
        "Disponibilidade (janelas de 30 min): "
            + (free.isEmpty() ? "sem horários livres" : String.join("  ·  ", free)));
  }

  private void shift(int amount) {
    if (date.getValue() == null) return;
    date.setValue(
        date.getValue().plusDays("Semana".equals(period.getValue()) ? amount * 7L : amount));
  }

  private boolean statusMatches(AppointmentStatus status) {
    String value = statusFilter.getValue();
    if (value == null || value.equals("Todas")) return true;
    return statusText(status).equals(value);
  }

  private String professionalName(UUID id) {
    return professionals.findById(id).map(Professional::name).orElse("Profissional removido");
  }

  private void createAppointment() {
    if (!authorization.isAllowed(Permission.SCHEDULE_MANAGE)) return;
    AppointmentForm form = new AppointmentForm("Novo agendamento", date.getValue(), null);
    form.showAndWait()
        .ifPresent(
            values ->
                run(
                    () ->
                        appointments.create(
                            values.client().id(),
                            values.professional().id(),
                            values.service().id(),
                            LocalDateTime.of(values.day(), values.time()),
                            values.notes(),
                            values.override(),
                            values.reason())));
  }

  private void rescheduleAppointment() {
    AppointmentRow row = selected();
    if (row == null || !authorization.isAllowed(Permission.SCHEDULE_MANAGE)) return;
    AppointmentForm form =
        new AppointmentForm(
            "Remarcar agendamento", row.appointment().startsAt().toLocalDate(), row);
    form.showAndWait()
        .ifPresent(
            values ->
                run(
                    () ->
                        appointments.reschedule(
                            row.appointment().id(),
                            values.professional().id(),
                            LocalDateTime.of(values.day(), values.time()),
                            values.override(),
                            values.reason())));
  }

  private void confirmAppointment() {
    AppointmentRow row = selected();
    if (row != null) run(() -> appointments.confirm(row.appointment().id()));
  }

  private void startAttendance() {
    AppointmentRow row = selected();
    if (row != null) run(() -> appointments.startAttendance(row.appointment().id()));
  }

  private void markNoShow() {
    AppointmentRow row = selected();
    if (row == null) return;
    Dialog<String> dialog = new Dialog<>();
    dialog.setTitle("Registrar ausência");
    dialog.setHeaderText("Informe o motivo da falta");
    TextField reason = new TextField();
    reason.setId("appointmentNoShowReason");
    dialog.getDialogPane().setContent(reason);
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    dialog.setResultConverter(button -> button == ButtonType.OK ? reason.getText() : null);
    dialog
        .showAndWait()
        .filter(text -> !text.isBlank())
        .ifPresent(text -> run(() -> appointments.markNoShow(row.appointment().id(), text)));
  }

  private void cancelAppointment() {
    AppointmentRow row = selected();
    if (row == null) return;
    Dialog<String> dialog = new Dialog<>();
    dialog.setTitle("Cancelar agendamento");
    dialog.setHeaderText("Informe o motivo do cancelamento");
    TextField reason = new TextField();
    reason.setId("appointmentCancelReason");
    dialog.getDialogPane().setContent(reason);
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    dialog.setResultConverter(button -> button == ButtonType.OK ? reason.getText() : null);
    dialog
        .showAndWait()
        .filter(text -> !text.isBlank())
        .ifPresent(text -> run(() -> appointments.cancel(row.appointment().id(), text)));
  }

  private void showDetails() {
    AppointmentRow row = selected();
    if (row == null) return;
    Appointment item = row.appointment();
    Alert alert = new Alert(Alert.AlertType.INFORMATION);
    alert.setTitle("Detalhes do agendamento");
    alert.setHeaderText(row.clientName() + " · " + row.serviceName());
    alert.setContentText(
        "Profissional: "
            + row.professionalName()
            + "\nData: "
            + row.dateText()
            + "\nHorário: "
            + row.timeText()
            + "\nSituação: "
            + statusText(item.status())
            + "\nObservações: "
            + (item.notes() == null ? "—" : item.notes()));
    alert.showAndWait();
  }

  private void run(Runnable action) {
    try {
      action.run();
      message.setText("Alteração salva localmente neste computador.");
      refresh();
      mutationListener.run();
    } catch (RuntimeException failure) {
      message.setText(
          failure.getMessage() == null
              ? "Não foi possível salvar o agendamento."
              : failure.getMessage());
    }
  }

  private AppointmentRow selected() {
    return table.getSelectionModel().getSelectedItem();
  }

  private void updateActions() {
    AppointmentRow row = selected();
    boolean canManage = authorization.isAllowed(Permission.SCHEDULE_MANAGE);
    confirm.setDisable(
        !canManage || row == null || row.appointment().status() != AppointmentStatus.AGENDADO);
    boolean pending =
        row != null
            && (row.appointment().status() == AppointmentStatus.AGENDADO
                || row.appointment().status() == AppointmentStatus.CONFIRMADO);
    start.setDisable(
        !authorization.isAllowed(Permission.ATTENDANCE_RECORD)
            || row == null
            || row.appointment().status() != AppointmentStatus.CONFIRMADO);
    noShow.setDisable(!canManage || !pending);
    reschedule.setDisable(!canManage || !pending);
    cancel.setDisable(!canManage || !pending);
  }

  private static Button button(String title, String id) {
    Button button = new Button(title);
    button.setId(id);
    return button;
  }

  private static <T> TableColumn<AppointmentRow, String> column(
      String title, java.util.function.Function<AppointmentRow, String> text, double width) {
    TableColumn<AppointmentRow, String> column = new TableColumn<>(title);
    column.setPrefWidth(width);
    column.setCellValueFactory(
        cell -> new javafx.beans.property.ReadOnlyStringWrapper(text.apply(cell.getValue())));
    return column;
  }

  public record ProfessionalChoice(UUID id, String name) {
    @Override
    public String toString() {
      return name;
    }
  }

  public record AppointmentRow(
      Appointment appointment, String clientName, String professionalName, String serviceName) {
    public String dateText() {
      return appointment.startsAt().toLocalDate().format(DATE);
    }

    public String timeText() {
      return appointment.startsAt().toLocalTime().format(TIME)
          + "–"
          + appointment.endsAt().toLocalTime().format(TIME);
    }

    public String statusText() {
      return AppointmentRegistryPane.statusText(appointment.status());
    }
  }

  private static String statusText(AppointmentStatus status) {
    return switch (status) {
      case AGENDADO -> "Agendado";
      case CONFIRMADO -> "Confirmado";
      case EM_ATENDIMENTO -> "Em atendimento";
      case CONCLUIDO -> "Concluído";
      case CANCELADO -> "Cancelado";
      case NAO_COMPARECEU -> "Não compareceu";
    };
  }

  private record FormValues(
      Client client,
      Professional professional,
      BeautyService service,
      LocalDate day,
      LocalTime time,
      String notes,
      boolean override,
      String reason) {}

  private final class AppointmentForm extends Dialog<FormValues> {
    AppointmentForm(String title, LocalDate initialDate, AppointmentRow existing) {
      setTitle(title);
      setHeaderText("Preencha os dados do horário local (Manaus)");
      ComboBox<Client> client = new ComboBox<>();
      client.setId("appointmentClient");
      client.setItems(
          FXCollections.observableArrayList(
              clients
                  .searchPage(
                      new ClientPageQuery(
                          "", ClientContactFilter.ALL, ClientSortField.NAME, true, 0, 100))
                  .items()));
      client.setConverter(converter(Client::name));
      ComboBox<Professional> professional = new ComboBox<>();
      professional.setId("appointmentProfessional");
      professional.setItems(
          FXCollections.observableArrayList(professionals.findActiveForScheduling()));
      professional.setConverter(converter(Professional::name));
      ComboBox<BeautyService> service = new ComboBox<>();
      service.setId("appointmentService");
      service.setItems(
          FXCollections.observableArrayList(
              services
                  .searchPage(
                      new ServicePageQuery("", "ALL", false, ServiceSortField.NAME, true, 0, 100))
                  .items()));
      service.setConverter(converter(BeautyService::name));
      DatePicker day = new DatePicker(initialDate);
      day.setId("appointmentFormDate");
      TextField time =
          new TextField(
              existing == null
                  ? "09:00"
                  : existing.appointment().startsAt().toLocalTime().format(TIME));
      time.setId("appointmentFormTime");
      TextArea notes = new TextArea(existing == null ? "" : existing.appointment().notes());
      notes.setId("appointmentFormNotes");
      notes.setPrefRowCount(2);
      javafx.scene.control.CheckBox override =
          new javafx.scene.control.CheckBox("Autorizar exceção administrativa de conflito");
      override.setId("appointmentOverride");
      override.setDisable(!authorization.isAllowed(Permission.MANAGE_USERS));
      TextField reason = new TextField();
      reason.setPromptText("Motivo obrigatório para exceção");
      reason.setId("appointmentOverrideReason");
      if (existing == null) client.getSelectionModel().selectFirst();
      else {
        client.setDisable(true);
        service.setDisable(true);
        client.getItems().stream()
            .filter(c -> c.id().equals(existing.appointment().clientId()))
            .findFirst()
            .ifPresent(client.getSelectionModel()::select);
        service.getItems().stream()
            .filter(s -> s.id().equals(existing.appointment().serviceId()))
            .findFirst()
            .ifPresent(service.getSelectionModel()::select);
      }
      if (existing == null) service.getSelectionModel().selectFirst();
      professional.getItems().stream()
          .filter(p -> existing != null && p.id().equals(existing.appointment().professionalId()))
          .findFirst()
          .ifPresent(professional.getSelectionModel()::select);
      if (professional.getValue() == null) professional.getSelectionModel().selectFirst();
      GridPane grid = new GridPane();
      grid.setHgap(8);
      grid.setVgap(7);
      grid.setPadding(new Insets(8));
      grid.addRow(0, new Label("Cliente"), client);
      grid.addRow(1, new Label("Profissional"), professional);
      grid.addRow(2, new Label("Serviço"), service);
      grid.addRow(3, new Label("Data"), day);
      grid.addRow(4, new Label("Início (HH:mm)"), time);
      grid.addRow(5, new Label("Observações"), notes);
      grid.add(override, 0, 6, 2, 1);
      grid.add(reason, 0, 7, 2, 1);
      getDialogPane().setContent(grid);
      getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
      setResultConverter(
          button -> {
            if (button != ButtonType.OK) return null;
            try {
              if (client.getValue() == null
                  || professional.getValue() == null
                  || service.getValue() == null
                  || day.getValue() == null)
                throw new IllegalArgumentException(
                    "Selecione cliente, profissional, serviço e data.");
              LocalTime start = LocalTime.parse(time.getText().strip(), TIME);
              return new FormValues(
                  client.getValue(),
                  professional.getValue(),
                  service.getValue(),
                  day.getValue(),
                  start,
                  notes.getText(),
                  override.isSelected(),
                  reason.getText());
            } catch (RuntimeException failure) {
              message.setText("Dados do horário inválidos: " + failure.getMessage());
              return null;
            }
          });
    }
  }

  private static <T> StringConverter<T> converter(java.util.function.Function<T, String> label) {
    return new StringConverter<>() {
      @Override
      public String toString(T item) {
        return item == null ? "" : label.apply(item);
      }

      @Override
      public T fromString(String text) {
        return null;
      }
    };
  }
}
