package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.reports.CommercialReportPage;
import br.com.espacotucuma.desktop.application.reports.CommercialReportPeriod;
import br.com.espacotucuma.desktop.application.reports.CommercialReportRow;
import br.com.espacotucuma.desktop.application.reports.CommercialReportSummary;
import br.com.espacotucuma.desktop.application.reports.CommercialReportType;
import br.com.espacotucuma.desktop.application.reports.QueryCommercialReportsUseCase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Local, paginated commercial reports backed by SQLite. */
public final class CommercialReportsPane extends BorderPane {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Manaus");
  private static final DateTimeFormatter DISPLAY_DATE =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(BUSINESS_ZONE);
  private static final int PAGE_SIZE = 100;

  private final QueryCommercialReportsUseCase reports;
  private final AuthorizationService authorization;
  private final ExecutorService queryExecutor =
      Executors.newSingleThreadExecutor(
          action -> {
            Thread thread = new Thread(action, "tucuma-commercial-reports");
            thread.setDaemon(true);
            return thread;
          });
  private final AtomicLong requestSequence = new AtomicLong();
  private final TextField from = new TextField();
  private final TextField through = new TextField();
  private final ComboBox<CommercialReportType> reportType =
      new ComboBox<>(FXCollections.observableArrayList(CommercialReportType.values()));
  private final Button queryButton = new Button("Consultar");
  private final Button previousButton = new Button("Anterior");
  private final Button nextButton = new Button("Próxima");
  private final Label revenue = new Label();
  private final Label received = new Label();
  private final Label pending = new Label();
  private final Label expenses = new Label();
  private final Label financialBasis =
      new Label(
          "Faturamento: conclusão do atendimento; recebido: recebimento menos estornos no período; pendente: saldo dos atendimentos concluídos; despesas: saídas do caixa.");
  private final Label pageStatus = new Label();
  private final Label message = new Label();
  private final TableView<CommercialReportRow> table = new TableView<>();
  private int page;
  private long totalRows;

  public CommercialReportsPane(
      QueryCommercialReportsUseCase reports, AuthorizationService authorization) {
    this.reports = reports;
    this.authorization = authorization;
    from.setId("commercialReportFrom");
    through.setId("commercialReportThrough");
    reportType.setId("commercialReportType");
    reportType.setValue(CommercialReportType.DAILY_AGENDA);
    queryButton.setId("commercialReportQuery");
    previousButton.setId("commercialReportPrevious");
    nextButton.setId("commercialReportNext");
    table.setId("commercialReportTable");
    message.setId("commercialReportMessage");
    pageStatus.setId("commercialReportPage");
    from.setPromptText("AAAA-MM-DD");
    through.setPromptText("AAAA-MM-DD");
    LocalDate today = LocalDate.now(BUSINESS_ZONE);
    from.setText(today.toString());
    through.setText(today.toString());
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    table
        .getColumns()
        .setAll(
            column(
                "Data/hora",
                row -> row.occurredAt() == null ? "—" : DISPLAY_DATE.format(row.occurredAt()),
                130),
            column("Descrição", CommercialReportRow::title, 200),
            column("Detalhes", CommercialReportRow::detail, 200),
            column("Situação", CommercialReportRow::status, 110),
            column("Qtde.", row -> Long.toString(row.quantity()), 70),
            column("Valor", this::displayAmount, 110));
    table.setPlaceholder(new Label("Nenhum registro para os filtros informados."));
    queryButton.setOnAction(
        event -> {
          page = 0;
          refresh();
        });
    reportType.setOnAction(
        event -> {
          page = 0;
          refresh();
        });
    previousButton.setOnAction(
        event -> {
          if (page > 0) {
            page--;
            refresh();
          }
        });
    nextButton.setOnAction(
        event -> {
          if ((long) (page + 1) * PAGE_SIZE < totalRows) {
            page++;
            refresh();
          }
        });
    HBox filters =
        new HBox(
            8,
            new Label("De"),
            from,
            new Label("Até"),
            through,
            new Label("Relatório"),
            reportType,
            queryButton);
    filters.setPadding(new Insets(8));
    HBox totals = new HBox(18, revenue, received, pending, expenses);
    totals.setPadding(new Insets(4, 8, 8, 8));
    HBox paging = new HBox(8, previousButton, pageStatus, nextButton);
    paging.setPadding(new Insets(8));
    VBox header = new VBox(filters, financialBasis, totals, message);
    setTop(header);
    setCenter(table);
    setBottom(paging);
    setPadding(new Insets(8));
    TucumaTheme.apply(this);
  }

  public void refresh() {
    if (!authorization.isAllowed(Permission.REPORTS_VIEW)) {
      table.getItems().clear();
      message.setText("A sessão atual não tem permissão para consultar relatórios.");
      return;
    }
    final CommercialReportPeriod period;
    try {
      period =
          new CommercialReportPeriod(
              LocalDate.parse(from.getText().strip()), LocalDate.parse(through.getText().strip()));
    } catch (RuntimeException invalidPeriod) {
      message.setText("Informe datas válidas no formato AAAA-MM-DD e um período de até dois anos.");
      return;
    }
    long request = requestSequence.incrementAndGet();
    queryButton.setDisable(true);
    previousButton.setDisable(true);
    nextButton.setDisable(true);
    message.setText("Consultando o banco local…");
    CommercialReportType selectedType = reportType.getValue();
    int selectedPage = page;
    queryExecutor.execute(
        () -> {
          try {
            CommercialReportSummary summary = reports.summarize(period);
            CommercialReportPage result =
                reports.find(selectedType, period, selectedPage, PAGE_SIZE);
            Platform.runLater(
                () -> {
                  if (request != requestSequence.get()) return;
                  showSummary(summary);
                  table.setItems(FXCollections.observableArrayList(result.rows()));
                  totalRows = result.totalRows();
                  pageStatus.setText(
                      totalRows == 0
                          ? "0 registros"
                          : "Página "
                              + (page + 1)
                              + " de "
                              + ((totalRows + PAGE_SIZE - 1) / PAGE_SIZE)
                              + " — "
                              + totalRows
                              + " registros");
                  previousButton.setDisable(page == 0);
                  nextButton.setDisable((long) (page + 1) * PAGE_SIZE >= totalRows);
                  queryButton.setDisable(false);
                  message.setText("");
                });
          } catch (RuntimeException failure) {
            Platform.runLater(
                () -> {
                  if (request != requestSequence.get()) return;
                  table.getItems().clear();
                  queryButton.setDisable(false);
                  message.setText(
                      failure.getMessage() == null
                          ? "Não foi possível consultar os relatórios locais."
                          : failure.getMessage());
                });
          }
        });
  }

  TableView<CommercialReportRow> reportTable() {
    return table;
  }

  void close() {
    queryExecutor.shutdownNow();
  }

  Label messageLabel() {
    return message;
  }

  private void showSummary(CommercialReportSummary summary) {
    revenue.setText("Faturamento: " + money(summary.revenueCents()));
    received.setText("Recebido líquido: " + money(summary.receivedCents()));
    pending.setText("Pendente: " + money(summary.pendingCents()));
    expenses.setText("Despesas: " + money(summary.expensesCents()));
  }

  private String displayAmount(CommercialReportRow row) {
    if (row.occurredAt() != null
        && (reportType.getValue() == CommercialReportType.DAILY_AGENDA
            || reportType.getValue() == CommercialReportType.ABSENCES)) {
      return "—";
    }
    return money(row.amountCents());
  }

  private static TableColumn<CommercialReportRow, String> column(
      String title, Function<CommercialReportRow, String> value, double width) {
    TableColumn<CommercialReportRow, String> column = new TableColumn<>(title);
    column.setPrefWidth(width);
    column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
    return column;
  }

  private static String money(long cents) {
    return "R$ " + BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',');
  }
}
