package br.com.espacotucuma.desktop.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.Page;
import br.com.espacotucuma.desktop.application.client.Client;
import br.com.espacotucuma.desktop.application.client.ClientContactFilter;
import br.com.espacotucuma.desktop.application.client.ClientPageQuery;
import br.com.espacotucuma.desktop.application.client.ClientSortField;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteClientRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteUnitOfWork;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Scene;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

@ExtendWith(ApplicationExtension.class)
class PagedTablePaneTest {

  private Stage stage;

  @Start
  void start(Stage stage) {
    this.stage = stage;
    stage.setScene(new Scene(new StackPane(), 900, 600));
    stage.show();
  }

  @Test
  @Timeout(30)
  void searchesFiltersOrdersAndPagesRowsFromLocalSqlite(FxRobot robot, @TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("clients.sqlite"))) {
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      SqliteClientRepository repository =
          new SqliteClientRepository(database.connection(), unitOfWork);
      seedClients(unitOfWork, repository);
      AtomicInteger loads = new AtomicInteger();
      AtomicInteger creations = new AtomicInteger();
      java.util.concurrent.atomic.AtomicReference<String> editedClient =
          new java.util.concurrent.atomic.AtomicReference<>();
      PagedTablePane<Client> table =
          createTable(
              repository,
              loads,
              creations::incrementAndGet,
              client -> editedClient.set(client.name()));
      robot.interact(() -> stage.setScene(new Scene(table, 900, 600)));
      Path screenshot = Path.of("target", "visual-regression", "client-table.png");
      Files.createDirectories(screenshot.getParent());
      robot
          .robotContext()
          .getCaptureSupport()
          .saveImage(robot.capture((javafx.scene.Node) table).getImage(), screenshot);
      assertTrue(Files.size(screenshot) > 0);

      assertEquals("1 / 3", robot.lookup("#pageInfo").queryLabeled().getText());
      assertEquals(10, tableItems(robot).size());
      assertEquals("Cliente 00", tableItems(robot).getFirst().name());
      robot.interact(
          () -> robot.lookup("#dataTable").queryTableView().getSelectionModel().select(1));
      assertFalse(robot.lookup("#tableEdit").queryButton().isDisabled());
      robot.interact(() -> robot.lookup("#tableEdit").queryButton().fire());
      assertEquals("Cliente 01", editedClient.get());
      robot.interact(
          () ->
              robot
                  .lookup("#tableSearch")
                  .<TextField>query()
                  .fireEvent(
                      new KeyEvent(
                          KeyEvent.KEY_PRESSED, "", "", KeyCode.N, false, true, false, false)));
      assertEquals(1, creations.get());

      robot.interact(() -> robot.lookup("#nextPage").queryButton().fire());
      assertEquals("2 / 3", robot.lookup("#pageInfo").queryLabeled().getText());
      assertEquals("Cliente 10", tableItems(robot).getFirst().name());

      robot.interact(() -> robot.lookup("#tableSortDirection").queryButton().fire());
      assertEquals("1 / 3", robot.lookup("#pageInfo").queryLabeled().getText());
      assertEquals("Cliente 24", tableItems(robot).getFirst().name());

      robot.interact(
          () -> robot.lookup("#tableSearch").queryTextInputControl().setText("Cliente 12"));
      assertEquals(1, tableItems(robot).size());
      assertEquals("Cliente 12", tableItems(robot).getFirst().name());

      robot.interact(
          () ->
              robot
                  .lookup("#tableFilter")
                  .queryComboBox()
                  .getSelectionModel()
                  .select("Sem telefone"));
      assertTrue(tableItems(robot).isEmpty());
      assertEquals("0 registros", robot.lookup("#pageInfo").queryLabeled().getText());

      robot.interact(
          () -> robot.lookup("#tableFilter").queryComboBox().getSelectionModel().select("Todos"));
      robot.interact(() -> robot.lookup("#tableSearch").queryAs(TextField.class).requestFocus());
      int beforeRefresh = loads.get();
      robot.interact(
          () ->
              robot
                  .lookup("#tableSearch")
                  .<TextField>query()
                  .fireEvent(
                      new KeyEvent(
                          KeyEvent.KEY_PRESSED, "", "", KeyCode.F5, false, false, false, false)));
      assertTrue(loads.get() > beforeRefresh);
      assertEquals("Cliente 12", robot.lookup("#tableSearch").queryTextInputControl().getText());
      assertTrue(robot.lookup("#tableMessage").queryLabeled().getText().isBlank());
      robot.interact(() -> robot.lookup("#tableSearch").queryTextInputControl().setText("%"));
      assertTrue(
          tableItems(robot).isEmpty(), "O caractere % deve ser tratado como texto de busca.");
    }
  }

  @Test
  @Timeout(30)
  void hidesDatabaseExceptionDetailsFromTheScreen(FxRobot robot) {
    PagedTablePane<Client> table =
        new PagedTablePane<>(
            columns(),
            Map.of("ALL", "Todos"),
            Map.of("NAME", "Nome"),
            10,
            request -> {
              throw new IllegalStateException("jdbc password=secret");
            },
            null,
            null);
    robot.interact(() -> stage.setScene(new Scene(table, 900, 600)));

    assertEquals(
        "Não foi possível carregar os dados locais. Tente novamente.",
        robot.lookup("#tableMessage").queryLabeled().getText());
    assertFalse(robot.lookup("#tableMessage").queryLabeled().getText().contains("secret"));
  }

  private PagedTablePane<Client> createTable(
      SqliteClientRepository repository,
      AtomicInteger loads,
      Runnable onNew,
      java.util.function.Consumer<Client> onEdit) {
    Map<String, String> filters = new LinkedHashMap<>();
    filters.put("ALL", "Todos");
    filters.put("WITH_PHONE", "Com telefone");
    filters.put("WITHOUT_PHONE", "Sem telefone");
    Map<String, String> sorts = new LinkedHashMap<>();
    sorts.put("NAME", "Nome");
    sorts.put("PHONE", "Telefone");
    sorts.put("CREATED_AT", "Cadastro");
    return new PagedTablePane<>(
        columns(),
        filters,
        sorts,
        10,
        request -> {
          loads.incrementAndGet();
          Page<Client> page =
              repository.searchPage(
                  new ClientPageQuery(
                      request.searchText(),
                      ClientContactFilter.valueOf(request.filter()),
                      ClientSortField.valueOf(request.sortBy()),
                      request.ascending(),
                      request.pageIndex(),
                      request.pageSize()));
          return page;
        },
        onNew,
        onEdit);
  }

  private java.util.List<TableColumn<Client, ?>> columns() {
    TableColumn<Client, String> name = new TableColumn<>("Nome");
    name.setCellValueFactory(value -> new ReadOnlyStringWrapper(value.getValue().name()));
    TableColumn<Client, String> phone = new TableColumn<>("Telefone");
    phone.setCellValueFactory(
        value ->
            new ReadOnlyStringWrapper(
                value.getValue().phone() == null ? "" : value.getValue().phone()));
    return java.util.List.of(name, phone);
  }

  private java.util.List<Client> tableItems(FxRobot robot) {
    java.util.concurrent.atomic.AtomicReference<java.util.List<Client>> items =
        new java.util.concurrent.atomic.AtomicReference<>();
    robot.interact(
        () ->
            items.set(
                robot.lookup("#dataTable").<TableView<Client>>query().getItems().stream()
                    .toList()));
    return items.get();
  }

  private void seedClients(SqliteUnitOfWork unitOfWork, SqliteClientRepository repository) {
    for (int index = 0; index < 25; index++) {
      int clientIndex = index;
      Client client =
          new Client(
              UUID.nameUUIDFromBytes(
                  ("client-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              "Cliente %02d".formatted(index),
              index % 2 == 0 ? "9299999%04d".formatted(index) : null,
              Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index));
      unitOfWork.execute(
          () -> {
            repository.save(client);
            return clientIndex;
          });
    }
  }
}
