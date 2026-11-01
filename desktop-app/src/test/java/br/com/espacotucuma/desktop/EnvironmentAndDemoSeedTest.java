package br.com.espacotucuma.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.config.DesktopEnvironment;
import br.com.espacotucuma.desktop.application.config.EnvironmentProfile;
import br.com.espacotucuma.desktop.application.demo.DemoRecord;
import br.com.espacotucuma.desktop.application.demo.DemoSeedRepository;
import br.com.espacotucuma.desktop.application.demo.DemoSeedService;
import br.com.espacotucuma.desktop.application.demo.SyntheticSalonSeed;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnvironmentAndDemoSeedTest {

  @Test
  void environmentProfilesUseIsolatedDataPathsOutsideTheCheckout() {
    Path home = Path.of("C:/fake/user");
    Path temp = Path.of("C:/fake/temp");
    Map<EnvironmentProfile, Path> paths = new EnumMap<>(EnvironmentProfile.class);

    for (EnvironmentProfile profile : EnvironmentProfile.values()) {
      DesktopEnvironment environment = DesktopEnvironment.forProfile(profile, null, home, temp);
      paths.put(profile, environment.databaseFile());
      assertTrue(environment.databaseFile().isAbsolute());
      assertTrue(!environment.databaseFile().startsWith(Path.of("C:/project")));
    }

    assertEquals(paths.size(), paths.values().stream().distinct().count());
    assertTrue(paths.get(EnvironmentProfile.TEST).startsWith(temp));
    assertTrue(paths.get(EnvironmentProfile.DEMO).startsWith(home));
    assertNotEquals(paths.get(EnvironmentProfile.DEMO), paths.get(EnvironmentProfile.HOMOLOGATION));
  }

  @Test
  void repeatedDemoSeedUsesStableSyntheticRecordsWithoutDuplicates() {
    Instant fixedInstant = Instant.parse("2026-11-01T14:00:00Z");
    var records = SyntheticSalonSeed.create(() -> fixedInstant);
    Map<UUID, DemoRecord> stored = new HashMap<>();
    DemoSeedRepository repository = record -> stored.putIfAbsent(record.id(), record) == null;
    DemoSeedService seed = new DemoSeedService(EnvironmentProfile.DEMO, repository, records);

    int firstInserted = seed.run();
    int secondInserted = seed.run();

    assertEquals(17, firstInserted);
    assertEquals(0, secondInserted);
    assertEquals(17, stored.size());
    assertEquals(
        2, stored.values().stream().filter(r -> r.kind() == DemoRecord.Kind.PROFESSIONAL).count());
    assertEquals(
        3, stored.values().stream().filter(r -> r.kind() == DemoRecord.Kind.CLIENT).count());
    assertEquals(
        4, stored.values().stream().filter(r -> r.kind() == DemoRecord.Kind.SERVICE).count());
    assertEquals(
        2, stored.values().stream().filter(r -> r.kind() == DemoRecord.Kind.APPOINTMENT).count());
    assertEquals(
        3, stored.values().stream().filter(r -> r.kind() == DemoRecord.Kind.PRODUCT).count());
    assertEquals(
        3,
        stored.values().stream().filter(r -> r.kind() == DemoRecord.Kind.STOCK_MOVEMENT).count());
    assertTrue(
        stored.values().stream()
            .filter(r -> r.kind() == DemoRecord.Kind.CLIENT)
            .allMatch(r -> r.name().startsWith("Cliente Demonstração")));
  }

  @Test
  void demoSeedUsesFixedManausTimezoneAndInjectedTime() {
    Instant fixedInstant = Instant.parse("2026-11-01T14:00:00Z");
    var records = SyntheticSalonSeed.create(() -> fixedInstant);

    assertTrue(
        records.stream()
            .allMatch(
                record ->
                    record.kind() == DemoRecord.Kind.APPOINTMENT
                        || record.createdAt().equals(fixedInstant)));
    assertTrue(
        records.stream()
            .filter(r -> r.kind() == DemoRecord.Kind.APPOINTMENT)
            .allMatch(
                r ->
                    r.createdAt().equals(Instant.parse("2026-11-01T13:00:00Z"))
                        || r.createdAt().equals(Instant.parse("2026-11-01T18:00:00Z"))));
  }

  @Test
  void demoSeedCannotRunInHomologation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DemoSeedService(
                EnvironmentProfile.HOMOLOGATION,
                record -> true,
                SyntheticSalonSeed.create(() -> Instant.parse("2026-11-01T14:00:00Z"))));
  }
}
