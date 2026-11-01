package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.port.synchronization.RemoteConflictRepository;
import br.com.espacotucuma.desktop.application.synchronization.RemoteConflict;
import br.com.espacotucuma.desktop.application.synchronization.ResolveRemoteConflictUseCase;
import java.util.Objects;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Lets an administrator review snapshots and explicitly retain local values while preserving remote
 * evidence.
 */
public final class ConflictResolutionPane extends BorderPane {
  private final RemoteConflictRepository conflicts;
  private final ResolveRemoteConflictUseCase resolveConflict;
  private final AuthorizationService authorization;
  private final TableView<RemoteConflict> table = new TableView<>();
  private final TextArea localValues = new TextArea();
  private final TextArea remoteValues = new TextArea();
  private final TextField decisionReason = new TextField();
  private final Button confirmDecision = new Button("Confirmar decisão: manter local");
  private final Label status = new Label();

  public ConflictResolutionPane(
      RemoteConflictRepository conflicts,
      ResolveRemoteConflictUseCase resolveConflict,
      AuthorizationService authorization) {
    this.conflicts = Objects.requireNonNull(conflicts, "conflicts");
    this.resolveConflict = Objects.requireNonNull(resolveConflict, "resolveConflict");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    setPadding(new Insets(8));
    table.setId("syncConflictTable");
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    table
        .getColumns()
        .setAll(sequenceColumn(), entityColumn(), entityIdColumn(), causeColumn(), stateColumn());
    table
        .getSelectionModel()
        .selectedItemProperty()
        .addListener((observable, previous, selected) -> show(selected));

    localValues.setId("conflictLocalValues");
    localValues.setEditable(false);
    localValues.setWrapText(true);
    localValues.setPrefRowCount(5);
    remoteValues.setId("conflictRemoteValues");
    remoteValues.setEditable(false);
    remoteValues.setWrapText(true);
    remoteValues.setPrefRowCount(5);
    decisionReason.setId("conflictDecisionReason");
    decisionReason.setPromptText("Motivo obrigatório (2 a 240 caracteres)");
    confirmDecision.setId("resolveConflict");
    confirmDecision.setOnAction(event -> decide());
    status.setId("conflictStatus");

    VBox details =
        new VBox(
            6,
            new Label("Valores locais registrados"),
            localValues,
            new Label("Valores remotos preservados"),
            remoteValues,
            new Label(
                "Decisão disponível: manter o estado local e preservar os valores remotos para auditoria."),
            decisionReason,
            new HBox(8, confirmDecision, status));
    setCenter(new VBox(8, new Label("Conflitos de sincronização"), table, details));
    TucumaTheme.apply(this);
    refresh();
  }

  public void refresh() {
    boolean allowed = authorization.isAllowed(Permission.SYNC_CONFLICT_VIEW);
    table.setDisable(!allowed);
    confirmDecision.setDisable(true);
    if (!allowed) {
      table.getItems().clear();
      show(null);
      status.setText("Acesso restrito ao perfil de administrador.");
      return;
    }
    try {
      table.setItems(FXCollections.observableArrayList(conflicts.findAll()));
      show(table.getSelectionModel().getSelectedItem());
      status.setText(table.getItems().isEmpty() ? "Nenhum conflito registrado." : "");
    } catch (RuntimeException failure) {
      table.getItems().clear();
      show(null);
      status.setText("Não foi possível carregar os conflitos locais.");
    }
  }

  private void decide() {
    RemoteConflict selected = table.getSelectionModel().getSelectedItem();
    if (selected == null || !"ABERTO".equals(selected.state())) return;
    try {
      resolveConflict.keepLocalAndPreserveRemote(selected.operationId(), decisionReason.getText());
      decisionReason.clear();
      refresh();
      status.setText("Decisão registrada; os valores remotos foram preservados.");
    } catch (RuntimeException failure) {
      status.setText(
          failure.getMessage() == null
              ? "Não foi possível registrar a decisão."
              : failure.getMessage());
    }
  }

  private void show(RemoteConflict conflict) {
    if (conflict == null) {
      localValues.clear();
      remoteValues.clear();
      confirmDecision.setDisable(true);
      return;
    }
    localValues.setText(
        conflict.localPayloadJson() == null
            ? "Sem snapshot local registrado."
            : conflict.localPayloadJson());
    remoteValues.setText(conflict.remotePayloadJson());
    confirmDecision.setDisable(
        !"ABERTO".equals(conflict.state())
            || !authorization.isAllowed(Permission.SYNC_CONFLICT_RESOLVE));
    if ("RESOLVIDO".equals(conflict.state())) {
      status.setText("Decisão: manter local; " + conflict.decisionReason());
    } else {
      status.setText(conflict.requiredAction());
    }
  }

  private TableColumn<RemoteConflict, String> sequenceColumn() {
    return column("Seq.", item -> Long.toString(item.sequence()));
  }

  private TableColumn<RemoteConflict, String> entityColumn() {
    return column("Entidade", RemoteConflict::entity);
  }

  private TableColumn<RemoteConflict, String> entityIdColumn() {
    return column("Registro", item -> item.entityId().toString());
  }

  private TableColumn<RemoteConflict, String> causeColumn() {
    return column("Causa", item -> causeLabel(item.cause()));
  }

  private TableColumn<RemoteConflict, String> stateColumn() {
    return column("Estado", item -> item.state().equals("ABERTO") ? "Aberto" : "Resolvido");
  }

  private static TableColumn<RemoteConflict, String> column(
      String title, java.util.function.Function<RemoteConflict, String> value) {
    TableColumn<RemoteConflict, String> column = new TableColumn<>(title);
    column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
    return column;
  }

  private static String causeLabel(String cause) {
    if (cause == null) return "Conflito remoto";
    return switch (cause) {
      case "VERSAO_REMOTA_DIVERGENTE" -> "Versão remota divergente";
      case "PAYLOAD_DIVERGENTE" -> "Valores divergentes";
      case "IDENTIDADE_DIVERGENTE" -> "Identidade divergente";
      case "ORIGEM_REMOTA_INESPERADA" -> "Origem remota inesperada";
      default -> cause.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
    };
  }
}
