package br.com.espacotucuma.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationException;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.outbox.ServerConfirmation;
import br.com.espacotucuma.desktop.application.synchronization.ResolveRemoteConflictUseCase;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteAuditRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteOutboxRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteRemoteChangeRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteRemoteConflictRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteUnitOfWork;
import br.com.espacotucuma.sync.contract.ChangesResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
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
class ConflictResolutionPaneTest {
  private static final UUID DEVICE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
  private Stage stage;

  @Start
  void start(Stage stage) {
    this.stage = stage;
    stage.setScene(new Scene(new StackPane(), 1080, 760));
    stage.show();
  }

  @Test
  @Timeout(30)
  void administratorComparesVersionsAndAuditsAnExplicitLocalDecision(
      FxRobot robot, @TempDir Path temp) throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("conflicts.sqlite"))) {
      SqliteUnitOfWork unit = new SqliteUnitOfWork(database.connection());
      UUID adminId = UUID.fromString("10000000-0000-0000-0000-000000000001");
      UUID localOperationId = UUID.fromString("20000000-0000-0000-0000-000000000001");
      UUID entityId = UUID.fromString("50000000-0000-0000-0000-000000000001");
      try (var insert =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO usuario (id, nome, login, senha_hash, perfil, ativo, criado_em_utc, "
                      + "atualizado_em_utc) VALUES (?, ?, ?, ?, 'ADMINISTRADOR', 1, ?, ?)")) {
        insert.setString(1, adminId.toString());
        insert.setString(2, "Admin Sintético");
        insert.setString(3, "admin.sintetico");
        insert.setString(4, "synthetic-password-hash-long-enough");
        insert.setString(5, "2026-11-01T12:00:00Z");
        insert.setString(6, "2026-11-01T12:00:00Z");
        insert.executeUpdate();
      }
      SessionContext sessions = new SessionContext();
      sessions.establish(
          new AuthenticatedUser(
              adminId, "Admin Sintético", "admin.sintetico", UserRole.ADMINISTRADOR));
      AuthorizationService authorization = new AuthorizationService(sessions);
      var audit = new SqliteAuditRepository(database.connection(), unit);
      var outbox = new SqliteOutboxRepository(database.connection(), unit);
      unit.execute(
          () -> {
            outbox.enqueue(
                new PendingOperation(
                    localOperationId,
                    "cliente",
                    entityId,
                    "CLIENTE_ATUALIZADO",
                    localOperationId.toString(),
                    "{\"nome\":\"Nome local\"}",
                    1,
                    0,
                    DEVICE_ID,
                    Instant.parse("2026-11-01T12:00:00Z"),
                    0));
            return null;
          });
      unit.execute(
          () -> {
            outbox.markSynchronized(
                new ServerConfirmation(localOperationId, 1, Instant.parse("2026-11-01T12:01:00Z")));
            return null;
          });
      var incoming =
          new SqliteRemoteChangeRepository(
              database.connection(), unit, new ObjectMapper(), DEVICE_ID);
      unit.execute(
          () -> {
            incoming.applyPage(
                0,
                new ChangesResponse(
                    1,
                    4,
                    List.of(
                        new ChangesResponse.RemoteChange(
                            4,
                            localOperationId,
                            DEVICE_ID,
                            "cliente",
                            entityId,
                            "CLIENTE_ATUALIZADO",
                            1,
                            2,
                            "ACEITA",
                            Map.of("nome", "Nome remoto")))));
            return null;
          });
      var conflictRepository = new SqliteRemoteConflictRepository(database.connection(), unit);
      var useCase =
          new ResolveRemoteConflictUseCase(
              unit, conflictRepository, authorization, audit, DEVICE_ID);
      AtomicReference<ConflictResolutionPane> pane = new AtomicReference<>();
      robot.interact(
          () -> {
            pane.set(new ConflictResolutionPane(conflictRepository, useCase, authorization));
            stage.getScene().setRoot(pane.get());
          });
      robot.interact(
          () ->
              robot
                  .lookup("#syncConflictTable")
                  .queryAs(TableView.class)
                  .getSelectionModel()
                  .select(0));

      assertTrue(
          robot
              .lookup("#conflictLocalValues")
              .queryTextInputControl()
              .getText()
              .contains("Nome local"));
      assertTrue(
          robot
              .lookup("#conflictRemoteValues")
              .queryTextInputControl()
              .getText()
              .contains("Nome remoto"));
      assertThrows(
          AuthorizationException.class,
          () -> {
            sessions.establish(
                new AuthenticatedUser(
                    UUID.randomUUID(), "Recepção", "recepcao", UserRole.RECEPCAO));
            useCase.keepLocalAndPreserveRemote(localOperationId, "Decisão sem autorização");
          });
      assertEquals("ABERTO", conflictRepository.findAll().getFirst().state());

      sessions.establish(
          new AuthenticatedUser(
              adminId, "Admin Sintético", "admin.sintetico", UserRole.ADMINISTRADOR));
      robot.interact(
          () -> {
            pane.get().refresh();
            robot
                .lookup("#syncConflictTable")
                .queryAs(TableView.class)
                .getSelectionModel()
                .select(0);
            robot
                .lookup("#conflictDecisionReason")
                .queryTextInputControl()
                .setText("Manter o cadastro local e preservar o remoto para revisão.");
            robot.lookup("#resolveConflict").queryButton().fire();
          });

      var resolved = conflictRepository.findAll().getFirst();
      assertEquals("RESOLVIDO", resolved.state());
      assertEquals("VERSAO_REMOTA_DIVERGENTE", resolved.cause());
      assertEquals("MANTER_LOCAL_PRESERVAR_REMOTO", resolved.decision());
      assertTrue(resolved.localPayloadJson().contains("Nome local"));
      assertTrue(resolved.remotePayloadJson().contains("Nome remoto"));
      try (var query = database.connection().createStatement();
          var result =
              query.executeQuery(
                  "SELECT estado, payload_json FROM operacao_pendente WHERE id = '"
                      + localOperationId
                      + "'")) {
        result.next();
        assertEquals("SINCRONIZADA", result.getString("estado"));
        assertTrue(result.getString("payload_json").contains("Nome local"));
      }
      try (var query = database.connection().createStatement();
          var result =
              query.executeQuery(
                  "SELECT COUNT(*) FROM evento_auditoria WHERE acao = 'CONFLITO_SYNC_DECIDIDO'")) {
        result.next();
        assertEquals(1, result.getInt(1));
      }
    }
  }
}
