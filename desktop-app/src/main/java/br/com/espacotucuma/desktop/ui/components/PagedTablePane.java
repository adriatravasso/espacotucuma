package br.com.espacotucuma.desktop.ui.components;

import br.com.espacotucuma.desktop.application.Page;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
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
  private final Button previous = new Button("Anterior");
  private final Button next = new Button("Próxima");
  private final Label pageInfo = new Label();
  private final Label message = new Label();
  private final Map<String, String> filterLabels;
  private final Map<String, String> sortLabels;
  private final PageLoader<T> loader;
  private final int pageSize;
  private int pageIndex;
  private boolean ascending = true;

  public PagedTablePane(
      List<TableColumn<T, ?>> columns,
      Map<String, String> filterOptions,
      Map<String, String> sortOptions,
      int pageSize,
      PageLoader<T> loader,
      Runnable onNew,
      Consumer<T> onEdit) {
    this.filterLabels = new LinkedHashMap<>(filterOptions);
    this.sortLabels = new LinkedHashMap<>(sortOptions);
    this.loader = Objects.requireNonNull(loader, "loader");
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
    newRecord.setDisable(onNew == null);
    newRecord.setTooltip(
        new Tooltip(onNew == null ? "Novo registro indisponível nesta área." : "Criar registro."));
    editRecord.setId("tableEdit");
    editRecord.setDisable(true);
    editRecord.setTooltip(
        new Tooltip(
            onEdit == null ? "Edição indisponível nesta área." : "Editar registro selecionado."));
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
    search.addEventFilter(KeyEvent.KEY_PRESSED, this::handleRefreshKey);
    table.addEventFilter(KeyEvent.KEY_PRESSED, this::handleRefreshKey);
    addEventFilter(KeyEvent.KEY_PRESSED, this::handleRefreshKey);

    Label filterLabel = new Label("Filtro:");
    Label sortLabel = new Label("Ordenar por:");
    HBox controls =
        new HBox(
            6, newRecord, editRecord, search, filterLabel, filter, sortLabel, sort, sortDirection);
    controls.setPadding(new Insets(7));
    HBox pagination = new HBox(6, previous, pageInfo, next);
    pagination.setPadding(new Insets(7));
    VBox footer = new VBox(message, pagination);
    setTop(controls);
    setCenter(table);
    setBottom(footer);

    if (onNew != null) newRecord.setOnAction(event -> onNew.run());
    if (onEdit != null) {
      table
          .getSelectionModel()
          .selectedItemProperty()
          .addListener(
              (observable, previousItem, selectedItem) ->
                  editRecord.setDisable(selectedItem == null));
      editRecord.setOnAction(
          event -> {
            T selectedItem = table.getSelectionModel().getSelectedItem();
            if (selectedItem != null) onEdit.accept(selectedItem);
          });
    }

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
    message.setText("");
    try {
      Page<T> page = loader.load(request());
      int pageCount = page.pageCount();
      if (pageCount > 0 && pageIndex >= pageCount) {
        pageIndex = pageCount - 1;
        page = loader.load(request());
      }
      table.setItems(FXCollections.observableArrayList(page.items()));
      pageInfo.setText(
          page.pageCount() == 0 ? "0 registros" : (pageIndex + 1) + " / " + page.pageCount());
      previous.setDisable(pageIndex == 0 || page.pageCount() == 0);
      next.setDisable(pageIndex + 1 >= page.pageCount());
    } catch (RuntimeException failure) {
      table.getItems().clear();
      pageInfo.setText("Dados indisponíveis");
      previous.setDisable(true);
      next.setDisable(true);
      message.setText("Não foi possível carregar os dados locais. Tente novamente.");
    }
  }

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
