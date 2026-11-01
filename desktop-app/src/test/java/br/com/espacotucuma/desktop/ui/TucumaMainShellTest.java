package br.com.espacotucuma.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.ProtectionStatus;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Scene;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

@ExtendWith(ApplicationExtension.class)
class TucumaMainShellTest {

  private TucumaMainShell shell;
  private AtomicInteger synchronizationCalls;
  private CountDownLatch synchronizationStarted;
  private CountDownLatch releaseSynchronization;

  @Start
  void start(Stage stage) {
    synchronizationCalls = new AtomicInteger();
    synchronizationStarted = new CountDownLatch(1);
    releaseSynchronization = new CountDownLatch(1);
    shell =
        new TucumaMainShell(
            () ->
                new ProtectionStatus(
                    "Administradora Sintética",
                    Path.of("dados/tucuma.sqlite"),
                    0,
                    0,
                    null,
                    0,
                    null,
                    null),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            () -> {
              synchronizationCalls.incrementAndGet();
              synchronizationStarted.countDown();
              boolean interrupted = false;
              while (releaseSynchronization.getCount() > 0) {
                try {
                  releaseSynchronization.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                  interrupted = true;
                }
              }
              if (interrupted) Thread.currentThread().interrupt();
            });
    stage.setScene(new Scene(shell.root(), 1180, 720));
    stage.show();
  }

  @Test
  @Timeout(30)
  void menuAndToolbarNavigationOpenTheRequestedModule(FxRobot robot) throws Exception {
    Path screenshot = Path.of("target", "visual-regression", "main-shell.png");
    Files.createDirectories(screenshot.getParent());
    robot
        .robotContext()
        .getCaptureSupport()
        .saveImage(robot.capture((javafx.scene.Node) shell.root()).getImage(), screenshot);
    assertTrue(Files.size(screenshot) > 0);

    MenuBar menuBar = robot.lookup("#mainMenuBar").queryAs(MenuBar.class);
    MenuItem clients =
        menuBar.getMenus().stream()
            .filter(menu -> menu.getText().equals("Cadastros"))
            .flatMap(menu -> menu.getItems().stream())
            .filter(item -> item.getId().equals("menu-clients"))
            .findFirst()
            .orElseThrow();
    robot.interact(clients::fire);
    assertEquals("Clientes", robot.lookup("#moduleTitle").queryLabeled().getText());

    robot.interact(() -> robot.lookup("#toolbar-agenda").queryButton().fire());
    assertEquals("Agenda", robot.lookup("#moduleTitle").queryLabeled().getText());

    robot.interact(() -> robot.lookup("#module-cash").queryButton().fire());
    assertEquals("Caixa", robot.lookup("#moduleTitle").queryLabeled().getText());
  }

  @Test
  @Timeout(30)
  void unavailableCommandsStayDisabledAndNavigationDoesNotReachRemotePorts(FxRobot robot) {
    for (String command :
        new String[] {
          "#command-new", "#command-edit", "#command-save", "#command-cancel", "#command-print",
        }) {
      assertTrue(
          robot.lookup(command).queryButton().isDisabled(), command + " deve estar desativado");
    }
    assertFalse(robot.lookup("#command-sync").queryButton().isDisabled());
    assertTrue(robot.lookup("#command-sync").queryButton().getAccessibleHelp().contains("manual"));

    JavaClass shell = new ClassFileImporter().importClass(TucumaMainShell.class);
    assertFalse(
        shell.getDirectDependenciesFromSelf().stream()
            .anyMatch(
                dependency ->
                    dependency.getTargetClass().getName().startsWith("java.net.")
                        || dependency
                            .getTargetClass()
                            .getName()
                            .contains("infrastructure.synchronization")),
        "A shell não deve manter cliente HTTP ou dependências de infraestrutura remota.");

    robot.interact(() -> robot.lookup("#module-clients").queryButton().fire());
    robot.press(KeyCode.CONTROL).press(KeyCode.N).release(KeyCode.N).release(KeyCode.CONTROL);
    assertEquals("Clientes", robot.lookup("#moduleTitle").queryLabeled().getText());
    assertEquals(
        "Usuário: Administradora Sintética", robot.lookup("#currentUser").queryLabeled().getText());
  }

  @Test
  @Timeout(30)
  void synchronizationRunsOnlyAfterClickAndDoesNotAllowParallelReentry(FxRobot robot)
      throws Exception {
    assertEquals(0, synchronizationCalls.get());
    robot.interact(() -> robot.lookup("#command-sync").queryButton().fire());
    assertTrue(synchronizationStarted.await(5, TimeUnit.SECONDS));
    try {
      robot.interact(
          () ->
              robot.lookup("#mainMenuBar").queryAs(MenuBar.class).getMenus().stream()
                  .filter(menu -> menu.getText().equals("Ferramentas"))
                  .flatMap(menu -> menu.getItems().stream())
                  .filter(item -> item.getId().equals("menu-sync"))
                  .findFirst()
                  .orElseThrow()
                  .fire());
      assertEquals(1, synchronizationCalls.get());
      assertTrue(robot.lookup("#command-sync").queryButton().isDisabled());
    } finally {
      releaseSynchronization.countDown();
    }
  }

  @Test
  @Timeout(30)
  void statusBarDistinguishesLocalPendingSynchronizedAndValidatedBackup(FxRobot robot) {
    robot.interact(
        () ->
            shell.showStatus(
                new ProtectionStatus(
                    "Administradora Sintética",
                    Path.of("dados/tucuma.sqlite"),
                    2,
                    0,
                    null,
                    0,
                    null,
                    null)));
    assertEquals(
        "Somente neste computador", robot.lookup("#statusProtection").queryLabeled().getText());
    assertEquals("Pendentes: 2", robot.lookup("#statusPending").queryLabeled().getText());
    assertEquals(
        "Último backup validado: Nunca",
        robot.lookup("#statusLastBackup").queryLabeled().getText());

    Instant synchronization = Instant.parse("2026-10-31T15:00:00Z");
    robot.interact(
        () ->
            shell.showStatus(
                new ProtectionStatus(
                    "Administradora Sintética",
                    Path.of("dados/tucuma.sqlite"),
                    0,
                    1,
                    synchronization,
                    0,
                    null,
                    null)));
    assertEquals(
        "Dados sincronizados; backup pendente",
        robot.lookup("#statusProtection").queryLabeled().getText());

    robot.interact(
        () ->
            shell.showStatus(
                new ProtectionStatus(
                    "Administradora Sintética",
                    Path.of("dados/tucuma.sqlite"),
                    0,
                    1,
                    synchronization,
                    1,
                    "backup-sintetico-1",
                    synchronization.plusSeconds(10))));
    assertEquals("Backup concluído", robot.lookup("#statusProtection").queryLabeled().getText());
    assertTrue(robot.lookup("#statusLastBackup").queryLabeled().getText().contains("31/10/2026"));
  }
}
