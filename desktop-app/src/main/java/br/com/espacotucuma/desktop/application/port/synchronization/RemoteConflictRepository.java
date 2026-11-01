package br.com.espacotucuma.desktop.application.port.synchronization;

import br.com.espacotucuma.desktop.application.synchronization.RemoteConflict;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RemoteConflictRepository {
  List<RemoteConflict> findAll();

  void recordDecision(UUID operationId, UUID userId, Instant decidedAt, String reason);
}
