package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.finance.Payment;
import br.com.espacotucuma.desktop.application.finance.PaymentMethod;
import br.com.espacotucuma.desktop.application.finance.ReceivableSummary;
import br.com.espacotucuma.desktop.application.finance.RegisterPaymentUseCase;
import br.com.espacotucuma.desktop.domain.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
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
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

final class PaymentDialog extends Dialog<Void> {
  private static final DateTimeFormatter RECEIVED_AT =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Manaus"));
  private final RegisterPaymentUseCase payments;
  private final UUID attendanceId;
  private final Runnable mutationListener;
  private final Label total = new Label();
  private final Label received = new Label();
  private final Label outstanding = new Label();
  private final Label state = new Label();
  private final Label message = new Label();
  private final TextField amount = new TextField();
  private final ComboBox<PaymentMethod> method = new ComboBox<>();
  private final TableView<Payment> history = new TableView<>();
  private String idempotencyKey = UUID.randomUUID().toString();

  PaymentDialog(
      RegisterPaymentUseCase payments,
      UUID attendanceId,
      String clientName,
      String professionalName,
      Runnable mutationListener) {
    this.payments = payments;
    this.attendanceId = attendanceId;
    this.mutationListener = mutationListener;
    setTitle("Recebimentos — Espaço Tucumã");
    setHeaderText(clientName + " · " + professionalName);

    total.setId("receivableTotal");
    received.setId("receivableReceived");
    outstanding.setId("receivableOutstanding");
    state.setId("receivableState");
    message.setId("paymentMessage");
    amount.setId("paymentAmount");
    amount.setPromptText("0,00");
    method.setId("paymentMethod");
    method.setItems(FXCollections.observableArrayList(PaymentMethod.values()));
    method.getSelectionModel().select(PaymentMethod.PIX);
    history.setId("paymentHistory");
    history.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    history
        .getColumns()
        .setAll(
            column("Forma", item -> item.method().toString(), 120),
            column("Valor", item -> money(item.amount()), 100),
            column("Recebido em", item -> RECEIVED_AT.format(item.receivedAt()), 160));

    GridPane fields = new GridPane();
    fields.setHgap(8);
    fields.setVgap(6);
    fields.add(new Label("Valor recebido"), 0, 0);
    fields.add(amount, 1, 0);
    fields.add(new Label("Forma"), 2, 0);
    fields.add(method, 3, 0);
    HBox summary = new HBox(16, total, received, outstanding, state);
    VBox content =
        new VBox(8, summary, new Label("Parcelas registradas"), history, fields, message);
    content.setPadding(new Insets(8));
    getDialogPane().setContent(content);
    ButtonType closeType = new ButtonType("Fechar", ButtonBar.ButtonData.CANCEL_CLOSE);
    ButtonType addType = new ButtonType("Registrar parcela", ButtonBar.ButtonData.OTHER);
    getDialogPane().getButtonTypes().setAll(closeType, addType);
    Button add = (Button) getDialogPane().lookupButton(addType);
    add.setId("paymentRegister");
    add.addEventFilter(
        javafx.event.ActionEvent.ACTION,
        event -> {
          try {
            payments.register(
                attendanceId, method.getValue(), parseMoney(amount.getText()), idempotencyKey);
            idempotencyKey = UUID.randomUUID().toString();
            amount.clear();
            message.setText("Parcela registrada localmente e lançada no caixa.");
            mutationListener.run();
            refresh();
          } catch (RuntimeException failure) {
            message.setText(
                failure.getMessage() == null
                    ? "Não foi possível registrar o recebimento."
                    : failure.getMessage());
          }
          event.consume();
        });
    getDialogPane().setPrefWidth(780);
    refresh();
  }

  private void refresh() {
    ReceivableSummary summary = payments.summary(attendanceId);
    total.setText("Total: " + money(summary.total()));
    received.setText("Recebido: " + money(summary.received()));
    outstanding.setText("Saldo pendente: " + money(summary.outstanding()));
    state.setText(summary.settled() ? "QUITADO" : "EM ABERTO");
    history.getItems().setAll(payments.findForAttendance(attendanceId));
    Button register =
        (Button) getDialogPane().lookupButton(getDialogPane().getButtonTypes().get(1));
    register.setDisable(summary.settled());
  }

  private static Money parseMoney(String input) {
    String normalized = input == null ? "" : input.strip().replace(".", "").replace(',', '.');
    if (normalized.isBlank()) throw new IllegalArgumentException("Informe o valor da parcela.");
    return new Money(new BigDecimal(normalized).setScale(2, RoundingMode.UNNECESSARY));
  }

  private static String money(Money value) {
    return "R$ " + value.amount().toPlainString().replace('.', ',');
  }

  private static TableColumn<Payment, String> column(
      String title, Function<Payment, String> value, double width) {
    TableColumn<Payment, String> column = new TableColumn<>(title);
    column.setPrefWidth(width);
    column.setCellValueFactory(
        cell -> new javafx.beans.property.ReadOnlyStringWrapper(value.apply(cell.getValue())));
    return column;
  }
}
