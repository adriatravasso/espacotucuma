package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.reports.CommercialReportPage;
import br.com.espacotucuma.desktop.application.reports.CommercialReportPeriod;
import br.com.espacotucuma.desktop.application.reports.CommercialReportRow;
import br.com.espacotucuma.desktop.application.reports.CommercialReportSummary;
import br.com.espacotucuma.desktop.application.reports.CommercialReportType;
import br.com.espacotucuma.desktop.application.reports.QueryCommercialReportsUseCase;
import br.com.espacotucuma.desktop.infrastructure.reporting.LocalArtifactWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.print.PrinterJob;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

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
  private final TextField professionalFilter = new TextField();
  private final TextField serviceFilter = new TextField();
  private final ComboBox<CommercialReportType> reportType =
      new ComboBox<>(FXCollections.observableArrayList(CommercialReportType.values()));
  private final Button queryButton = new Button("Consultar");
  private final Button previousButton = new Button("Anterior");
  private final Button nextButton = new Button("Próxima");
  private final Button printButton = new Button("Imprimir");
  private final Button csvButton = new Button("Exportar CSV");
  private final Button pdfButton = new Button("Exportar PDF");
  private final Label revenue = new Label();
  private final Label received = new Label();
  private final Label pending = new Label();
  private final Label expenses = new Label();
  private final Label financialBasis =
      new Label(
          "Recebimento não representa lucro. Faturamento usa atendimentos concluídos; recebido subtrai estornos do período; pendente é o saldo devido; despesas são saídas do caixa. Estoque mostra custo, comissão mostra obrigação e fechamento compara esperado e contado.");
  private final Label pageStatus = new Label();
  private final Label pageSubtotal = new Label();
  private final Label issuer = new Label();
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
    professionalFilter.setId("commercialReportProfessionalFilter");
    serviceFilter.setId("commercialReportServiceFilter");
    reportType.setId("commercialReportType");
    reportType.setValue(CommercialReportType.DAILY_AGENDA);
    queryButton.setId("commercialReportQuery");
    previousButton.setId("commercialReportPrevious");
    nextButton.setId("commercialReportNext");
    printButton.setId("commercialReportPrint");
    csvButton.setId("commercialReportCsv");
    pdfButton.setId("commercialReportPdf");
    issuer.setId("commercialReportIssuer");
    table.setId("commercialReportTable");
    message.setId("commercialReportMessage");
    pageStatus.setId("commercialReportPage");
    pageSubtotal.setId("commercialReportPageSubtotal");
    from.setPromptText("AAAA-MM-DD");
    through.setPromptText("AAAA-MM-DD");
    professionalFilter.setPromptText("Todos");
    serviceFilter.setPromptText("Todos");
    professionalFilter.setPrefColumnCount(12);
    serviceFilter.setPrefColumnCount(12);
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
            column("Qtde. / esperado", this::displayQuantity, 110),
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
    printButton.setOnAction(event -> printReport());
    csvButton.setOnAction(event -> exportReport(false));
    pdfButton.setOnAction(event -> exportReport(true));
    HBox periodFilters =
        new HBox(
            8,
            new Label("De"),
            from,
            new Label("Até"),
            through,
            new Label("Relatório"),
            reportType,
            queryButton);
    HBox detailFilters =
        new HBox(
            8,
            new Label("Profissional"),
            professionalFilter,
            new Label("Serviço"),
            serviceFilter,
            printButton,
            csvButton,
            pdfButton);
    periodFilters.setPadding(new Insets(8));
    detailFilters.setPadding(new Insets(0, 8, 8, 8));
    HBox totals = new HBox(18, revenue, received, pending, expenses);
    totals.setPadding(new Insets(4, 8, 8, 8));
    HBox paging = new HBox(8, previousButton, pageStatus, pageSubtotal, nextButton);
    paging.setPadding(new Insets(8));
    VBox header = new VBox(periodFilters, detailFilters, issuer, financialBasis, totals, message);
    setTop(header);
    setCenter(table);
    setBottom(paging);
    setPadding(new Insets(8));
    TucumaTheme.apply(this);
  }

  public void refresh() {
    long request = requestSequence.incrementAndGet();
    if (!authorization.isAllowed(Permission.REPORTS_VIEW)) {
      clearResults();
      issuer.setText("");
      printButton.setDisable(true);
      csvButton.setDisable(true);
      pdfButton.setDisable(true);
      message.setText("A sessão atual não tem permissão para consultar relatórios.");
      return;
    }
    printButton.setDisable(false);
    csvButton.setDisable(false);
    pdfButton.setDisable(false);
    clearResults();
    final CommercialReportPeriod period;
    try {
      period =
          new CommercialReportPeriod(
              LocalDate.parse(from.getText().strip()), LocalDate.parse(through.getText().strip()));
    } catch (RuntimeException invalidPeriod) {
      queryButton.setDisable(false);
      message.setText("Informe datas válidas no formato AAAA-MM-DD e um período de até dois anos.");
      return;
    }
    queryButton.setDisable(true);
    previousButton.setDisable(true);
    nextButton.setDisable(true);
    message.setText("Consultando o banco local…");
    CommercialReportType selectedType = reportType.getValue();
    int selectedPage = page;
    String selectedProfessional = professionalFilter.getText().strip();
    String selectedService = serviceFilter.getText().strip();
    var currentUser = authorization.requireUser(Permission.REPORTS_VIEW);
    issuer.setText(
        "Emitido por "
            + currentUser.name()
            + " em "
            + DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
                .withZone(BUSINESS_ZONE)
                .format(Instant.now()));
    queryExecutor.execute(
        () -> {
          try {
            CommercialReportPage result =
                reports.find(
                    selectedType,
                    period,
                    selectedPage,
                    PAGE_SIZE,
                    selectedProfessional,
                    selectedService);
            CommercialReportSummary summary = reports.summarize(period);
            Platform.runLater(
                () -> {
                  if (request != requestSequence.get()) return;
                  showSummary(summary);
                  table.setItems(FXCollections.observableArrayList(result.rows()));
                  long subtotalCents =
                      result.rows().stream()
                          .mapToLong(CommercialReportRow::amountCents)
                          .reduce(0L, Math::addExact);
                  pageSubtotal.setText("Subtotal exibido: " + money(subtotalCents));
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

  ComboBox<CommercialReportType> reportTypeSelector() {
    return reportType;
  }

  void close() {
    requestSequence.incrementAndGet();
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

  private void clearResults() {
    table.getItems().clear();
    revenue.setText("");
    received.setText("");
    pending.setText("");
    expenses.setText("");
    pageStatus.setText("");
    pageSubtotal.setText("");
    totalRows = 0;
  }

  private String displayAmount(CommercialReportRow row) {
    if (row.occurredAt() != null
        && (reportType.getValue() == CommercialReportType.DAILY_AGENDA
            || reportType.getValue() == CommercialReportType.ABSENCES)) {
      return "—";
    }
    return money(row.amountCents());
  }

  private String displayQuantity(CommercialReportRow row) {
    if (reportType.getValue() == CommercialReportType.CASH_CLOSINGS) {
      return money(row.quantity());
    }
    if (reportType.getValue() == CommercialReportType.STOCK_POSITION
        || reportType.getValue() == CommercialReportType.LOW_STOCK
        || reportType.getValue() == CommercialReportType.STOCK_CONSUMPTION) {
      return BigDecimal.valueOf(row.quantity(), 3).stripTrailingZeros().toPlainString();
    }
    return Long.toString(row.quantity());
  }

  private void printReport() {
    if (getScene() == null) return;
    PrinterJob job = PrinterJob.createPrinterJob();
    if (job == null) {
      message.setText("Nenhuma impressora está disponível.");
      return;
    }
    job.getJobSettings().setJobName("Tucumã — " + reportType.getValue().label());
    if (job.showPrintDialog(getScene().getWindow())) {
      if (job.printPage(this)) job.endJob();
      else message.setText("Não foi possível imprimir o relatório.");
    }
  }

  private void exportReport(boolean pdf) {
    if (getScene() == null || !authorization.isAllowed(Permission.REPORTS_VIEW)) return;
    final CommercialReportPeriod period;
    try {
      period =
          new CommercialReportPeriod(
              LocalDate.parse(from.getText().strip()), LocalDate.parse(through.getText().strip()));
    } catch (RuntimeException invalidPeriod) {
      message.setText("Informe datas válidas antes de exportar.");
      return;
    }
    CommercialReportType selectedType = reportType.getValue();
    String selectedProfessional = professionalFilter.getText().strip();
    String selectedService = serviceFilter.getText().strip();
    String extension = pdf ? "pdf" : "csv";
    String baseName =
        "tucuma-"
            + selectedType.name().toLowerCase(java.util.Locale.ROOT)
            + "-"
            + period.from()
            + "-"
            + period.through()
            + "."
            + extension;
    FileChooser chooser = new FileChooser();
    chooser.setTitle(pdf ? "Exportar relatório em PDF" : "Exportar relatório em CSV");
    chooser.setInitialFileName(baseName);
    chooser
        .getExtensionFilters()
        .add(
            new FileChooser.ExtensionFilter(
                extension.toUpperCase() + " (*." + extension + ")", "*." + extension));
    java.io.File selectedFile = chooser.showSaveDialog(getScene().getWindow());
    if (selectedFile == null) return;
    exportReportTo(pdf, period, selectedFile.toPath());
  }

  void exportReportTo(boolean pdf, CommercialReportPeriod period, Path destination) {
    if (getScene() == null || !authorization.isAllowed(Permission.REPORTS_VIEW)) return;
    CommercialReportType selectedType = reportType.getValue();
    String selectedProfessional = professionalFilter.getText().strip();
    String selectedService = serviceFilter.getText().strip();
    String user = authorization.requireUser(Permission.REPORTS_VIEW).name();
    String generatedAt =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(BUSINESS_ZONE)
            .format(Instant.now());
    csvButton.setDisable(true);
    pdfButton.setDisable(true);
    message.setText("Gerando arquivo com os dados locais…");
    queryExecutor.execute(
        () -> {
          try {
            List<CommercialReportRow> rows = new ArrayList<>();
            int currentPage = 0;
            long total;
            do {
              CommercialReportPage result =
                  reports.find(
                      selectedType,
                      period,
                      currentPage,
                      200,
                      selectedProfessional,
                      selectedService);
              rows.addAll(result.rows());
              total = result.totalRows();
              currentPage++;
            } while ((long) currentPage * 200 < total);
            if (pdf) {
              LocalArtifactWriter.writePdf(
                  destination,
                  "Espaço Tucumã — " + selectedType.label(),
                  reportDocumentLines(
                      selectedType,
                      period,
                      selectedProfessional,
                      selectedService,
                      generatedAt,
                      user,
                      rows));
            } else {
              LocalArtifactWriter.writeCsv(
                  destination,
                  reportCsvRows(
                      selectedType,
                      period,
                      selectedProfessional,
                      selectedService,
                      generatedAt,
                      user,
                      rows));
            }
            Platform.runLater(() -> message.setText("Arquivo salvo: " + destination.getFileName()));
          } catch (IOException | RuntimeException failure) {
            Platform.runLater(
                () ->
                    message.setText(
                        failure.getMessage() == null
                            ? "Não foi possível gerar o arquivo. Os dados locais foram preservados."
                            : "Falha ao exportar: " + failure.getMessage()));
          } finally {
            Platform.runLater(
                () -> {
                  csvButton.setDisable(!authorization.isAllowed(Permission.REPORTS_VIEW));
                  pdfButton.setDisable(!authorization.isAllowed(Permission.REPORTS_VIEW));
                });
          }
        });
  }

  private List<List<String>> reportCsvRows(
      CommercialReportType type,
      CommercialReportPeriod period,
      String professional,
      String service,
      String generatedAt,
      String user,
      List<CommercialReportRow> rows) {
    List<List<String>> result = new ArrayList<>();
    result.add(List.of("Relatório", type.label()));
    result.add(List.of("Período", period.from().toString(), period.through().toString()));
    result.add(List.of("Profissional", professional.isEmpty() ? "Todos" : professional));
    result.add(List.of("Serviço", service.isEmpty() ? "Todos" : service));
    result.add(List.of("Emitido por", user));
    result.add(List.of("Gerado em", generatedAt));
    result.add(
        List.of(
            "Data/hora",
            "Descrição",
            "Detalhes",
            "Situação",
            "Quantidade / esperado",
            "Valor (R$)"));
    for (CommercialReportRow row : rows) {
      result.add(
          List.of(
              row.occurredAt() == null ? "" : DISPLAY_DATE.format(row.occurredAt()),
              Objects.toString(row.title(), ""),
              Objects.toString(row.detail(), ""),
              Objects.toString(row.status(), ""),
              exportQuantity(type, row),
              BigDecimal.valueOf(row.amountCents(), 2).toPlainString().replace('.', ',')));
    }
    return result;
  }

  private List<String> reportDocumentLines(
      CommercialReportType type,
      CommercialReportPeriod period,
      String professional,
      String service,
      String generatedAt,
      String user,
      List<CommercialReportRow> rows) {
    List<String> result = new ArrayList<>();
    result.add("Período: " + period.from() + " a " + period.through());
    result.add("Profissional: " + (professional.isEmpty() ? "Todos" : professional));
    result.add("Serviço: " + (service.isEmpty() ? "Todos" : service));
    result.add("Emitido por: " + user + " em " + generatedAt + " (America/Manaus)");
    result.add("");
    result.add("Data/hora | Descrição | Detalhes | Situação | Qtde./esperado | Valor (R$)");
    for (CommercialReportRow row : rows) {
      result.add(
          (row.occurredAt() == null ? "—" : DISPLAY_DATE.format(row.occurredAt()))
              + " | "
              + Objects.toString(row.title(), "")
              + " | "
              + Objects.toString(row.detail(), "")
              + " | "
              + Objects.toString(row.status(), "")
              + " | "
              + exportQuantity(type, row)
              + " | "
              + money(row.amountCents()));
    }
    return result;
  }

  private static String exportQuantity(CommercialReportType type, CommercialReportRow row) {
    if (type == CommercialReportType.CASH_CLOSINGS) return money(row.quantity());
    if (type == CommercialReportType.STOCK_POSITION
        || type == CommercialReportType.LOW_STOCK
        || type == CommercialReportType.STOCK_CONSUMPTION) {
      return BigDecimal.valueOf(row.quantity(), 3).stripTrailingZeros().toPlainString();
    }
    return Long.toString(row.quantity());
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
