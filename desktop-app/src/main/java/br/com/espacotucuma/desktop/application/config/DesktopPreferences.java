package br.com.espacotucuma.desktop.application.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

public record DesktopPreferences(Path databaseFile) {

  private static final String DATABASE_FILE = "database.file";

  public DesktopPreferences {
    databaseFile = databaseFile.toAbsolutePath().normalize();
  }

  public static DesktopPreferences loadOrCreate(DesktopEnvironment environment) throws IOException {
    Path preferencesFile = environment.preferencesFile();
    Properties properties = new Properties();

    if (Files.exists(preferencesFile)) {
      try (InputStream input = Files.newInputStream(preferencesFile)) {
        properties.load(input);
      }
    } else {
      properties.setProperty(DATABASE_FILE, environment.databaseFile().toString());
      try (var output =
          Files.newOutputStream(
              preferencesFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        properties.store(output, "Preferências locais do Tucumã");
      } catch (java.nio.file.FileAlreadyExistsException concurrentStartup) {
        try (InputStream input = Files.newInputStream(preferencesFile)) {
          properties.load(input);
        }
      }
    }

    String configuredDatabase = properties.getProperty(DATABASE_FILE);
    if (configuredDatabase == null || configuredDatabase.isBlank()) {
      return new DesktopPreferences(environment.databaseFile());
    }

    Path databaseFile = Path.of(configuredDatabase.trim());
    if (!databaseFile.isAbsolute()) {
      throw new IOException("A preferência database.file precisa conter um caminho absoluto.");
    }
    return new DesktopPreferences(databaseFile);
  }
}
