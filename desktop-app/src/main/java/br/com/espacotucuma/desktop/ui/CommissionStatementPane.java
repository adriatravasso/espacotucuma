package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.commission.CloseCommissionCompetencyUseCase;
import br.com.espacotucuma.desktop.application.commission.CommissionStatementLine;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Offline commission statement for a selected month. */
public final class CommissionStatementPane extends BorderPane {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Manaus");
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(BUSINESS_ZONE);
  private final CloseCommissionCompetencyUseCase commissions;
  private final AuthorizationService authorization;
  private final TextField period = new TextField(YearMonth.now(BUSINESS_ZONE).toString());
  private final Button refreshButton = new Button("Consultar extrato");
  private final Button closeButton = new Button("Fechar competência");
  private final Label state = new Label();
  private final Label total = new Label();
  private final Label message = new Label();
  private final TableView<CommissionStatementLine> table = new TableView<>();

  public CommissionStatementPane(
      CloseCommissionCompetencyUseCase commissions, AuthorizationService authorization) {
    this.commissions = commissions;
    this.authorization = authorization;
    state.setId("commissionStatementState");
    period.setId("commissionPeriod");
    refreshButton.setId("commissionStatementRefresh");
    closeButton.setId("commissionCompetencyClose");
    table.setId("commissionStatementTable");
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    table
        .getColumns()
        .setAll(
            column("Profissional", CommissionStatementLine::professionalName, 160),
            column("Serviço", CommissionStatementLine::serviceName, 150),
            column("Concluído em", row -> DATE.format(row.completedAt()), 125),
            column("Atendimento", row -> row.attendanceId().toString(), 190),
            column("Bruto", row -> money(row.grossCents()), 90),
            column("Desconto rateado", row -> money(row.discountCents()), 110),
            column("Base", row -> money(row.commissionBaseCents()), 90),
            column(
                "Taxa",
                row -> BigDecimal.valueOf(row.percentageBasisPoints(), 2).toPlainString() + "%",
                70),
            column("Comissão", row -> money(row.commissionCents()), 100),
            column("Situação", CommissionStatementLine::status, 90));
    table.setPlaceholder(new Label("Nenhuma comissão apurada para esta competência."));
    refreshButton.setOnAction(event -> refresh());
    closeButton.setOnAction(event -> close());
    HBox controls =
        new HBox(8, new Label("Competência (AAAA-MM)"), period, refreshButton, closeButton);
    controls.setPadding(new Insets(8));
    VBox header = new VBox(6, controls, state, total, message);
    header.setPadding(new Insets(4, 8, 8, 8));
    setTop(header);
    setCenter(table);
    setPadding(new Insets(8));
    refresh();
    TucumaTheme.apply(this);
  }

  public void refresh() {
    boolean canClose = authorization.isAllowed(Permission.COMMISSION_SETTLE);
    closeButton.setDisable(!canClose);
    try {
      YearMonth month = parsePeriod();
      List<CommissionStatementLine> rows = commissions.statement(month, null);
      table.setItems(FXCollections.observableArrayList(rows));
      long totalCents = rows.stream().mapToLong(CommissionStatementLine::commissionCents).sum();
      total.setText("Total devido: " + money(totalCents));
      boolean closed = commissions.isClosed(month);
      state.setText(
          closed ? "Competência fechada" : "Competência aberta — fechamento congela o extrato");
      closeButton.setDisable(!canClose || closed);
      message.setText("");
    } catch (RuntimeException failure) {
      table.getItems().clear();
      total.setText("");
      state.setText("");
      message.setText(
          failure.getMessage() == null
              ? "Não foi possível consultar o extrato."
              : failure.getMessage());
    }
  }

  TableView<CommissionStatementLine> statementTable() {
    return table;
  }

  Label messageLabel() {
    return message;
  }

  private void close() {
    try {
      commissions.close(parsePeriod());
      refresh();
      message.setText("Competência fechada e extrato congelado localmente.");
    } catch (RuntimeException failure) {
      message.setText(
          failure.getMessage() == null
              ? "Não foi possível fechar a competência."
              : failure.getMessage());
    }
  }

  private YearMonth parsePeriod() {
    try {
      return YearMonth.parse(period.getText().strip());
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException("Informe a competência no formato AAAA-MM.");
    }
  }

  private static TableColumn<CommissionStatementLine, String> column(
      String title, Function<CommissionStatementLine, String> value, double width) {
    TableColumn<CommissionStatementLine, String> column = new TableColumn<>(title);
    column.setPrefWidth(width);
    column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
    return column;
  }

  private static String money(long cents) {
    return "R$ " + BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',');
  }
}
