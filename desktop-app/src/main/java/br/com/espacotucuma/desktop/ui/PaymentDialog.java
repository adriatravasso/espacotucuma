package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.finance.Payment;
import br.com.espacotucuma.desktop.application.finance.PaymentMethod;
import br.com.espacotucuma.desktop.application.finance.PaymentRefund;
import br.com.espacotucuma.desktop.application.finance.ReceivableSummary;
import br.com.espacotucuma.desktop.application.finance.RefundPaymentUseCase;
import br.com.espacotucuma.desktop.application.finance.RegisterPaymentUseCase;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
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
  private final RefundPaymentUseCase refunds;
  private final AuthorizationService authorization;
  private final UUID attendanceId;
  private final Runnable mutationListener;
  private final Label total = new Label();
  private final Label received = new Label();
  private final Label outstanding = new Label();
  private final Label state = new Label();
  private final Label message = new Label();
  private final TextField amount = new TextField();
  private final TextField refundAmount = new TextField();
  private final TextField refundReason = new TextField();
  private final ComboBox<PaymentMethod> method = new ComboBox<>();
  private final TableView<Payment> history = new TableView<>();
  private final TableView<PaymentRefund> refundHistory = new TableView<>();
  private String idempotencyKey = UUID.randomUUID().toString();
  private String refundIdempotencyKey = UUID.randomUUID().toString();

  PaymentDialog(
      RegisterPaymentUseCase payments,
      RefundPaymentUseCase refunds,
      AuthorizationService authorization,
      UUID attendanceId,
      String clientName,
      String professionalName,
      Runnable mutationListener) {
    this.payments = payments;
    this.refunds = refunds;
    this.authorization = authorization;
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
    refundHistory.setId("paymentRefundHistory");
    refundHistory.setPrefHeight(110);
    refundHistory
        .getColumns()
        .setAll(
            refundColumn(
                "Estornado",
                item -> money(new Money(BigDecimal.valueOf(item.amountCents(), 2))),
                100),
            refundColumn("Motivo", PaymentRefund::reason, 220),
            refundColumn("Responsável", item -> item.refundedBy().toString(), 250),
            refundColumn("Data", item -> RECEIVED_AT.format(item.refundedAt()), 150));
    history
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (observable, oldValue, value) -> {
              if (value != null) {
                refundAmount.setText(money(value.amount()));
                refundHistory
                    .getItems()
                    .setAll(refunds == null ? java.util.List.of() : refunds.history(value.id()));
              }
            });
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
    refundAmount.setId("refundAmount");
    refundReason.setId("refundReason");
    refundAmount.setPromptText("Valor do estorno");
    refundReason.setPromptText("Motivo obrigatório");
    GridPane refundFields = new GridPane();
    refundFields.setHgap(8);
    refundFields.setVgap(6);
    refundFields.add(new Label("Valor a estornar"), 0, 0);
    refundFields.add(refundAmount, 1, 0);
    refundFields.add(new Label("Motivo"), 2, 0);
    refundFields.add(refundReason, 3, 0);
    HBox summary = new HBox(16, total, received, outstanding, state);
    VBox content =
        new VBox(
            8,
            summary,
            new Label("Recebimentos"),
            history,
            fields,
            refundFields,
            new Label("Histórico de estornos: valor, motivo e responsável"),
            refundHistory,
            message);
    content.setPadding(new Insets(8));
    getDialogPane().setContent(content);
    ButtonType closeType = new ButtonType("Fechar", ButtonBar.ButtonData.CANCEL_CLOSE);
    ButtonType addType = new ButtonType("Registrar parcela", ButtonBar.ButtonData.OTHER);
    ButtonType refundType = new ButtonType("Estornar selecionado", ButtonBar.ButtonData.OTHER);
    getDialogPane().getButtonTypes().setAll(closeType, refundType, addType);
    Button refund = (Button) getDialogPane().lookupButton(refundType);
    refund.setId("paymentRefund");
    refund.setDisable(refunds == null || !authorization.isAllowed(Permission.FINANCIAL_REVERSAL));
    refund.addEventFilter(
        javafx.event.ActionEvent.ACTION,
        event -> {
          try {
            Payment selected = history.getSelectionModel().getSelectedItem();
            if (selected == null)
              throw new IllegalArgumentException("Selecione um recebimento para estornar.");
            long cents =
                parseMoney(refundAmount.getText()).amount().movePointRight(2).longValueExact();
            refunds.refund(selected.id(), cents, refundReason.getText(), refundIdempotencyKey);
            refundIdempotencyKey = UUID.randomUUID().toString();
            refundReason.clear();
            message.setText("Estorno registrado no histórico e no caixa.");
            mutationListener.run();
            refresh();
          } catch (RuntimeException failure) {
            message.setText(
                failure.getMessage() == null
                    ? "Não foi possível registrar o estorno."
                    : failure.getMessage());
          }
          event.consume();
        });
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
        (Button) getDialogPane().lookupButton(getDialogPane().getButtonTypes().get(2));
    register.setDisable(summary.settled());
    Button refund = (Button) getDialogPane().lookupButton(getDialogPane().getButtonTypes().get(1));
    Payment selected = history.getSelectionModel().getSelectedItem();
    refund.setDisable(
        refunds == null
            || !authorization.isAllowed(Permission.FINANCIAL_REVERSAL)
            || selected == null
            || selected.amount().amount().movePointRight(2).longValueExact() <= 0);
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

  private static TableColumn<PaymentRefund, String> refundColumn(
      String title, Function<PaymentRefund, String> value, double width) {
    TableColumn<PaymentRefund, String> column = new TableColumn<>(title);
    column.setPrefWidth(width);
    column.setCellValueFactory(
        cell -> new javafx.beans.property.ReadOnlyStringWrapper(value.apply(cell.getValue())));
    return column;
  }
}
