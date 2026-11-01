package br.com.espacotucuma.desktop.infrastructure.sqlite;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class LocalDatabaseSession implements AutoCloseable {

  private final Path databaseFile;
  private final Connection connection;
  private final FileChannel lockChannel;
  private final FileLock lock;

  private LocalDatabaseSession(
      Path databaseFile, Connection connection, FileChannel lockChannel, FileLock lock) {
    this.databaseFile = databaseFile;
    this.connection = connection;
    this.lockChannel = lockChannel;
    this.lock = lock;
  }

  public static LocalDatabaseSession open(Path databaseFile) throws IOException, SQLException {
    Path file = databaseFile.toAbsolutePath().normalize();
    Files.createDirectories(file.getParent());

    Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
    FileChannel lockChannel =
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock lock;
    try {
      lock = lockChannel.tryLock();
    } catch (OverlappingFileLockException alreadyOpenInThisProcess) {
      lockChannel.close();
      throw new SQLException(
          "O banco local já está aberto nesta estação.", alreadyOpenInThisProcess);
    }
    if (lock == null) {
      lockChannel.close();
      throw new SQLException("O banco local já está aberto por outra instância do Tucumã.");
    }

    Connection connection = null;
    try {
      connection = SQLiteSchemaMigrator.dataSource(file).getConnection();
      verifyDatabase(connection);
      SQLiteSchemaMigrator.migrate(file);
      verifyDatabase(connection);
      return new LocalDatabaseSession(file, connection, lockChannel, lock);
    } catch (SQLException | RuntimeException error) {
      if (connection != null) {
        try {
          connection.close();
        } catch (SQLException closeError) {
          error.addSuppressed(closeError);
        }
      }
      try {
        lock.release();
      } catch (IOException closeError) {
        error.addSuppressed(closeError);
      }
      try {
        lockChannel.close();
      } catch (IOException closeError) {
        error.addSuppressed(closeError);
      }
      throw error;
    }
  }

  private static void verifyDatabase(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
      if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
        throw new SQLException("A verificação de integridade do banco local falhou.");
      }
    }
  }

  public Path databaseFile() {
    return databaseFile;
  }

  Connection connection() {
    return connection;
  }

  @Override
  public void close() throws SQLException, IOException {
    try {
      connection.close();
    } finally {
      try {
        lock.release();
      } finally {
        lockChannel.close();
      }
    }
  }
}
