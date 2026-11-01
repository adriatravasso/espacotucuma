package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.finance.CashMovement;
import br.com.espacotucuma.desktop.application.finance.CashMovementType;
import br.com.espacotucuma.desktop.application.finance.CashReconciliation;
import br.com.espacotucuma.desktop.application.finance.CashTurn;
import br.com.espacotucuma.desktop.application.finance.ManageCashTurnUseCase;
import br.com.espacotucuma.desktop.application.finance.PaymentMethod;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/** Compact offline view for a single operator cash turn. */
public final class CashRegisterPane extends BorderPane {
  private final ManageCashTurnUseCase cash;
  private final AuthorizationService authorization;
  private final Label state = new Label();
  private final Label summary = new Label();
  private final Label message = new Label();
  private final TextField opening = field("cashOpeningAmount");
  private final ChoiceBox<CashMovementType> movementType = new ChoiceBox<>();
  private final TextField movementAmount = field("cashMovementAmount");
  private final TextField movementReason = field("cashMovementReason");
  private final TextField countCash = field("cashCountCash");
  private final TextField countPix = field("cashCountPix");
  private final TextField countCard = field("cashCountCard");
  private final TextField closeReason = field("cashCloseReason");
  private final Button openButton = new Button("Abrir caixa");
  private final Button movementButton = new Button("Lançar movimento");
  private final Button closeButton = new Button("Fechar e conferir");
  private final Button reopenButton = new Button("Reabrir caixa");
  private final TableView<CashMovement> movementTable = new TableView<>();
  private UUID displayedTurnId;

  public CashRegisterPane(ManageCashTurnUseCase cash, AuthorizationService authorization) {
    this.cash = cash;
    this.authorization = authorization;
    movementType.setItems(
        FXCollections.observableArrayList(
            CashMovementType.SANGRIA, CashMovementType.SUPRIMENTO, CashMovementType.SAIDA));
    movementType.getSelectionModel().selectFirst();
    movementType.setId("cashMovementType");
    openButton.setId("cashOpen");
    movementButton.setId("cashMovementSave");
    closeButton.setId("cashClose");
    reopenButton.setId("cashReopen");
    openButton.setOnAction(event -> perform(this::open));
    movementButton.setOnAction(event -> perform(this::recordMovement));
    closeButton.setOnAction(event -> perform(this::close));
    reopenButton.setOnAction(event -> perform(this::reopen));
    countCash.setText("0,00");
    countPix.setText("0,00");
    countCard.setText("0,00");
    opening.setText("0,00");
    movementAmount.setText("0,00");
    setPadding(new Insets(8));
    setTop(createForms());
    setCenter(createMovementTable());
    setBottom(message);
    BorderPane.setMargin(message, new Insets(8, 0, 0, 0));
    refresh();
    TucumaTheme.apply(this);
  }

  public void refresh() {
    if (!authorization.isAllowed(Permission.CASH_MANAGE)) {
      state.setText("Acesso ao caixa não permitido para a sessão atual.");
      summary.setText("");
      movementTable.getItems().clear();
      displayedTurnId = null;
      setTurnControls(false, false);
      openButton.setDisable(true);
      return;
    }
    CashTurn turn = cash.currentTurn().orElse(null);
    displayedTurnId = turn == null ? null : turn.id();
    openButton.setDisable(turn != null && turn.open());
    setTurnControls(turn != null && turn.open(), turn != null && !turn.open());
    reopenButton.setVisible(
        turn != null && !turn.open() && authorization.isAllowed(Permission.FINANCIAL_REVERSAL));
    if (turn == null) {
      state.setText("Nenhum turno de caixa registrado para este operador.");
      summary.setText("");
      movementTable.getItems().clear();
      return;
    }
    state.setText((turn.open() ? "Caixa aberto" : "Último turno fechado") + " · " + turn.id());
    movementTable.setItems(FXCollections.observableArrayList(cash.movements(turn.id())));
    if (turn.open()) {
      summary.setText(renderExpected(cash.expectedTotals(turn.id())));
    } else {
      List<CashReconciliation> closing = cash.latestClosing(turn.id());
      summary.setText(
          closing.isEmpty() ? "Sem conferência de fechamento registrada." : renderClosing(closing));
    }
  }

  TableView<CashMovement> movementTable() {
    return movementTable;
  }

  Label stateLabel() {
    return state;
  }

  Label summaryLabel() {
    return summary;
  }

  Label messageLabel() {
    return message;
  }

  private VBox createForms() {
    GridPane openingForm = new GridPane();
    openingForm.setHgap(6);
    openingForm.setVgap(5);
    openingForm.addRow(0, new Label("Saldo inicial (R$)"), opening, openButton, state);

    GridPane movementForm = new GridPane();
    movementForm.setHgap(6);
    movementForm.setVgap(5);
    movementForm.addRow(
        0,
        new Label("Tipo"),
        movementType,
        new Label("Valor (R$)"),
        movementAmount,
        new Label("Motivo"),
        movementReason,
        movementButton);

    GridPane closingForm = new GridPane();
    closingForm.setHgap(6);
    closingForm.setVgap(5);
    closingForm.addRow(
        0,
        new Label("Dinheiro conferido (R$)"),
        countCash,
        new Label("Pix conferido (R$)"),
        countPix,
        new Label("Cartão conferido (R$)"),
        countCard);
    closingForm.addRow(
        1, new Label("Justificativa se houver diferença"), closeReason, closeButton, reopenButton);
    Label summaryTitle = new Label("Resumo do turno (valores recebidos, não lucro)");
    summaryTitle.setStyle("-fx-font-weight: bold;");
    VBox forms = new VBox(8, openingForm, movementForm, closingForm, summaryTitle, summary);
    forms.setPadding(new Insets(4, 4, 8, 4));
    return forms;
  }

  private TableView<CashMovement> createMovementTable() {
    TableColumn<CashMovement, String> time = new TableColumn<>("Horário UTC");
    time.setCellValueFactory(
        cell -> new ReadOnlyStringWrapper(cell.getValue().createdAt().toString()));
    TableColumn<CashMovement, String> type = new TableColumn<>("Movimento");
    type.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().type().name()));
    TableColumn<CashMovement, String> amount = new TableColumn<>("Valor (centavos)");
    amount.setCellValueFactory(
        cell -> new ReadOnlyStringWrapper(money(cell.getValue().amountCents())));
    TableColumn<CashMovement, String> reason = new TableColumn<>("Motivo");
    reason.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().reason()));
    movementTable.getColumns().addAll(time, type, amount, reason);
    movementTable.setId("cashMovements");
    movementTable.setPlaceholder(new Label("Nenhum movimento neste turno."));
    return movementTable;
  }

  private void open() {
    cash.open(parseCents(opening.getText()));
    message.setText("Turno aberto localmente.");
    refresh();
  }

  private void recordMovement() {
    cash.recordMovement(
        movementType.getValue(), parseCents(movementAmount.getText()), movementReason.getText());
    message.setText("Movimento registrado localmente.");
    refresh();
  }

  private void close() {
    EnumMap<PaymentMethod, Long> counted = new EnumMap<>(PaymentMethod.class);
    counted.put(PaymentMethod.DINHEIRO, parseCents(countCash.getText()));
    counted.put(PaymentMethod.PIX, parseCents(countPix.getText()));
    counted.put(PaymentMethod.CARTAO, parseCents(countCard.getText()));
    cash.close(counted, closeReason.getText());
    message.setText("Fechamento e conferência salvos localmente.");
    refresh();
  }

  private void reopen() {
    cash.reopen(displayedTurnId, closeReason.getText());
    message.setText("Caixa reaberto com registro de auditoria.");
    refresh();
  }

  private void perform(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException failure) {
      message.setText(
          failure.getMessage() == null ? "Operação não concluída." : failure.getMessage());
    }
  }

  private void setTurnControls(boolean open, boolean closed) {
    movementButton.setDisable(!open);
    movementType.setDisable(!open);
    movementAmount.setDisable(!open);
    movementReason.setDisable(!open);
    countCash.setDisable(!open);
    countPix.setDisable(!open);
    countCard.setDisable(!open);
    closeReason.setDisable(!(open || closed));
    closeButton.setDisable(!open);
    reopenButton.setDisable(!closed || !authorization.isAllowed(Permission.FINANCIAL_REVERSAL));
  }

  private String renderExpected(java.util.Map<PaymentMethod, Long> totals) {
    return "Esperado — Dinheiro: "
        + money(totals.get(PaymentMethod.DINHEIRO))
        + " · Pix: "
        + money(totals.get(PaymentMethod.PIX))
        + " · Cartão: "
        + money(totals.get(PaymentMethod.CARTAO));
  }

  private String renderClosing(List<CashReconciliation> totals) {
    return totals.stream()
        .map(
            total ->
                total.method()
                    + ": esperado "
                    + money(total.expectedCents())
                    + ", conferido "
                    + money(total.countedCents())
                    + ", diferença "
                    + money(total.differenceCents()))
        .collect(java.util.stream.Collectors.joining(" · "));
  }

  private long parseCents(String value) {
    String normalized = value == null ? "" : value.strip().replace(".", "").replace(',', '.');
    try {
      long cents = new BigDecimal(normalized).movePointRight(2).longValueExact();
      if (cents < 0) throw new NumberFormatException();
      return cents;
    } catch (NumberFormatException | ArithmeticException failure) {
      throw new IllegalArgumentException("Informe um valor em reais com até duas casas decimais.");
    }
  }

  private TextField field(String id) {
    TextField field = new TextField();
    field.setId(id);
    field.setPrefColumnCount(9);
    return field;
  }

  private String money(long cents) {
    return "R$ " + BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',');
  }
}
