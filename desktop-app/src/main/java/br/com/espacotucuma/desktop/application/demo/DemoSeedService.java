package br.com.espacotucuma.desktop.application.demo;

import br.com.espacotucuma.desktop.application.config.EnvironmentProfile;
import java.util.List;

public final class DemoSeedService {
  private final DemoSeedRepository repository;
  private final List<DemoRecord> records;

  public DemoSeedService(
      EnvironmentProfile profile, DemoSeedRepository repository, List<DemoRecord> records) {
    if (profile != EnvironmentProfile.DEMO) {
      throw new IllegalArgumentException("O seed sintético só pode ser executado no perfil demo");
    }
    this.repository = repository;
    this.records = List.copyOf(records);
  }

  public int run() {
    int inserted = 0;
    for (DemoRecord record : records) {
      if (repository.insertIfAbsent(record)) {
        inserted++;
      }
    }
    return inserted;
  }
}
