package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.port.identity.InitialSetupRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

public final class SqliteInitialSetupRepository implements InitialSetupRepository {

  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
  private final java.sql.Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteInitialSetupRepository(java.sql.Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public boolean isComplete() {
    return unitOfWork.read(
        () -> {
          try (var statement = connection.createStatement();
              ResultSet result =
                  statement.executeQuery(
                      "SELECT (SELECT count(*) FROM estabelecimento) = 1 "
                          + "AND (SELECT count(*) FROM usuario WHERE perfil = 'ADMINISTRADOR') = 1")) {
            return result.next() && result.getBoolean(1);
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível verificar a configuração inicial.", failure);
          }
        });
  }

  @Override
  public void save(
      UUID establishmentId,
      String establishmentName,
      UUID administratorId,
      String administratorName,
      String login,
      String passwordHash,
      Instant createdAt) {
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException("A configuração inicial exige uma transação local.");
    }
    String timestamp = UTC.format(createdAt);
    try (PreparedStatement establishment =
            connection.prepareStatement(
                "INSERT INTO estabelecimento (singleton_id, id, nome_fantasia, criado_em_utc, atualizado_em_utc) "
                    + "VALUES (1, ?, ?, ?, ?)");
        PreparedStatement administrator =
            connection.prepareStatement(
                "INSERT INTO usuario (id, nome, login, senha_hash, perfil, criado_em_utc, atualizado_em_utc) "
                    + "VALUES (?, ?, ?, ?, 'ADMINISTRADOR', ?, ?)")) {
      establishment.setString(1, establishmentId.toString());
      establishment.setString(2, establishmentName);
      establishment.setString(3, timestamp);
      establishment.setString(4, timestamp);
      establishment.executeUpdate();

      administrator.setString(1, administratorId.toString());
      administrator.setString(2, administratorName);
      administrator.setString(3, login);
      administrator.setString(4, passwordHash);
      administrator.setString(5, timestamp);
      administrator.setString(6, timestamp);
      administrator.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "Não foi possível salvar a configuração inicial local.", failure);
    }
  }
}
