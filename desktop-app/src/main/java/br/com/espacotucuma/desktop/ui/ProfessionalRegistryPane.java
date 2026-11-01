package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.port.professional.ProfessionalRepository;
import br.com.espacotucuma.desktop.application.professional.MaintainProfessionalUseCase;
import br.com.espacotucuma.desktop.application.professional.OneOffUnavailability;
import br.com.espacotucuma.desktop.application.professional.Professional;
import br.com.espacotucuma.desktop.application.professional.ProfessionalPageQuery;
import br.com.espacotucuma.desktop.application.professional.ProfessionalSchedule;
import br.com.espacotucuma.desktop.application.professional.WeeklyInterval;
import br.com.espacotucuma.desktop.ui.components.PagedTablePane;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Local professional directory with weekly shifts and one-off absences. */
public final class ProfessionalRegistryPane extends javafx.scene.layout.BorderPane {
  private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private final ProfessionalRepository professionals;
  private final MaintainProfessionalUseCase maintain;
  private final AuthorizationService authorization;
  private final AtomicBoolean includeInactive = new AtomicBoolean();
  private final PagedTablePane<Professional> table;
  private final CheckBox showInactive = new CheckBox("Mostrar inativos");
  private final Button deactivate = new Button("Inativar");

  public ProfessionalRegistryPane(
      ProfessionalRepository professionals,
      MaintainProfessionalUseCase maintain,
      AuthorizationService authorization) {
    this.professionals = Objects.requireNonNull(professionals, "professionals");
    this.maintain = Objects.requireNonNull(maintain, "maintain");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    table = createTable();
    showInactive.setId("professionalShowInactive");
    showInactive.setOnAction(
        event -> {
          includeInactive.set(showInactive.isSelected());
          table.refresh();
        });
    deactivate.setId("professionalDeactivate");
    deactivate.setDisable(true);
    table.addSelectionListener(
        selected ->
            deactivate.setDisable(
                selected == null
                    || !selected.active()
                    || !authorization.isAllowed(Permission.PROFESSIONAL_DEACTIVATE)));
    table.setEditAvailability(Professional::active);
    deactivate.setOnAction(event -> deactivate(table.selectedItem()));
    HBox actions =
        new HBox(6, new Label("Equipe, jornadas e afastamentos"), showInactive, deactivate);
    actions.setPadding(new Insets(8));
    setTop(actions);
    setCenter(table);
    refreshAccess();
  }

  public void refreshAccess() {
    boolean canRead = authorization.isAllowed(Permission.PROFESSIONAL_READ);
    table.setNewActionEnabled(canRead && authorization.isAllowed(Permission.PROFESSIONAL_REGISTER));
    table.setEditActionEnabled(canRead && authorization.isAllowed(Permission.PROFESSIONAL_UPDATE));
    showInactive.setDisable(!canRead);
    deactivate.setDisable(true);
    if (canRead) {
      setCenter(table);
      table.refresh();
    } else {
      setCenter(new Label("Seu perfil não tem permissão para consultar profissionais."));
    }
  }

  private PagedTablePane<Professional> createTable() {
    return new PagedTablePane<>(
        columns(),
        Map.of("ALL", "Todos"),
        Map.of("NAME", "Nome"),
        25,
        request -> {
          authorization.require(Permission.PROFESSIONAL_READ);
          return professionals.searchPage(
              new ProfessionalPageQuery(
                  request.searchText(),
                  includeInactive.get(),
                  request.pageIndex(),
                  request.pageSize()));
        },
        this::create,
        this::edit);
  }

  private List<TableColumn<Professional, ?>> columns() {
    TableColumn<Professional, String> name = new TableColumn<>("Profissional");
    name.setCellValueFactory(
        row -> new javafx.beans.property.ReadOnlyStringWrapper(row.getValue().name()));
    TableColumn<Professional, String> phone = new TableColumn<>("Telefone");
    phone.setCellValueFactory(
        row ->
            new javafx.beans.property.ReadOnlyStringWrapper(
                row.getValue().phone() == null ? "" : row.getValue().phone()));
    TableColumn<Professional, String> shifts = new TableColumn<>("Jornada semanal");
    shifts.setCellValueFactory(
        row ->
            new javafx.beans.property.ReadOnlyStringWrapper(
                row.getValue().schedule().weeklyIntervals().size() + " períodos"));
    TableColumn<Professional, String> status = new TableColumn<>("Situação");
    status.setCellValueFactory(
        row ->
            new javafx.beans.property.ReadOnlyStringWrapper(
                row.getValue().active() ? "Ativo" : "Inativo"));
    return List.of(name, phone, shifts, status);
  }

  private boolean create() {
    if (!authorization.isAllowed(Permission.PROFESSIONAL_REGISTER)) return false;
    ProfessionalForm form = showForm(null);
    if (form == null) return false;
    try {
      maintain.register(form.name(), form.phone(), form.schedule());
      table.refresh();
      return true;
    } catch (RuntimeException failure) {
      showError("Não foi possível cadastrar o profissional.", failure.getMessage());
      return false;
    }
  }

  private boolean edit(Professional current) {
    if (!authorization.isAllowed(Permission.PROFESSIONAL_UPDATE)) return false;
    ProfessionalForm form = showForm(current);
    if (form == null) return false;
    try {
      maintain.update(current.id(), form.name(), form.phone(), form.schedule());
      table.refresh();
      return true;
    } catch (RuntimeException failure) {
      showError("Não foi possível atualizar o profissional.", failure.getMessage());
      return false;
    }
  }

  private void deactivate(Professional selected) {
    if (selected == null || !selected.active()) return;
    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
    confirm.setTitle("Inativar profissional");
    confirm.setHeaderText("Inativar " + selected.name() + "?");
    confirm.setContentText(
        "A pessoa deixará de receber novos agendamentos. Atendimentos e comissões anteriores serão preservados.");
    confirm.getDialogPane().lookupButton(ButtonType.OK).setId("professionalDeactivateConfirm");
    TucumaTheme.apply(confirm.getDialogPane());
    confirm
        .showAndWait()
        .filter(ButtonType.OK::equals)
        .ifPresent(
            button -> {
              try {
                maintain.deactivate(selected.id());
                table.refresh();
              } catch (RuntimeException failure) {
                showError("Não foi possível inativar o profissional.", failure.getMessage());
              }
            });
  }

  private ProfessionalForm showForm(Professional current) {
    Window owner = getScene() == null ? null : getScene().getWindow();
    Dialog<ProfessionalForm> dialog = new Dialog<>();
    dialog.setTitle(
        "Tucumã — Gestão — " + (current == null ? "Novo profissional" : "Editar profissional"));
    if (owner != null) dialog.initOwner(owner);
    ButtonType save = new ButtonType("Salvar", ButtonBar.ButtonData.OK_DONE);
    dialog
        .getDialogPane()
        .getButtonTypes()
        .addAll(save, new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE));

    TextField name = new TextField(current == null ? "" : current.name());
    name.setId("professionalName");
    TextField phone =
        new TextField(current == null || current.phone() == null ? "" : current.phone());
    phone.setId("professionalPhone");
    GridPane basic = new GridPane();
    basic.setHgap(8);
    basic.setVgap(8);
    basic.setPadding(new Insets(10));
    basic.addRow(0, new Label("Nome *"), name);
    basic.addRow(1, new Label("Telefone"), phone);

    Map<DayOfWeek, TextField> dayInputs = new LinkedHashMap<>();
    GridPane schedule = new GridPane();
    schedule.setHgap(8);
    schedule.setVgap(6);
    schedule.setPadding(new Insets(10));
    schedule.addRow(
        0, new Label("Dia"), new Label("Períodos (HH:mm-HH:mm, separados por vírgula)"));
    DayOfWeek[] days = {
      DayOfWeek.MONDAY,
      DayOfWeek.TUESDAY,
      DayOfWeek.WEDNESDAY,
      DayOfWeek.THURSDAY,
      DayOfWeek.FRIDAY,
      DayOfWeek.SATURDAY,
      DayOfWeek.SUNDAY
    };
    for (int i = 0; i < days.length; i++) {
      DayOfWeek day = days[i];
      TextField periods = new TextField(weeklyText(current, day));
      periods.setId("professionalDay-" + day.name().toLowerCase(java.util.Locale.ROOT));
      periods.setPromptText("09:00-12:00, 13:00-18:00");
      dayInputs.put(day, periods);
      schedule.addRow(i + 1, new Label(dayName(day)), periods);
    }
    TextArea blocks = new TextArea(absenceText(current));
    blocks.setId("professionalAbsences");
    blocks.setPromptText(
        "Uma linha por afastamento: 2026-11-10 09:00 | 2026-11-10 12:00 | Motivo opcional");
    blocks.setPrefRowCount(4);
    VBox scheduleContent =
        new VBox(8, schedule, new Label("Afastamentos pontuais (uma linha por período)"), blocks);
    scheduleContent.setPadding(new Insets(8));

    Label commissionInfo =
        new Label("As regras atuais seguem o percentual configurado em cada serviço.");
    commissionInfo.setWrapText(true);
    commissionInfo.setPadding(new Insets(10));
    Tab basicTab = new Tab("Dados básicos", basic);
    basicTab.setClosable(false);
    Tab scheduleTab = new Tab("Horários", scheduleContent);
    scheduleTab.setClosable(false);
    Tab commissionTab = new Tab("Comissões", commissionInfo);
    commissionTab.setClosable(false);
    boolean commissionAccess = authorization.isAllowed(Permission.PROFESSIONAL_COMMISSION_MANAGE);
    commissionTab.setDisable(!commissionAccess);
    commissionTab.setTooltip(
        new javafx.scene.control.Tooltip(
            commissionAccess
                ? "A edição dos percentuais é restrita ao cadastro de serviços."
                : "Somente administradores podem consultar regras de comissão."));
    TabPane tabs = new TabPane(basicTab, scheduleTab, commissionTab);
    Label validation = new Label();
    validation.setId("professionalFormMessage");
    validation.setWrapText(true);
    VBox content = new VBox(6, tabs, validation);
    content.setPadding(new Insets(6));
    dialog.getDialogPane().setContent(content);
    dialog.getDialogPane().setPrefWidth(680);
    dialog.getDialogPane().setPrefHeight(540);
    TucumaTheme.apply(dialog.getDialogPane());
    javafx.scene.Node saveButton = dialog.getDialogPane().lookupButton(save);
    saveButton.addEventFilter(
        javafx.event.ActionEvent.ACTION,
        event -> {
          try {
            toForm(name, phone, dayInputs, blocks);
            validation.setText("");
          } catch (RuntimeException invalid) {
            validation.setText(invalid.getMessage());
            event.consume();
          }
        });
    dialog.setResultConverter(
        button -> button == save ? toForm(name, phone, dayInputs, blocks) : null);
    return dialog.showAndWait().orElse(null);
  }

  private ProfessionalForm toForm(
      TextField name, TextField phone, Map<DayOfWeek, TextField> days, TextArea blocks) {
    if (name.getText().isBlank())
      throw new IllegalArgumentException("Informe o nome do profissional.");
    List<WeeklyInterval> intervals = new ArrayList<>();
    for (Map.Entry<DayOfWeek, TextField> entry : days.entrySet()) {
      String value = entry.getValue().getText().strip();
      if (value.isEmpty()) continue;
      for (String period : value.split(",")) {
        String[] bounds = period.strip().split("-", -1);
        if (bounds.length != 2) {
          throw new IllegalArgumentException("Use HH:mm-HH:mm para cada período semanal.");
        }
        intervals.add(
            new WeeklyInterval(
                entry.getKey(),
                LocalTime.parse(bounds[0].strip()),
                LocalTime.parse(bounds[1].strip())));
      }
    }
    List<OneOffUnavailability> absences = new ArrayList<>();
    for (String line :
        blocks.getText().lines().map(String::strip).filter(v -> !v.isEmpty()).toList()) {
      String[] parts = line.split("\\|", -1);
      if (parts.length < 2 || parts.length > 3) {
        throw new IllegalArgumentException("Use início | fim | motivo em cada afastamento.");
      }
      absences.add(
          new OneOffUnavailability(
              LocalDateTime.parse(parts[0].strip(), LOCAL),
              LocalDateTime.parse(parts[1].strip(), LOCAL),
              parts.length == 3 ? parts[2].strip() : null));
    }
    return new ProfessionalForm(
        name.getText(), phone.getText(), new ProfessionalSchedule(intervals, absences));
  }

  private String weeklyText(Professional professional, DayOfWeek day) {
    if (professional == null) return "";
    return professional.schedule().weeklyIntervals().stream()
        .filter(interval -> interval.day() == day)
        .map(interval -> interval.startsAt() + "-" + interval.endsAt())
        .collect(java.util.stream.Collectors.joining(", "));
  }

  private String absenceText(Professional professional) {
    if (professional == null) return "";
    return professional.schedule().unavailabilities().stream()
        .map(
            block ->
                LOCAL.format(block.startsAt())
                    + " | "
                    + LOCAL.format(block.endsAt())
                    + " | "
                    + (block.reason() == null ? "" : block.reason()))
        .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
  }

  private String dayName(DayOfWeek day) {
    return switch (day) {
      case MONDAY -> "Segunda";
      case TUESDAY -> "Terça";
      case WEDNESDAY -> "Quarta";
      case THURSDAY -> "Quinta";
      case FRIDAY -> "Sexta";
      case SATURDAY -> "Sábado";
      case SUNDAY -> "Domingo";
    };
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

  private record ProfessionalForm(String name, String phone, ProfessionalSchedule schedule) {}
}
