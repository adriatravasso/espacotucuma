package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Serializes access to the single local connection and commits each business operation atomically.
 */
public final class SqliteUnitOfWork implements UnitOfWork {

  private final Connection connection;
  private boolean executing;
  private Thread transactionOwner;

  public SqliteUnitOfWork(Connection connection) {
    this.connection = Objects.requireNonNull(connection, "connection");
  }

  @Override
  public synchronized <T> T execute(Supplier<T> operation) {
    Objects.requireNonNull(operation, "operation");
    if (executing) {
      throw new IllegalStateException("Uma transação local já está em andamento.");
    }
    executing = true;
    transactionOwner = Thread.currentThread();
    try {
      connection.setAutoCommit(false);
      T result = operation.get();
      connection.commit();
      return result;
    } catch (RuntimeException | Error failure) {
      rollback(failure);
      throw failure;
    } catch (SQLException failure) {
      RuntimeException wrapped = new IllegalStateException("Falha na transação SQLite.", failure);
      rollback(wrapped);
      throw wrapped;
    } finally {
      try {
        connection.setAutoCommit(true);
      } catch (SQLException resetFailure) {
        throw new IllegalStateException(
            "Não foi possível restaurar o modo transacional SQLite.", resetFailure);
      } finally {
        transactionOwner = null;
        executing = false;
      }
    }
  }

  synchronized <T> T read(Supplier<T> query) {
    Objects.requireNonNull(query, "query");
    if (executing) {
      throw new IllegalStateException("A consulta não pode ocorrer durante uma transação local.");
    }
    return query.get();
  }

  synchronized boolean isExecutingOnCurrentThread() {
    return executing && transactionOwner == Thread.currentThread();
  }

  private void rollback(Throwable failure) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }
}
