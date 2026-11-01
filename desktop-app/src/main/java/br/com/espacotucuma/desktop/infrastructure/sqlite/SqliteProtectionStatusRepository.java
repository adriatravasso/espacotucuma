package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.ProtectionStatus;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Reads and records local evidence returned by explicit remote synchronization operations. */
public final class SqliteProtectionStatusRepository {

  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
  private final java.sql.Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteProtectionStatusRepository(
      java.sql.Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  public ProtectionStatus load(String userName, java.nio.file.Path databaseFile) {
    return unitOfWork.read(
        () -> {
          String sql =
              "SELECT s.sync_generation, s.last_synced_at_utc, s.backup_sync_generation, "
                  + "s.last_backup_id, "
                  + "s.last_backup_validated_at_utc, "
                  + "(SELECT COUNT(*) FROM operacao_pendente WHERE estado = 'PENDENTE') AS pending "
                  + "FROM status_protecao s WHERE s.id = 1";
          try (PreparedStatement statement = connection.prepareStatement(sql);
              ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
              throw new IllegalStateException("O estado local de proteção não foi inicializado.");
            }
            String syncAt = result.getString("last_synced_at_utc");
            String backupAt = result.getString("last_backup_validated_at_utc");
            String backupId = result.getString("last_backup_id");
            return new ProtectionStatus(
                userName,
                databaseFile,
                result.getInt("pending"),
                result.getLong("sync_generation"),
                syncAt == null ? null : Instant.parse(syncAt),
                result.getLong("backup_sync_generation"),
                backupId,
                backupAt == null ? null : Instant.parse(backupAt));
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível ler o estado local de proteção.", failure);
          }
        });
  }

  public void recordSynchronization(Instant completedAt) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE status_protecao SET sync_generation = sync_generation + 1, "
                + "last_synced_at_utc = ? WHERE id = 1")) {
      statement.setString(1, UTC.format(Objects.requireNonNull(completedAt, "completedAt")));
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException("O estado local de proteção não foi inicializado.");
      }
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "Não foi possível registrar a sincronização concluída.", failure);
    }
  }

  public void recordValidatedBackup(String backupId, Instant validatedAt) {
    requireTransaction();
    if (backupId == null || backupId.isBlank()) {
      throw new IllegalArgumentException("O identificador do backup remoto é obrigatório.");
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE status_protecao SET backup_sync_generation = sync_generation, "
                + "last_backup_id = ?, last_backup_validated_at_utc = ? "
                + "WHERE id = 1 AND sync_generation > 0")) {
      statement.setString(1, backupId.trim());
      statement.setString(2, UTC.format(Objects.requireNonNull(validatedAt, "validatedAt")));
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException(
            "Não é possível confirmar backup sem uma sincronização concluída.");
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível registrar o backup validado.", failure);
    }
  }

  private void requireTransaction() {
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException(
          "A alteração do estado de proteção exige uma transação local.");
    }
  }
}
