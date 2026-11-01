package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.port.identity.LoginAttemptRepository;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SqliteLoginAttemptRepository implements LoginAttemptRepository {
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
  private final java.sql.Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteLoginAttemptRepository(java.sql.Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public void record(Optional<UUID> userId, String login, boolean success, Instant occurredAt) {
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException("A tentativa de autenticação exige transação local.");
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO tentativa_login (id, usuario_id, login, sucesso, motivo, ocorrido_em_utc) "
                + "VALUES (?, ?, ?, ?, ?, ?)")) {
      statement.setString(1, UUID.randomUUID().toString());
      statement.setString(2, userId.map(UUID::toString).orElse(null));
      statement.setString(3, login);
      statement.setInt(4, success ? 1 : 0);
      statement.setString(5, success ? "AUTENTICADO" : "CREDENCIAIS_INVALIDAS");
      statement.setString(6, UTC.format(occurredAt));
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível registrar a tentativa de login.", failure);
    }
  }
}
