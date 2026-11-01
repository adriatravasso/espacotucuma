package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.attendance.Attendance;
import br.com.espacotucuma.desktop.application.attendance.AttendanceServiceLine;
import br.com.espacotucuma.desktop.application.attendance.MaintainAttendanceUseCase;
import br.com.espacotucuma.desktop.application.commission.CommissionCalculator;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.service.BeautyService;
import br.com.espacotucuma.desktop.domain.money.Money;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

final class AttendanceDialog extends Dialog<Attendance> {
  private final MaintainAttendanceUseCase attendances;
  private final Attendance attendance;
  private final AuthorizationService authorization;
  private final Runnable mutationListener;
  private final TableView<AttendanceServiceLine> services = new TableView<>();
  private final ComboBox<BeautyService> serviceChoice = new ComboBox<>();
  private final TextField discount = new TextField();
  private final TextField discountReason = new TextField();
  private final TextArea notes = new TextArea();
  private final Label subtotalLabel = new Label();
  private final Label totalLabel = new Label();
  private final Label commissionLabel = new Label();
  private final Label message = new Label();

  AttendanceDialog(
      MaintainAttendanceUseCase attendances,
      Attendance attendance,
      String clientName,
      String professionalName,
      AuthorizationService authorization,
      int availablePackages,
      Runnable mutationListener) {
    this.attendances = attendances;
    this.attendance = attendance;
    this.authorization = authorization;
    this.mutationListener = mutationListener;
    setTitle("Atendimento — Espaço Tucumã");
    setHeaderText(clientName + " · " + professionalName);

    serviceChoice.setId("attendanceServiceChoice");
    serviceChoice.setItems(FXCollections.observableArrayList(attendances.availableServices()));
    serviceChoice.setConverter(converter(BeautyService::name));
    serviceChoice.getSelectionModel().selectFirst();
    Button addService = new Button("Adicionar serviço");
    addService.setId("attendanceAddService");
    addService.setOnAction(
        event -> {
          BeautyService selected = serviceChoice.getValue();
          if (selected == null) {
            message.setText("Selecione um serviço.");
            return;
          }
          try {
            attendances.addService(attendance.id(), selected.id(), 1);
            message.setText("Serviço salvo localmente com preço e comissão congelados.");
            mutationListener.run();
            refresh();
          } catch (RuntimeException failure) {
            showFailure(failure);
          }
        });

    services.setId("attendanceServices");
    services.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    services
        .getColumns()
        .setAll(
            column("Serviço", AttendanceServiceLine::nameSnapshot, 190),
            column("Qtd.", line -> Integer.toString(line.quantity()), 55),
            column("Preço unitário", line -> money(line.unitPrice()), 100),
            column(
                "Comissão",
                line ->
                    String.format(
                        Locale.forLanguageTag("pt-BR"),
                        "%.2f%%",
                        line.commission().percentageBasisPoints() / 100.0),
                90));
    discount.setId("attendanceDiscount");
    discount.setPromptText("0,00");
    discountReason.setId("attendanceDiscountReason");
    discountReason.setPromptText("Motivo obrigatório");
    discount.setDisable(!authorization.isAllowed(Permission.DISCOUNT_MANAGE));
    discountReason.setDisable(!authorization.isAllowed(Permission.DISCOUNT_MANAGE));
    Button applyDiscount = new Button("Aplicar desconto");
    applyDiscount.setId("attendanceApplyDiscount");
    applyDiscount.setDisable(!authorization.isAllowed(Permission.DISCOUNT_MANAGE));
    applyDiscount.setOnAction(
        event -> {
          try {
            attendances.applyDiscount(
                attendance.id(), parseMoney(discount.getText()), discountReason.getText());
            message.setText("Desconto autorizado e salvo localmente.");
            mutationListener.run();
            refresh();
          } catch (RuntimeException failure) {
            showFailure(failure);
          }
        });
    notes.setId("attendanceNotes");
    notes.setPrefRowCount(2);
    notes.setText(attendance.notes() == null ? "" : attendance.notes());
    Button saveNotes = new Button("Salvar observações");
    saveNotes.setId("attendanceSaveNotes");
    saveNotes.setOnAction(
        event -> {
          try {
            attendances.updateNotes(attendance.id(), notes.getText());
            message.setText("Observações salvas localmente.");
            mutationListener.run();
            refresh();
          } catch (RuntimeException failure) {
            showFailure(failure);
          }
        });

    Label packageStatus =
        new Label(
            availablePackages == 0
                ? "Pacotes: sem saldo ativo para este cliente."
                : "Pacotes: "
                    + availablePackages
                    + " com saldo ativo; consumo do pacote ainda pendente.");
    packageStatus.setId("attendancePackageStatus");
    subtotalLabel.setId("attendanceSubtotal");
    commissionLabel.setId("attendanceCommission");
    message.setId("attendanceMessage");
    HBox addRow = new HBox(7, serviceChoice, addService);
    HBox discountRow = new HBox(7, discount, discountReason, applyDiscount);
    totalLabel.setId("attendanceTotal");
    HBox summary = new HBox(18, subtotalLabel, totalLabel, commissionLabel);
    GridPane detailGrid = new GridPane();
    detailGrid.setHgap(8);
    detailGrid.setVgap(6);
    detailGrid.add(new Label("Desconto autorizado"), 0, 0);
    detailGrid.add(discountRow, 1, 0);
    detailGrid.add(new Label("Observações administrativas"), 0, 1);
    detailGrid.add(notes, 1, 1);
    detailGrid.add(saveNotes, 1, 2);
    VBox content = new VBox(8, addRow, services, detailGrid, summary, packageStatus, message);
    content.setPadding(new Insets(8));
    getDialogPane().setContent(content);

    ButtonType closeType = new ButtonType("Fechar", ButtonBar.ButtonData.CANCEL_CLOSE);
    ButtonType completeType = new ButtonType("Concluir atendimento", ButtonBar.ButtonData.OK_DONE);
    getDialogPane().getButtonTypes().setAll(closeType, completeType);
    Button complete = (Button) getDialogPane().lookupButton(completeType);
    complete.setId("attendanceComplete");
    complete.addEventFilter(
        javafx.event.ActionEvent.ACTION,
        event -> {
          try {
            Attendance result = attendances.complete(attendance.id());
            setResult(result);
            mutationListener.run();
          } catch (RuntimeException failure) {
            showFailure(failure);
            event.consume();
          }
        });
    setResultConverter(button -> button == completeType ? getResult() : null);
    getDialogPane().setPrefWidth(880);
    refresh();
  }

  private void refresh() {
    List<AttendanceServiceLine> lines = attendances.findServices(attendance.id());
    services.getItems().setAll(lines);
    Attendance current = attendances.findById(attendance.id());
    discount.setText(current.discount().amount().toPlainString().replace('.', ','));
    notes.setText(current.notes() == null ? "" : current.notes());
    subtotalLabel.setText("Subtotal: " + money(current.subtotal()));
    totalLabel.setText("Total do serviço: " + money(current.total()));
    java.math.BigDecimal commission =
        lines.stream()
            .map(
                line ->
                    CommissionCalculator.calculate(
                            line.total(), Money.of("0.00"), line.commission())
                        .amount())
            .reduce(java.math.BigDecimal.ZERO.setScale(2), java.math.BigDecimal::add);
    commissionLabel.setText(
        "Comissão prevista antes do desconto: R$ " + commission.toPlainString());
  }

  private void showFailure(RuntimeException failure) {
    message.setText(
        failure.getMessage() == null
            ? "Não foi possível salvar o atendimento."
            : failure.getMessage());
  }

  private static Money parseMoney(String input) {
    String normalized = input == null ? "" : input.strip().replace(".", "").replace(',', '.');
    if (normalized.isBlank()) normalized = "0.00";
    return new Money(new java.math.BigDecimal(normalized).setScale(2, RoundingMode.UNNECESSARY));
  }

  private static String money(Money money) {
    return "R$ " + money.amount().toPlainString().replace('.', ',');
  }

  private static <T> StringConverter<T> converter(Function<T, String> label) {
    return new StringConverter<>() {
      @Override
      public String toString(T value) {
        return value == null ? "" : label.apply(value);
      }

      @Override
      public T fromString(String text) {
        return null;
      }
    };
  }

  private static <T> TableColumn<AttendanceServiceLine, String> column(
      String title, Function<AttendanceServiceLine, String> value, double width) {
    TableColumn<AttendanceServiceLine, String> column = new TableColumn<>(title);
    column.setPrefWidth(width);
    column.setCellValueFactory(
        cell -> new javafx.beans.property.ReadOnlyStringWrapper(value.apply(cell.getValue())));
    return column;
  }
}
