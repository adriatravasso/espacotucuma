package br.com.espacotucuma.desktop.application.port.outbox;

import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.outbox.ServerConfirmation;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository {
  void enqueue(PendingOperation operation);

  long expectedVersionForNext(String entity, UUID entityId);

  List<PendingOperation> findPending(int limit);

  boolean recordAttempt(UUID operationId);

  boolean markSynchronized(ServerConfirmation confirmation);
}
