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
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
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
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

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
              () -> {
                creations.incrementAndGet();
                Client created =
                    new Client(
                        UUID.randomUUID(),
                        "Cliente 00 - Novo",
                        "92900000003",
                        Instant.parse("2026-10-31T15:00:00Z"));
                unitOfWork.execute(
                    () -> {
                      repository.save(created);
                      return null;
                    });
                return true;
              },
              client -> {
                editedClient.set(client.name());
                // The UI callback represents a completed application edit use case. The current
                // local repository supports client creation only, so the test keeps the UI scope
                // focused on invoking the callback and reporting its successful commit.
                return true;
              });
      robot.interact(() -> stage.setScene(new Scene(table, 900, 600)));
      waitForPage(robot, "1 / 3");
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
      waitForMessage(robot, "Alterações salvas localmente.");
      assertEquals("Cliente 00", tableItems(robot).getFirst().name());
      robot.interact(
          () ->
              robot
                  .lookup("#tableSearch")
                  .<TextField>query()
                  .fireEvent(
                      new KeyEvent(
                          KeyEvent.KEY_PRESSED, "", "", KeyCode.N, false, true, false, false)));
      assertEquals(1, creations.get());
      waitForPage(robot, "1 / 3");
      assertEquals(
          "Registro salvo localmente.", robot.lookup("#tableMessage").queryLabeled().getText());
      assertTrue(
          tableItems(robot).stream().anyMatch(client -> client.name().equals("Cliente 00 - Novo")));

      robot.interact(() -> robot.lookup("#nextPage").queryButton().fire());
      waitForPage(robot, "2 / 3");
      assertEquals("2 / 3", robot.lookup("#pageInfo").queryLabeled().getText());
      assertEquals("Cliente 09", tableItems(robot).getFirst().name());

      robot.interact(() -> robot.lookup("#tableSortDirection").queryButton().fire());
      waitForPage(robot, "1 / 3");
      assertEquals("1 / 3", robot.lookup("#pageInfo").queryLabeled().getText());
      assertEquals("Cliente 24", tableItems(robot).getFirst().name());

      robot.interact(
          () -> robot.lookup("#tableSearch").queryTextInputControl().setText("Cliente 12"));
      waitForPage(robot, "1 / 1");
      assertEquals(1, tableItems(robot).size());
      assertEquals("Cliente 12", tableItems(robot).getFirst().name());

      robot.interact(
          () ->
              robot
                  .lookup("#tableFilter")
                  .queryComboBox()
                  .getSelectionModel()
                  .select("Sem telefone"));
      waitForPage(robot, "0 registros");
      assertTrue(tableItems(robot).isEmpty());
      assertEquals("0 registros", robot.lookup("#pageInfo").queryLabeled().getText());

      robot.interact(
          () -> robot.lookup("#tableFilter").queryComboBox().getSelectionModel().select("Todos"));
      waitForPage(robot, "1 / 1");
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
      waitForPage(robot, "1 / 1");
      assertTrue(loads.get() > beforeRefresh);
      assertEquals("Cliente 12", robot.lookup("#tableSearch").queryTextInputControl().getText());
      assertTrue(robot.lookup("#tableMessage").queryLabeled().getText().isBlank());
      robot.interact(() -> robot.lookup("#tableSearch").queryTextInputControl().setText("%"));
      waitForPage(robot, "0 registros");
      assertTrue(
          tableItems(robot).isEmpty(), "O caractere % deve ser tratado como texto de busca.");

      javafx.scene.control.Button preview = robot.lookup("#tablePrintPreview").queryButton();
      Platform.runLater(preview::fire);
      WaitForAsyncUtils.waitFor(
          5, TimeUnit.SECONDS, () -> robot.lookup("#printPreviewTitle").tryQuery().isPresent());
      assertEquals(
          "Resultado da consulta local",
          robot.lookup("#printPreviewTitle").queryLabeled().getText());
      assertTrue(robot.lookup("#printIdentity").queryLabeled().getText().contains("ESPAÇO TUCUMÃ"));
      assertTrue(
          robot
              .lookup("#printPreviewContent")
              .queryTextInputControl()
              .getText()
              .contains("Nenhum registro"));
      Path previewScreenshot = Path.of("target", "visual-regression", "print-preview.png");
      robot
          .robotContext()
          .getCaptureSupport()
          .saveImage(
              robot.capture((javafx.scene.Node) robot.lookup(".dialog-pane").query()).getImage(),
              previewScreenshot);
      assertTrue(Files.size(previewScreenshot) > 0);
      robot.interact(() -> robot.lookup("#printPreviewClose").queryButton().fire());
    }
  }

  @Test
  @Timeout(30)
  void hidesDatabaseExceptionDetailsFromTheScreen(FxRobot robot) throws Exception {
    AtomicBoolean loaderRanOnFxThread = new AtomicBoolean(true);
    PagedTablePane<Client> table =
        new PagedTablePane<>(
            columns(),
            Map.of("ALL", "Todos"),
            Map.of("NAME", "Nome"),
            10,
            request -> {
              loaderRanOnFxThread.set(Platform.isFxApplicationThread());
              throw new IllegalStateException("jdbc password=secret");
            },
            null,
            null);
    robot.interact(() -> stage.setScene(new Scene(table, 900, 600)));
    WaitForAsyncUtils.waitFor(
        5,
        TimeUnit.SECONDS,
        () -> robot.lookup("#tableMessage").queryLabeled().getText().contains("Tente novamente"));

    assertEquals(
        "Não foi possível carregar os dados locais. Tente novamente.",
        robot.lookup("#tableMessage").queryLabeled().getText());
    assertFalse(robot.lookup("#tableMessage").queryLabeled().getText().contains("secret"));
    assertFalse(loaderRanOnFxThread.get(), "Consulta local deve executar fora da thread JavaFX.");
  }

  @Test
  @Timeout(30)
  void diagnosesFullLocalDiskWithoutShowingDriverDetails(FxRobot robot) throws Exception {
    PagedTablePane<Client> table =
        new PagedTablePane<>(
            columns(),
            Map.of("ALL", "Todos"),
            Map.of("NAME", "Nome"),
            10,
            request -> {
              throw new IllegalStateException(
                  "Falha do repositório",
                  new SQLiteException("database or disk is full", SQLiteErrorCode.SQLITE_FULL));
            },
            null,
            null);
    robot.interact(() -> stage.setScene(new Scene(table, 1366, 768)));
    WaitForAsyncUtils.waitFor(
        5,
        TimeUnit.SECONDS,
        () -> robot.lookup("#tableMessage").queryLabeled().getText().contains("sem espaço"));

    String message = robot.lookup("#tableMessage").queryLabeled().getText();
    assertTrue(message.contains("registro não foi confirmado"));
    assertTrue(message.contains("dados anteriores foram preservados"));
    assertFalse(message.contains("database or disk is full"));
  }

  @Test
  @Timeout(30)
  void longLocalQueryLeavesTheWindowResponsive(FxRobot robot) throws Exception {
    CountDownLatch queryStarted = new CountDownLatch(1);
    CountDownLatch allowQueryToFinish = new CountDownLatch(1);
    PagedTablePane<Client> table =
        new PagedTablePane<>(
            columns(),
            Map.of("ALL", "Todos"),
            Map.of("NAME", "Nome"),
            10,
            request -> {
              queryStarted.countDown();
              try {
                if (!allowQueryToFinish.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Consulta sintética excedeu o tempo limite.");
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Consulta sintética interrompida.", interrupted);
              }
              return new Page<>(java.util.List.of(), 0, request.pageIndex(), request.pageSize());
            },
            null,
            null);
    robot.interact(() -> stage.setScene(new Scene(table, 900, 600)));
    WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> queryStarted.getCount() == 0);
    assertEquals(
        "Carregando dados locais…", robot.lookup("#tableMessage").queryLabeled().getText());

    robot.interact(() -> robot.lookup("#tableSearch").queryTextInputControl().setText("Maria"));
    assertEquals("Maria", robot.lookup("#tableSearch").queryTextInputControl().getText());
    allowQueryToFinish.countDown();
    waitForPage(robot, "0 registros");
  }

  @Test
  @Timeout(45)
  void searchesThreeThousandClientsAtMinimumTargetResolution(FxRobot robot, @TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("clients-volume.sqlite"))) {
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      SqliteClientRepository repository =
          new SqliteClientRepository(database.connection(), unitOfWork);
      database.connection().setAutoCommit(false);
      try (PreparedStatement insert =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO cliente (id,codigo_interno,nome,telefone,ativo,criado_em_utc,atualizado_em_utc) "
                      + "VALUES (?,?,?,?,1,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')")) {
        for (int index = 0; index < 3000; index++) {
          insert.setString(
              1,
              UUID.nameUUIDFromBytes(
                      ("volume-client-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                  .toString());
          insert.setString(2, "CLI-%06d".formatted(index + 1));
          insert.setString(3, "Cliente Volume %04d".formatted(index));
          insert.setString(4, "929%07d".formatted(index));
          insert.addBatch();
        }
        insert.executeBatch();
        database.connection().commit();
      } finally {
        database.connection().setAutoCommit(true);
      }

      AtomicInteger loads = new AtomicInteger();
      PagedTablePane<Client> table = createTable(repository, loads, null, null);
      robot.interact(
          () -> {
            stage.setScene(new Scene(table, 1366, 768));
            stage.setWidth(1366);
            stage.setHeight(768);
          });
      assertEquals(1366, (int) stage.getScene().getWidth());
      assertEquals(768, (int) stage.getScene().getHeight());
      waitForPage(robot, "1 / 300");
      assertEquals(10, tableItems(robot).size());
      assertEquals(3000, repository.searchPage(query(0, 10)).totalItems());
      assertEquals(
          "Pesquisar", robot.lookup("#tableSearch").queryTextInputControl().getPromptText());
      assertEquals(
          "Novo registro indisponível nesta área.",
          robot.lookup("#tableNew").queryButton().getAccessibleHelp());
      assertEquals(
          "Estado das consultas e confirmações de gravação local.",
          robot.lookup("#tableMessage").queryLabeled().getAccessibleHelp());

      long started = System.nanoTime();
      robot.interact(
          () ->
              robot.lookup("#tableSearch").queryTextInputControl().setText("Cliente Volume 2999"));
      waitForPage(robot, "1 / 1");
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

      assertTrue(
          elapsedMillis < 5000, "Local search took " + elapsedMillis + " ms for 3000 clients");
      assertEquals("Cliente Volume 2999", tableItems(robot).getFirst().name());
      System.out.println("TUC53_CLIENT_SEARCH_3000_MS=" + elapsedMillis);
    }
  }

  private PagedTablePane<Client> createTable(
      SqliteClientRepository repository,
      AtomicInteger loads,
      java.util.function.BooleanSupplier onNew,
      java.util.function.Function<Client, Boolean> onEdit) {
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

  private void waitForPage(FxRobot robot, String expected) throws Exception {
    WaitForAsyncUtils.waitFor(
        5,
        TimeUnit.SECONDS,
        () -> expected.equals(robot.lookup("#pageInfo").queryLabeled().getText()));
  }

  private void waitForMessage(FxRobot robot, String expected) throws Exception {
    WaitForAsyncUtils.waitFor(
        5,
        TimeUnit.SECONDS,
        () -> !robot.lookup("#tableMessage").queryLabeled().getText().startsWith("Carregando"));
    assertEquals(expected, robot.lookup("#tableMessage").queryLabeled().getText());
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

  private ClientPageQuery query(int pageIndex, int pageSize) {
    return new ClientPageQuery(
        "", ClientContactFilter.ALL, ClientSortField.NAME, true, pageIndex, pageSize);
  }
}
