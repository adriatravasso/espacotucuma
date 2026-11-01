package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.identity.LocalCredential;
import br.com.espacotucuma.desktop.application.port.identity.LocalCredentialRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SqliteLocalCredentialRepository implements LocalCredentialRepository {

  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
  private final java.sql.Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteLocalCredentialRepository(
      java.sql.Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public Optional<LocalCredential> findActiveByLogin(String login) {
    return unitOfWork.read(
        () -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id, nome, login, perfil, senha_hash FROM usuario WHERE login = ? AND ativo = 1")) {
            statement.setString(1, login);
            try (ResultSet result = statement.executeQuery()) {
              return result.next()
                  ? Optional.of(
                      new LocalCredential(
                          UUID.fromString(result.getString("id")),
                          result.getString("nome"),
                          result.getString("login"),
                          br.com.espacotucuma.desktop.application.identity.UserRole.valueOf(
                              result.getString("perfil")),
                          result.getString("senha_hash")))
                  : Optional.empty();
            }
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível consultar a credencial local.", failure);
          }
        });
  }

  @Override
  public void updateHash(UUID userId, String passwordHash, Instant updatedAt) {
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException("A alteração da credencial exige uma transação local.");
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE usuario SET senha_hash = ?, atualizado_em_utc = ? WHERE id = ? AND ativo = 1")) {
      statement.setString(1, passwordHash);
      statement.setString(2, UTC.format(updatedAt));
      statement.setString(3, userId.toString());
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException("A credencial local não está disponível para alteração.");
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível atualizar a credencial local.", failure);
    }
  }
}
