package br.com.espacotucuma.desktop.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

@ExtendWith(ApplicationExtension.class)
class TucumaEntryDialogTest {

  private Stage owner;

  @Start
  void start(Stage stage) {
    owner = stage;
    stage.setScene(new Scene(new StackPane(), 400, 260));
    stage.show();
  }

  @Test
  @Timeout(30)
  void validatesRequiredFieldsThenCreatesAndEditsValuesWithKeyboard(FxRobot robot)
      throws Exception {
    CompletableFuture<Optional<Map<String, String>>> created = openForm(robot, Map.of());
    robot.interact(() -> robot.lookup("Salvar").queryButton().fire());
    assertEquals(
        "Informe o campo obrigatório: Name.",
        robot.lookup("#formMessage").queryLabeled().getText());
    assertFalse(robot.lookup("#form-name").queryTextInputControl().getText().contains("Exception"));

    robot.interact(
        () -> {
          robot.lookup("#form-name").queryTextInputControl().setText("Cliente Novo");
          robot.lookup("#form-phone").queryTextInputControl().setText("92999990000");
          robot.lookup("#form-phone").queryTextInputControl().requestFocus();
        });
    fireDialogKey(robot, KeyCode.S, true);
    Map<String, String> newValues = created.get(5, TimeUnit.SECONDS).orElseThrow();
    assertEquals("Cliente Novo", newValues.get("name"));
    assertEquals("92999990000", newValues.get("phone"));

    CompletableFuture<Optional<Map<String, String>>> edited =
        openForm(robot, Map.of("name", "Maria Oliveira", "phone", "92988887777"));
    assertEquals("Maria Oliveira", robot.lookup("#form-name").queryTextInputControl().getText());
    robot.interact(
        () -> {
          robot.lookup("#form-name").queryTextInputControl().setText("Maria Oliveira Lima");
          robot.lookup("#form-name").queryTextInputControl().requestFocus();
        });
    fireDialogKey(robot, KeyCode.ENTER, false);
    Map<String, String> updatedValues = edited.get(5, TimeUnit.SECONDS).orElseThrow();
    assertEquals("Maria Oliveira Lima", updatedValues.get("name"));
    assertEquals("92988887777", updatedValues.get("phone"));
  }

  @Test
  @Timeout(30)
  void escapeCancelsWithoutReturningFormData(FxRobot robot) throws Exception {
    CompletableFuture<Optional<Map<String, String>>> cancelled = openForm(robot, Map.of());
    robot.interact(() -> robot.lookup("#form-name").queryTextInputControl().setText("Não salvar"));
    javafx.scene.Node dialogPane = robot.lookup(".dialog-pane").query();
    Platform.runLater(
        () ->
            dialogPane.fireEvent(
                new KeyEvent(
                    KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false)));
    WaitForAsyncUtils.waitFor(
        5, TimeUnit.SECONDS, () -> robot.lookup(".alert").tryQuery().isPresent());
    assertEquals(
        "Descartar alterações não salvas?",
        robot.lookup(".alert .header-panel .label").queryLabeled().getText());
    robot.interact(() -> robot.lookup("Continuar editando").queryButton().fire());
    assertTrue(robot.lookup("#form-name").tryQuery().isPresent());
    Platform.runLater(
        () ->
            dialogPane.fireEvent(
                new KeyEvent(
                    KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false)));
    WaitForAsyncUtils.waitFor(
        5, TimeUnit.SECONDS, () -> robot.lookup("Descartar").tryQuery().isPresent());
    robot.interact(() -> robot.lookup("Descartar").queryButton().fire());
    assertTrue(cancelled.get(5, TimeUnit.SECONDS).isEmpty());
  }

  @Test
  @Timeout(30)
  void cancelsUntouchedFormWithoutAnUnnecessaryConfirmation(FxRobot robot) throws Exception {
    CompletableFuture<Optional<Map<String, String>>> cancelled = openForm(robot, Map.of());
    javafx.scene.control.Button cancel = robot.lookup("#formCancel").queryButton();
    Platform.runLater(cancel::fire);
    assertTrue(cancelled.get(5, TimeUnit.SECONDS).isEmpty());
    assertTrue(robot.lookup(".alert").tryQuery().isEmpty());
  }

  private CompletableFuture<Optional<Map<String, String>>> openForm(
      FxRobot robot, Map<String, String> initialValues) throws Exception {
    CompletableFuture<Optional<Map<String, String>>> result = new CompletableFuture<>();
    Platform.runLater(
        () -> {
          Map<String, Boolean> fields = new LinkedHashMap<>();
          fields.put("name", true);
          fields.put("phone", false);
          result.complete(
              new TucumaEntryDialog(owner, "Cadastro de cliente", fields, initialValues)
                  .showAndWait());
        });
    WaitForAsyncUtils.waitFor(
        5,
        TimeUnit.SECONDS,
        () ->
            robot
                .lookup("#form-name")
                .tryQuery()
                .map(node -> node.getScene() != null && node.getScene().getWindow().isShowing())
                .orElse(false));
    return result;
  }

  private void fireDialogKey(FxRobot robot, KeyCode key, boolean controlDown) {
    robot.interact(
        () ->
            robot
                .lookup(".dialog-pane")
                .query()
                .fireEvent(
                    new KeyEvent(
                        KeyEvent.KEY_PRESSED, "", "", key, false, controlDown, false, false)));
  }
}
