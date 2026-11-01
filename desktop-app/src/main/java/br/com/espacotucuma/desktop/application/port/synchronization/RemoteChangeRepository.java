package br.com.espacotucuma.desktop.application.port.synchronization;

import br.com.espacotucuma.sync.contract.ChangesResponse;

public interface RemoteChangeRepository {
  long checkpoint();

  void applyPage(long expectedCheckpoint, ChangesResponse page);

  default int unresolvedConflictCount() {
    return 0;
  }
}
