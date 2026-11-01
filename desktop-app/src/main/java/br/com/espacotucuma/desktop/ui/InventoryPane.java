package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.inventory.*;
import br.com.espacotucuma.desktop.application.port.inventory.InventoryRepository;
import br.com.espacotucuma.desktop.domain.money.Money;
import java.math.BigDecimal;
import java.util.UUID;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Offline catalog, supplier maintenance and stock entry backed exclusively by movement records. */
public final class InventoryPane extends BorderPane {
  private final InventoryRepository repository;
  private final MaintainInventoryUseCase useCase;
  private final AuthorizationService authorization;
  private final TableView<StockProduct> table = new TableView<>();
  private final TextField search = new TextField();
  private final ComboBox<String> category = new ComboBox<>();
  private final CheckBox inactive = new CheckBox("Inativos");
  private final CheckBox low = new CheckBox("Abaixo do mínimo");
  private final Label status = new Label();

  public InventoryPane(
      InventoryRepository repository,
      MaintainInventoryUseCase useCase,
      AuthorizationService authorization) {
    this.repository = repository;
    this.useCase = useCase;
    this.authorization = authorization;
    setPadding(new Insets(10));
    buildTable();
    search.setPromptText("Nome, SKU ou fornecedor");
    search.setId("inventory-search");
    category.setId("inventory-category");
    category.getItems().add("ALL");
    category.setValue("ALL");
    low.setId("inventory-low-only");
    low.setOnAction(event -> refresh());
    inactive.setOnAction(event -> refresh());
    Button filter = new Button("Consultar");
    filter.setOnAction(e -> refresh());
    Button add = new Button("Novo produto");
    add.setId("inventory-new-product");
    add.setOnAction(e -> productDialog(null));
    Button edit = new Button("Editar");
    edit.setOnAction(
        e -> {
          if (table.getSelectionModel().getSelectedItem() != null)
            productDialog(table.getSelectionModel().getSelectedItem().product());
        });
    Button entry = new Button("Registrar entrada");
    entry.setId("inventory-entry");
    entry.setOnAction(e -> entryDialog());
    Button deactivate = new Button("Inativar");
    deactivate.setOnAction(e -> deactivate());
    Button supplier = new Button("Novo fornecedor");
    supplier.setId("inventory-new-supplier");
    supplier.setOnAction(e -> supplierDialog());
    HBox controls =
        new HBox(
            7, search, category, inactive, low, filter, add, edit, entry, deactivate, supplier);
    HBox.setHgrow(search, Priority.ALWAYS);
    setTop(controls);
    setCenter(table);
    setBottom(status);
    BorderPane.setMargin(status, new Insets(7, 0, 0, 0));
    refreshAccess();
    refresh();
  }

  private void buildTable() {
    table.setId("inventory-products");
    table.getColumns().add(column("Produto", x -> x.product().name()));
    table.getColumns().add(column("Categoria", x -> x.product().category()));
    table.getColumns().add(column("Unidade", x -> x.product().unit().label()));
    table.getColumns().add(column("Fornecedor", StockProduct::supplierName));
    table.getColumns().add(column("Saldo", x -> format(x.onHandMilliUnits(), x.product().unit())));
    table
        .getColumns()
        .add(
            column(
                "Mínimo", x -> format(x.product().minimumStockMilliUnits(), x.product().unit())));
    table.getColumns().add(column("Situação", x -> x.belowMinimum() ? "REPOR" : "OK"));
    table.getColumns().add(column("Status", x -> x.product().active() ? "Ativo" : "Inativo"));
  }

  private TableColumn<StockProduct, String> column(
      String title, java.util.function.Function<StockProduct, String> value) {
    TableColumn<StockProduct, String> c = new TableColumn<>(title);
    c.setCellValueFactory(
        x -> new javafx.beans.property.SimpleStringProperty(value.apply(x.getValue())));
    c.setPrefWidth(title.equals("Produto") ? 180 : 110);
    return c;
  }

  public void refreshAccess() {
    boolean can =
        authorization.isAllowed(
            br.com.espacotucuma.desktop.application.identity.Permission.INVENTORY_MANAGE);
    for (String id :
        new String[] {"inventory-new-product", "inventory-entry", "inventory-new-supplier"}) {
      javafx.scene.Node n = lookup("#" + id);
      if (n != null) n.setDisable(!can);
    }
  }

  public void refresh() {
    try {
      useCase.requireReadAccess();
      var cats = repository.categories();
      String selected = category.getValue();
      category.getItems().setAll("ALL");
      category.getItems().addAll(cats);
      category.setValue(category.getItems().contains(selected) ? selected : "ALL");
      var page =
          repository.searchProducts(
              new ProductPageQuery(
                  search.getText(),
                  category.getValue(),
                  inactive.isSelected(),
                  low.isSelected(),
                  ProductSortField.NAME,
                  true,
                  0,
                  250));
      table.setItems(FXCollections.observableArrayList(page.items()));
      status.setText(
          page.totalItems() + " produto(s) · “REPOR” indica saldo igual ou inferior ao mínimo.");
    } catch (RuntimeException e) {
      status.setText(e.getMessage());
    }
  }

  private void productDialog(Product old) {
    Dialog<ButtonType> d = new Dialog<>();
    if (getScene() != null) d.initOwner(getScene().getWindow());
    d.setTitle(old == null ? "Cadastrar produto" : "Editar produto");
    ButtonType save = new ButtonType("Salvar", ButtonBar.ButtonData.OK_DONE);
    d.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
    d.getDialogPane().lookupButton(save).setId("product-save");
    TextField name = new TextField(old == null ? "" : old.name()),
        sku = new TextField(old == null ? "" : nullToBlank(old.sku())),
        cat = new TextField(old == null ? "Geral" : old.category()),
        cost = new TextField(old == null ? "0,00" : old.unitCost().amount().toPlainString()),
        price =
            new TextField(
                old == null
                    ? ""
                    : old.salePrice() == null ? "" : old.salePrice().amount().toPlainString()),
        minimum =
            new TextField(old == null ? "0" : format(old.minimumStockMilliUnits(), old.unit()));
    name.setId("product-name");
    sku.setId("product-sku");
    cat.setId("product-category");
    cost.setId("product-cost");
    price.setId("product-price");
    minimum.setId("product-minimum");
    ComboBox<UnitOfMeasure> unit =
        new ComboBox<>(FXCollections.observableArrayList(UnitOfMeasure.values()));
    unit.setValue(old == null ? UnitOfMeasure.UNIDADE : old.unit());
    ComboBox<Supplier> suppliers =
        new ComboBox<>(FXCollections.observableArrayList(repository.suppliers(false)));
    suppliers.setConverter(
        new javafx.util.StringConverter<>() {
          public String toString(Supplier s) {
            return s == null ? "Sem fornecedor" : s.name();
          }

          public Supplier fromString(String s) {
            return null;
          }
        });
    if (old != null && old.supplierId() != null)
      suppliers.getItems().stream()
          .filter(s -> s.id().equals(old.supplierId()))
          .findFirst()
          .ifPresent(suppliers::setValue);
    GridPane g = new GridPane();
    g.setHgap(8);
    g.setVgap(7);
    int row = 0;
    for (Object[] field :
        new Object[][] {
          {"Nome", name},
          {"SKU", sku},
          {"Categoria", cat},
          {"Unidade", unit},
          {"Custo unitário", cost},
          {"Preço de venda (opcional)", price},
          {"Estoque mínimo", minimum},
          {"Fornecedor", suppliers}
        }) {
      g.add(new Label((String) field[0]), 0, row);
      g.add((javafx.scene.Node) field[1], 1, row++);
    }
    d.getDialogPane().setContent(g);
    d.showAndWait()
        .filter(save::equals)
        .ifPresent(
            x -> {
              try {
                long min = toMilli(minimum.getText(), unit.getValue());
                UUID sid = suppliers.getValue() == null ? null : suppliers.getValue().id();
                Money c = new Money(decimal(cost.getText())),
                    p = price.getText().isBlank() ? null : new Money(decimal(price.getText()));
                if (old == null)
                  useCase.registerProduct(
                      sid,
                      sku.getText(),
                      name.getText(),
                      cat.getText(),
                      unit.getValue(),
                      c,
                      p,
                      min);
                else
                  useCase.updateProduct(
                      old.id(),
                      sid,
                      sku.getText(),
                      name.getText(),
                      cat.getText(),
                      unit.getValue(),
                      c,
                      p,
                      min);
                refresh();
              } catch (RuntimeException e) {
                error(e);
              }
            });
  }

  private void entryDialog() {
    StockProduct selected = table.getSelectionModel().getSelectedItem();
    if (selected == null) {
      error(new IllegalArgumentException("Selecione um produto."));
      return;
    }
    Dialog<ButtonType> dialog = new Dialog<>();
    if (getScene() != null) dialog.initOwner(getScene().getWindow());
    dialog.setTitle("Entrada de estoque");
    dialog.setHeaderText(
        selected.product().name() + " (" + selected.product().unit().label() + ")");
    ButtonType save = new ButtonType("Registrar", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
    dialog.getDialogPane().lookupButton(save).setId("stock-entry-save");
    TextField quantity = new TextField("1");
    quantity.setId("stock-entry-quantity");
    TextField reason = new TextField("Compra");
    reason.setId("stock-entry-reason");
    GridPane fields = new GridPane();
    fields.setHgap(8);
    fields.setVgap(7);
    fields.add(new Label("Quantidade"), 0, 0);
    fields.add(quantity, 1, 0);
    fields.add(new Label("Motivo"), 0, 1);
    fields.add(reason, 1, 1);
    dialog.getDialogPane().setContent(fields);
    dialog
        .showAndWait()
        .filter(save::equals)
        .ifPresent(
            ignored -> {
              try {
                useCase.registerEntry(
                    selected.product().id(),
                    toMilli(quantity.getText(), selected.product().unit()),
                    reason.getText(),
                    UUID.randomUUID().toString());
                refresh();
              } catch (RuntimeException e) {
                error(e);
              }
            });
  }

  private void supplierDialog() {
    Dialog<ButtonType> d = new Dialog<>();
    d.setTitle("Novo fornecedor");
    ButtonType save = new ButtonType("Salvar", ButtonBar.ButtonData.OK_DONE);
    d.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
    TextField name = new TextField(), phone = new TextField(), email = new TextField();
    GridPane g = new GridPane();
    g.setHgap(8);
    g.setVgap(7);
    String[] labels = {"Nome", "Telefone", "E-mail"};
    TextField[] fields = {name, phone, email};
    for (int i = 0; i < 3; i++) {
      g.add(new Label(labels[i]), 0, i);
      g.add(fields[i], 1, i);
    }
    d.getDialogPane().setContent(g);
    d.showAndWait()
        .filter(save::equals)
        .ifPresent(
            x -> {
              try {
                useCase.registerSupplier(name.getText(), phone.getText(), email.getText());
                refresh();
              } catch (RuntimeException e) {
                error(e);
              }
            });
  }

  private void deactivate() {
    StockProduct x = table.getSelectionModel().getSelectedItem();
    if (x == null) return;
    try {
      useCase.deactivateProduct(x.product().id());
      refresh();
    } catch (RuntimeException e) {
      error(e);
    }
  }

  private BigDecimal decimal(String s) {
    String value = s.strip();
    return new BigDecimal(value.contains(",") ? value.replace(".", "").replace(",", ".") : value);
  }

  private long toMilli(String s, UnitOfMeasure u) {
    return decimal(s).movePointRight(3).longValueExact();
  }

  private String format(long m, UnitOfMeasure u) {
    return BigDecimal.valueOf(m, 3).stripTrailingZeros().toPlainString();
  }

  private String nullToBlank(String s) {
    return s == null ? "" : s;
  }

  private void error(RuntimeException e) {
    Alert a = new Alert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
    a.setHeaderText("Não foi possível concluir a operação");
    a.showAndWait();
  }
}
