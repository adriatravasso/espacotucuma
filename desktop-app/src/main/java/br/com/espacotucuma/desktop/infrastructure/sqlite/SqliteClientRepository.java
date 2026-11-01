package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.client.Client;
import br.com.espacotucuma.desktop.application.port.client.ClientRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SqliteClientRepository implements ClientRepository {

  private final java.sql.Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteClientRepository(java.sql.Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public void save(Client client) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO cliente (id, nome, telefone, criado_em_utc, atualizado_em_utc) "
                + "VALUES (?, ?, ?, ?, ?)")) {
      statement.setString(1, client.id().toString());
      statement.setString(2, client.name());
      statement.setString(3, client.phone());
      statement.setString(4, utc(client.createdAt()));
      statement.setString(5, utc(client.createdAt()));
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível salvar o cliente local.", failure);
    }
  }

  @Override
  public Optional<Client> findById(UUID id) {
    return unitOfWork.read(
        () -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT id, nome, telefone, criado_em_utc FROM cliente WHERE id = ?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
              return result.next()
                  ? Optional.of(
                      new Client(
                          UUID.fromString(result.getString("id")),
                          result.getString("nome"),
                          result.getString("telefone"),
                          Instant.parse(result.getString("criado_em_utc"))))
                  : Optional.empty();
            }
          } catch (SQLException failure) {
            throw new IllegalStateException("Não foi possível consultar o cliente local.", failure);
          }
        });
  }

  private void requireTransaction() {
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException("A gravação do cliente exige uma transação local.");
    }
  }

  private String utc(Instant instant) {
    return java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'")
        .withZone(java.time.ZoneOffset.UTC)
        .format(instant);
  }
}
