package br.com.espacotucuma.desktop.application.port.synchronization;

import java.util.function.Consumer;

public interface ManualSynchronizationPort {
  void synchronizeNow();

  default void synchronizeNow(Consumer<SynchronizationProgress> progress) {
    progress.accept(SynchronizationProgress.of(SynchronizationProgress.State.VERIFYING_ACCESS));
    try {
      synchronizeNow();
      progress.accept(
          new SynchronizationProgress(
              SynchronizationProgress.State.SYNCHRONIZED_NO_BACKUP,
              "Dados sincronizados; backup pendente.",
              0,
              0,
              0,
              0,
              null));
    } catch (RuntimeException failure) {
      progress.accept(
          new SynchronizationProgress(
              SynchronizationProgress.State.FAILED,
              failure.getMessage() == null ? "Falha na sincronização." : failure.getMessage(),
              0,
              0,
              0,
              0,
              null));
      throw failure;
    }
  }
}
