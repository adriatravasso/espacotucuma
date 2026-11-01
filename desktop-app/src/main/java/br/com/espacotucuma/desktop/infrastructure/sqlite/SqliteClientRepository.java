package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.Page;
import br.com.espacotucuma.desktop.application.client.Client;
import br.com.espacotucuma.desktop.application.client.ClientContactFilter;
import br.com.espacotucuma.desktop.application.client.ClientPageQuery;
import br.com.espacotucuma.desktop.application.port.client.ClientRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
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

  @Override
  public Page<Client> searchPage(ClientPageQuery query) {
    Objects.requireNonNull(query, "query");
    return unitOfWork.read(
        () -> {
          String where = whereClause(query);
          long total = count(query, where);
          List<Client> items = selectPage(query, where);
          return new Page<>(items, total, query.pageIndex(), query.pageSize());
        });
  }

  private long count(ClientPageQuery query, String where) {
    String sql = "SELECT count(*) FROM cliente" + where;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bindFilters(statement, query);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getLong(1) : 0;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível consultar os clientes locais.", failure);
    }
  }

  private List<Client> selectPage(ClientPageQuery query, String where) {
    String direction = query.ascending() ? "ASC" : "DESC";
    String sortColumn =
        switch (query.sortField()) {
          case NAME -> "nome COLLATE NOCASE";
          case PHONE -> "COALESCE(telefone, '') COLLATE NOCASE";
          case CREATED_AT -> "criado_em_utc";
        };
    String sql =
        "SELECT id, nome, telefone, criado_em_utc FROM cliente"
            + where
            + " ORDER BY "
            + sortColumn
            + " "
            + direction
            + ", id ASC LIMIT ? OFFSET ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bindFilters(statement, query);
      int pageIndex = where.contains("?") ? 2 : 0;
      statement.setInt(pageIndex + 1, query.pageSize());
      statement.setLong(
          pageIndex + 2, Math.multiplyExact((long) query.pageIndex(), query.pageSize()));
      try (ResultSet result = statement.executeQuery()) {
        List<Client> clients = new java.util.ArrayList<>();
        while (result.next()) {
          clients.add(
              new Client(
                  UUID.fromString(result.getString("id")),
                  result.getString("nome"),
                  result.getString("telefone"),
                  Instant.parse(result.getString("criado_em_utc"))));
        }
        return clients;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível consultar os clientes locais.", failure);
    }
  }

  private String whereClause(ClientPageQuery query) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (!query.searchText().isEmpty())
      where.append(" AND (nome LIKE ? ESCAPE '\\' OR telefone LIKE ? ESCAPE '\\')");
    if (query.contactFilter() == ClientContactFilter.WITH_PHONE) {
      where.append(" AND telefone IS NOT NULL AND trim(telefone) <> ''");
    } else if (query.contactFilter() == ClientContactFilter.WITHOUT_PHONE) {
      where.append(" AND (telefone IS NULL OR trim(telefone) = '')");
    }
    return where.toString();
  }

  private void bindFilters(PreparedStatement statement, ClientPageQuery query) throws SQLException {
    if (!query.searchText().isEmpty()) {
      String search = "%" + escapeLike(query.searchText()) + "%";
      statement.setString(1, search);
      statement.setString(2, search);
    }
  }

  private String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
