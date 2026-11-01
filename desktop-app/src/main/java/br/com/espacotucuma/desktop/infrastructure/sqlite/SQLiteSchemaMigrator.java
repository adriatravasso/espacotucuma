package br.com.espacotucuma.desktop.infrastructure.sqlite;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

public final class SQLiteSchemaMigrator {

  private SQLiteSchemaMigrator() {}

  public static void migrate(Path databaseFile) {
    Flyway.configure()
        .dataSource(dataSource(databaseFile))
        .locations("classpath:db/migration")
        .communityDBSupportEnabled(true)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  static SQLiteDataSource dataSource(Path databaseFile) {
    SQLiteConfig configuration = new SQLiteConfig();
    configuration.enforceForeignKeys(true);
    SQLiteDataSource dataSource = new SQLiteDataSource(configuration);
    dataSource.setUrl("jdbc:sqlite:" + databaseFile.toAbsolutePath().normalize());
    return dataSource;
  }
}
