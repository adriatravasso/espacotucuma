package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.ProtectionStatus;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

/** Persistent, local-only indication of database, pending changes and remote recovery evidence. */
final class StatusBar extends HBox {

  private static final DateTimeFormatter LOCAL_TIME =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Manaus"));

  private final Label user = new Label();
  private final Label database = new Label("Banco local: OK");
  private final Label pending = new Label();
  private final Label lastSynchronization = new Label();
  private final Label lastBackup = new Label();
  private final Label protection = new Label();

  StatusBar() {
    setId("statusBar");
    setSpacing(0);
    setPadding(new Insets(3, 7, 3, 7));
    getStyleClass().add("status-bar");
    user.setId("currentUser");
    database.setId("statusDatabase");
    pending.setId("statusPending");
    lastSynchronization.setId("statusLastSynchronization");
    lastBackup.setId("statusLastBackup");
    protection.setId("statusProtection");
    for (Label label :
        new Label[] {user, database, pending, lastSynchronization, lastBackup, protection}) {
      label.setPadding(new Insets(2, 7, 2, 7));
    }
    getChildren()
        .addAll(
            user,
            separator(),
            database,
            separator(),
            pending,
            separator(),
            lastSynchronization,
            separator(),
            lastBackup,
            separator(),
            protection);
  }

  void show(ProtectionStatus status) {
    user.setText("Usuário: " + status.userName());
    database.setText("Banco local: OK");
    database.setTooltip(new Tooltip(status.databaseFile().toAbsolutePath().normalize().toString()));
    pending.setText("Pendentes: " + status.pendingOperations());
    lastSynchronization.setText("Última sincronização: " + display(status.lastSynchronizationAt()));
    lastBackup.setText("Último backup validado: " + display(status.lastValidatedBackupAt()));
    lastBackup.setTooltip(
        status.validatedBackupId() == null
            ? null
            : new Tooltip("Identificador remoto: " + status.validatedBackupId()));
    protection.setText(status.protectionMessage());
    protection
        .getStyleClass()
        .removeAll("protection-local", "protection-pending", "protection-safe");
    protection
        .getStyleClass()
        .add(
            status.backupCompletedForCurrentState()
                ? "protection-safe"
                : status.synchronizationGeneration() > 0 && status.pendingOperations() == 0
                    ? "protection-pending"
                    : "protection-local");
  }

  void showReadFailure() {
    protection.setText("Estado local: indisponível");
    protection.getStyleClass().removeAll("protection-safe", "protection-pending");
    protection.getStyleClass().add("protection-local");
  }

  private static String display(java.time.Instant value) {
    return value == null ? "Nunca" : LOCAL_TIME.format(value);
  }

  private static Label separator() {
    Label separator = new Label("│");
    separator.getStyleClass().add("status-separator");
    return separator;
  }
}
