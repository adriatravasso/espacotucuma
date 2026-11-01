package br.com.espacotucuma.desktop.application.config;

import java.nio.file.Path;
import java.util.Map;

public record DesktopEnvironment(EnvironmentProfile profile, Path dataDirectory) {

  public DesktopEnvironment {
    dataDirectory = dataDirectory.toAbsolutePath().normalize();
  }

  public static DesktopEnvironment current() {
    Map<String, String> environment = System.getenv();
    EnvironmentProfile profile =
        EnvironmentProfile.parse(
            System.getProperty("tucuma.profile", environment.get("TUCUMA_PROFILE")));
    String configuredRoot =
        System.getProperty("tucuma.data-root", environment.get("TUCUMA_DATA_ROOT"));
    Path home = Path.of(System.getProperty("user.home"));
    Path temp = Path.of(System.getProperty("java.io.tmpdir"));
    return forProfile(profile, configuredRoot == null ? null : Path.of(configuredRoot), home, temp);
  }

  public static DesktopEnvironment forProfile(
      EnvironmentProfile profile, Path configuredRoot, Path home, Path temp) {
    Path base = configuredRoot;
    if (base == null) {
      base =
          profile == EnvironmentProfile.TEST
              ? temp.resolve("espacotucuma")
              : home.resolve(".espacotucuma");
    }
    return new DesktopEnvironment(profile, base.resolve(profile.directoryName()));
  }

  public Path databaseFile() {
    return dataDirectory.resolve("tucuma.sqlite");
  }
}
