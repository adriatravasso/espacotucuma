package br.com.espacotucuma.desktop.application.port.synchronization;

import java.util.Objects;
import java.util.UUID;

/** Safe, credential-free progress and outcome details for an explicitly requested sync. */
public record SynchronizationProgress(
    State state,
    String detail,
    int sentOperations,
    int receivedChanges,
    int conflicts,
    long checkpoint,
    UUID backupId) {
  public SynchronizationProgress {
    Objects.requireNonNull(state, "state");
    detail = Objects.requireNonNull(detail, "detail").trim();
    if (sentOperations < 0 || receivedChanges < 0 || conflicts < 0 || checkpoint < 0) {
      throw new IllegalArgumentException(
          "Os indicadores da sincronização não podem ser negativos.");
    }
  }

  public static SynchronizationProgress of(State state) {
    return new SynchronizationProgress(state, state.label(), 0, 0, 0, 0, null);
  }

  public enum State {
    VERIFYING_ACCESS("Verificando acesso à estação..."),
    CHECKING_CHECKPOINT("Conferindo checkpoint remoto..."),
    SENDING("Enviando alterações locais..."),
    CONFIRMING("Confirmando operações no serviço..."),
    RECEIVING("Recebendo alterações remotas..."),
    RECONCILING("Gravando alterações e checkpoint local..."),
    REQUESTING_BACKUP("Solicitando validação do backup remoto..."),
    NO_CONNECTION("Sem conexão com o serviço remoto."),
    PENDING("Sincronização com pendências."),
    SYNCHRONIZED_NO_BACKUP("Dados sincronizados; backup pendente."),
    BACKUP_VALIDATED("Backup remoto validado."),
    CANCELLED("Sincronização cancelada com segurança."),
    FAILED("Falha na sincronização.");

    private final String label;

    State(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }
}
