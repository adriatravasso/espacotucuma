package br.com.espacotucuma.desktop.ui.components;

import br.com.espacotucuma.desktop.application.Page;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Compact local-data table with incremental search, filter, ordering, and explicit pagination. */
public final class PagedTablePane<T> extends BorderPane {

  @FunctionalInterface
  public interface PageLoader<T> {
    Page<T> load(PageRequest request);
  }

  private final TableView<T> table = new TableView<>();
  private final TextField search = new TextField();
  private final ComboBox<String> filter = new ComboBox<>();
  private final ComboBox<String> sort = new ComboBox<>();
  private final Button sortDirection = new Button("↑ Crescente");
  private final Button newRecord = new Button("Novo");
  private final Button editRecord = new Button("Editar");
  private final Button printPreview = new Button("Prévia / Imprimir");
  private final Button previous = new Button("Anterior");
  private final Button next = new Button("Próxima");
  private final Label pageInfo = new Label();
  private final Label message = new Label();
  private final Map<String, String> filterLabels;
  private final Map<String, String> sortLabels;
  private final PageLoader<T> loader;
  private final int pageSize;
  private final Executor queryExecutor;
  private Predicate<T> editAvailability = item -> true;
  private final boolean hasNewAction;
  private boolean newActionAllowed = true;
  private boolean editActionAllowed = true;
  private int pageIndex;
  private long loadGeneration;
  private boolean ascending = true;

  public PagedTablePane(
      List<TableColumn<T, ?>> columns,
      Map<String, String> filterOptions,
      Map<String, String> sortOptions,
      int pageSize,
      PageLoader<T> loader,
      BooleanSupplier onNew,
      Function<T, Boolean> onEdit) {
    this(
        columns,
        filterOptions,
        sortOptions,
        pageSize,
        loader,
        onNew,
        onEdit,
        ForkJoinPool.commonPool());
  }

  public PagedTablePane(
      List<TableColumn<T, ?>> columns,
      Map<String, String> filterOptions,
      Map<String, String> sortOptions,
      int pageSize,
      PageLoader<T> loader,
      BooleanSupplier onNew,
      Function<T, Boolean> onEdit,
      Executor queryExecutor) {
    this.filterLabels = new LinkedHashMap<>(filterOptions);
    this.sortLabels = new LinkedHashMap<>(sortOptions);
    this.loader = Objects.requireNonNull(loader, "loader");
    this.queryExecutor = Objects.requireNonNull(queryExecutor, "queryExecutor");
    if (pageSize < 1 || pageSize > 100) {
      throw new IllegalArgumentException("O tamanho da página deve estar entre 1 e 100.");
    }
    this.pageSize = pageSize;

    search.setId("tableSearch");
    search.setPromptText("Pesquisar");
    search.setPrefColumnCount(24);
    filter.setId("tableFilter");
    filter.setItems(FXCollections.observableArrayList(filterLabels.values()));
    filter.getSelectionModel().selectFirst();
    sort.setId("tableSort");
    sort.setItems(FXCollections.observableArrayList(sortLabels.values()));
    sort.getSelectionModel().selectFirst();
    sortDirection.setId("tableSortDirection");
    newRecord.setId("tableNew");
    hasNewAction = onNew != null;
    newRecord.setDisable(onNew == null);
    newRecord.setAccessibleHelp(
        onNew == null ? "Novo registro indisponível nesta área." : "Criar registro local.");
    newRecord.setTooltip(
        new Tooltip(onNew == null ? "Novo registro indisponível nesta área." : "Criar registro."));
    editRecord.setId("tableEdit");
    editRecord.setDisable(true);
    editRecord.setAccessibleHelp(
        onEdit == null ? "Edição indisponível nesta área." : "Editar o registro selecionado.");
    editRecord.setTooltip(
        new Tooltip(
            onEdit == null ? "Edição indisponível nesta área." : "Editar registro selecionado."));
    printPreview.setId("tablePrintPreview");
    printPreview.setAccessibleHelp("Abrir uma prévia imprimível da página atual.");
    printPreview.setTooltip(new Tooltip("Abrir prévia do resultado local atual."));
    table.setId("dataTable");
    table.getColumns().setAll(columns);
    table.getColumns().forEach(column -> column.setSortable(false));
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    table.setPlaceholder(new Label("Nenhum registro encontrado."));
    previous.setId("previousPage");
    next.setId("nextPage");
    pageInfo.setId("pageInfo");
    message.setId("tableMessage");
    message.setWrapText(true);
    message.setAccessibleHelp("Estado das consultas e confirmações de gravação local.");
    search.addEventFilter(KeyEvent.KEY_PRESSED, this::handleRefreshKey);
    table.addEventFilter(KeyEvent.KEY_PRESSED, this::handleRefreshKey);
    addEventFilter(KeyEvent.KEY_PRESSED, this::handleRefreshKey);

    Label filterLabel = new Label("Filtro:");
    Label sortLabel = new Label("Ordenar por:");
    HBox controls =
        new HBox(
            6,
            newRecord,
            editRecord,
            printPreview,
            search,
            filterLabel,
            filter,
            sortLabel,
            sort,
            sortDirection);
    controls.setPadding(new Insets(7));
    HBox pagination = new HBox(6, previous, pageInfo, next);
    pagination.setPadding(new Insets(7));
    VBox footer = new VBox(message, pagination);
    setTop(controls);
    setCenter(table);
    setBottom(footer);

    if (onNew != null) {
      newRecord.setOnAction(
          event -> {
            try {
              if (onNew.getAsBoolean()) {
                pageIndex = 0;
                loadPage("Registro salvo localmente.");
              }
            } catch (RuntimeException failure) {
              setMessage("Não foi possível concluir a operação local. Tente novamente.");
            }
          });
    }
    if (onEdit != null) {
      table
          .getSelectionModel()
          .selectedItemProperty()
          .addListener(
              (observable, previousItem, selectedItem) ->
                  editRecord.setDisable(
                      selectedItem == null
                          || !editActionAllowed
                          || !editAvailability.test(selectedItem)));
      editRecord.setOnAction(
          event -> {
            T selectedItem = table.getSelectionModel().getSelectedItem();
            if (selectedItem != null) {
              try {
                if (Boolean.TRUE.equals(onEdit.apply(selectedItem))) {
                  loadPage("Alterações salvas localmente.");
                }
              } catch (RuntimeException failure) {
                setMessage("Não foi possível concluir a operação local. Tente novamente.");
              }
            }
          });
    }
    printPreview.setOnAction(
        event ->
            new TucumaPrintPreviewDialog(
                    getScene() == null ? null : getScene().getWindow(),
                    "Resultado da consulta local",
                    printableRows())
                .showAndWait());

    search.textProperty().addListener((observable, oldValue, newValue) -> reloadFromFirstPage());
    filter.valueProperty().addListener((observable, oldValue, newValue) -> reloadFromFirstPage());
    sort.valueProperty().addListener((observable, oldValue, newValue) -> reloadFromFirstPage());
    sortDirection.setOnAction(
        event -> {
          ascending = !ascending;
          sortDirection.setText(ascending ? "↑ Crescente" : "↓ Decrescente");
          reloadFromFirstPage();
        });
    previous.setOnAction(
        event -> {
          if (pageIndex > 0) {
            pageIndex--;
            loadPage();
          }
        });
    next.setOnAction(
        event -> {
          pageIndex++;
          loadPage();
        });
    loadPage();
  }

  private void reloadFromFirstPage() {
    pageIndex = 0;
    loadPage();
  }

  public void refresh() {
    loadPage();
  }

  public T selectedItem() {
    return table.getSelectionModel().getSelectedItem();
  }

  public void addSelectionListener(Consumer<T> listener) {
    Objects.requireNonNull(listener, "listener");
    table
        .getSelectionModel()
        .selectedItemProperty()
        .addListener((observable, previousItem, selectedItem) -> listener.accept(selectedItem));
  }

  public void setEditAvailability(Predicate<T> availability) {
    editAvailability = Objects.requireNonNull(availability, "availability");
    T selected = table.getSelectionModel().getSelectedItem();
    editRecord.setDisable(
        selected == null || !editActionAllowed || !editAvailability.test(selected));
  }

  public void setNewActionEnabled(boolean enabled) {
    newActionAllowed = enabled;
    newRecord.setDisable(!newActionAllowed || !hasNewAction);
    if (hasNewAction) {
      String help =
          enabled
              ? "Criar registro local."
              : "Seu perfil não tem permissão para cadastrar registros nesta área.";
      newRecord.setAccessibleHelp(help);
      newRecord.setTooltip(new Tooltip(help));
    }
  }

  public void setEditActionEnabled(boolean enabled) {
    editActionAllowed = enabled;
    T selected = table.getSelectionModel().getSelectedItem();
    editRecord.setDisable(
        selected == null || !editActionAllowed || !editAvailability.test(selected));
    String help =
        enabled
            ? "Editar o registro selecionado."
            : "Seu perfil não tem permissão para editar registros nesta área.";
    editRecord.setAccessibleHelp(help);
    editRecord.setTooltip(new Tooltip(help));
  }

  private void handleRefreshKey(KeyEvent event) {
    if (event.isControlDown() && event.getCode() == javafx.scene.input.KeyCode.N) {
      if (!newRecord.isDisabled()) newRecord.fire();
      event.consume();
    } else if (event.getCode() == javafx.scene.input.KeyCode.F5) {
      loadPage();
      event.consume();
    }
  }

  private void loadPage() {
    loadPage(null);
  }

  private void loadPage(String completedMessage) {
    long generation = ++loadGeneration;
    PageRequest requested = request();
    setMessage("Carregando dados locais…");
    pageInfo.setText("Carregando…");
    previous.setDisable(true);
    next.setDisable(true);
    CompletableFuture.supplyAsync(() -> query(requested), queryExecutor)
        .whenComplete(
            (loaded, failure) ->
                Platform.runLater(
                    () -> {
                      if (generation != loadGeneration) return;
                      if (failure != null) {
                        table.getItems().clear();
                        pageInfo.setText("Dados indisponíveis");
                        previous.setDisable(true);
                        next.setDisable(true);
                        setMessage("Não foi possível carregar os dados locais. Tente novamente.");
                        return;
                      }
                      pageIndex = loaded.pageIndex();
                      Page<T> page = loaded.page();
                      table.setItems(FXCollections.observableArrayList(page.items()));
                      pageInfo.setText(
                          page.pageCount() == 0
                              ? "0 registros"
                              : (pageIndex + 1) + " / " + page.pageCount());
                      previous.setDisable(pageIndex == 0 || page.pageCount() == 0);
                      next.setDisable(pageIndex + 1 >= page.pageCount());
                      setMessage(completedMessage == null ? "" : completedMessage);
                    }));
  }

  private LoadedPage<T> query(PageRequest request) {
    Page<T> page = loader.load(request);
    if (page.pageCount() == 0) return new LoadedPage<>(page, 0);
    int resolvedPageIndex = Math.min(request.pageIndex(), page.pageCount() - 1);
    if (resolvedPageIndex == request.pageIndex()) return new LoadedPage<>(page, resolvedPageIndex);
    PageRequest correctedRequest =
        new PageRequest(
            request.searchText(),
            request.filter(),
            request.sortBy(),
            request.ascending(),
            resolvedPageIndex,
            request.pageSize());
    return new LoadedPage<>(loader.load(correctedRequest), resolvedPageIndex);
  }

  private String printableRows() {
    String header =
        String.join(" | ", table.getColumns().stream().map(TableColumn::getText).toList());
    String rows =
        table.getItems().stream()
            .map(
                item ->
                    table.getColumns().stream()
                        .map(
                            column -> {
                              javafx.beans.value.ObservableValue<?> value =
                                  column.getCellObservableValue(item);
                              return value == null ? "" : Objects.toString(value.getValue(), "");
                            })
                        .collect(java.util.stream.Collectors.joining(" | ")))
            .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    return table.getItems().isEmpty()
        ? header + System.lineSeparator() + "Nenhum registro."
        : header + System.lineSeparator() + rows;
  }

  private void setMessage(String text) {
    message.setText(text);
    message.setAccessibleText(text);
  }

  private record LoadedPage<T>(Page<T> page, int pageIndex) {}

  private PageRequest request() {
    return new PageRequest(
        search.getText(),
        selectedKey(filter, filterLabels),
        selectedKey(sort, sortLabels),
        ascending,
        pageIndex,
        pageSize);
  }

  private String selectedKey(ComboBox<String> comboBox, Map<String, String> options) {
    String selectedLabel = comboBox.getValue();
    return options.entrySet().stream()
        .filter(entry -> Objects.equals(entry.getValue(), selectedLabel))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse("");
  }
}
