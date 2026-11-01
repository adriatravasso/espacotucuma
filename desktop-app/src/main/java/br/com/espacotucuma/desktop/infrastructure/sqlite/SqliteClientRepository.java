package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.Page;
import br.com.espacotucuma.desktop.application.client.Client;
import br.com.espacotucuma.desktop.application.client.ClientAttendanceHistory;
import br.com.espacotucuma.desktop.application.client.ClientContactFilter;
import br.com.espacotucuma.desktop.application.client.ClientPageQuery;
import br.com.espacotucuma.desktop.application.port.client.ClientRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
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
            "INSERT INTO cliente (id, codigo_interno, nome, telefone, contato_opcional, "
                + "observacao_operacional, ativo, criado_em_utc, atualizado_em_utc) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      statement.setString(1, client.id().toString());
      statement.setString(
          2, client.internalCode() == null ? nextInternalCode() : client.internalCode());
      statement.setString(3, client.name());
      statement.setString(4, client.phone());
      statement.setString(5, client.optionalContact());
      statement.setString(6, client.preferences());
      statement.setInt(7, client.active() ? 1 : 0);
      statement.setString(8, utc(client.createdAt()));
      statement.setString(9, utc(client.updatedAt()));
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível salvar o cliente local.", failure);
    }
  }

  @Override
  public void update(Client client) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE cliente SET nome = ?, telefone = ?, contato_opcional = ?, "
                + "observacao_operacional = ?, atualizado_em_utc = ? "
                + "WHERE id = ? AND ativo = 1")) {
      statement.setString(1, client.name());
      statement.setString(2, client.phone());
      statement.setString(3, client.optionalContact());
      statement.setString(4, client.preferences());
      statement.setString(5, utc(client.updatedAt()));
      statement.setString(6, client.id().toString());
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException("O cliente não existe ou está inativo.");
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível atualizar o cliente local.", failure);
    }
  }

  @Override
  public void deactivate(UUID id, Instant updatedAt) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE cliente SET ativo = 0, atualizado_em_utc = ? WHERE id = ? AND ativo = 1")) {
      statement.setString(1, utc(updatedAt));
      statement.setString(2, id.toString());
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException("O cliente não existe ou já está inativo.");
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível inativar o cliente local.", failure);
    }
  }

  @Override
  public String nextInternalCode() {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT COALESCE(MAX(CAST(SUBSTR(codigo_interno, 5) AS INTEGER)), 0) + 1 "
                + "FROM cliente")) {
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new IllegalStateException("Não foi possível gerar o código.");
        return String.format(java.util.Locale.ROOT, "CLI-%06d", result.getLong(1));
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível gerar o código interno.", failure);
    }
  }

  @Override
  public Optional<Client> findById(UUID id) {
    return unitOfWork.read(
        () -> {
          try (PreparedStatement statement =
              connection.prepareStatement(selectClient() + " WHERE id = ?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
              return result.next() ? Optional.of(client(result)) : Optional.empty();
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

  @Override
  public List<Client> findPotentialDuplicates(String name, String phone, UUID excludingId) {
    String normalizedName = Objects.requireNonNull(name, "name").trim();
    String normalizedPhone = digits(phone);
    return unitOfWork.read(
        () -> {
          String sql =
              selectClient()
                  + " WHERE ativo = 1 AND id <> ? AND (nome = ? COLLATE NOCASE"
                  + (normalizedPhone.isEmpty() ? ")" : " OR telefone = ?)")
                  + " ORDER BY nome COLLATE NOCASE LIMIT 10";
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, excludingId == null ? "" : excludingId.toString());
            statement.setString(2, normalizedName);
            if (!normalizedPhone.isEmpty()) statement.setString(3, normalizedPhone);
            try (ResultSet result = statement.executeQuery()) {
              List<Client> matches = new ArrayList<>();
              while (result.next()) matches.add(client(result));
              return List.copyOf(matches);
            }
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível conferir possíveis duplicidades.", failure);
          }
        });
  }

  @Override
  public List<ClientAttendanceHistory> attendanceHistory(UUID clientId) {
    return unitOfWork.read(
        () -> {
          String sql =
              "SELECT a.id, a.iniciado_em_utc, a.estado, a.total_centavos, "
                  + "COALESCE(group_concat(s.nome_snapshot, ', '), '') AS servicos "
                  + "FROM atendimento a LEFT JOIN atendimento_servico s "
                  + "ON s.atendimento_id = a.id WHERE a.cliente_id = ? "
                  + "GROUP BY a.id ORDER BY a.iniciado_em_utc DESC, a.id LIMIT 200";
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId.toString());
            try (ResultSet result = statement.executeQuery()) {
              List<ClientAttendanceHistory> history = new ArrayList<>();
              while (result.next()) {
                history.add(
                    new ClientAttendanceHistory(
                        UUID.fromString(result.getString("id")),
                        Instant.parse(result.getString("iniciado_em_utc")),
                        result.getString("estado"),
                        result.getString("servicos"),
                        result.getLong("total_centavos")));
              }
              return List.copyOf(history);
            }
          } catch (SQLException failure) {
            throw new IllegalStateException(
                "Não foi possível consultar o histórico local.", failure);
          }
        });
  }

  private long count(ClientPageQuery query, String where) {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT count(*) FROM cliente" + where)) {
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
        selectClient()
            + where
            + " ORDER BY "
            + sortColumn
            + " "
            + direction
            + ", id ASC LIMIT ? OFFSET ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bindFilters(statement, query);
      int pageIndex = where.contains("?") ? 3 : 0;
      statement.setInt(pageIndex + 1, query.pageSize());
      statement.setLong(
          pageIndex + 2, Math.multiplyExact((long) query.pageIndex(), query.pageSize()));
      try (ResultSet result = statement.executeQuery()) {
        List<Client> clients = new ArrayList<>();
        while (result.next()) clients.add(client(result));
        return clients;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível consultar os clientes locais.", failure);
    }
  }

  private String whereClause(ClientPageQuery query) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (!query.includeInactive()) where.append(" AND ativo = 1");
    if (!query.searchText().isEmpty())
      where.append(
          " AND (nome LIKE ? ESCAPE '\\' OR codigo_interno LIKE ? ESCAPE '\\' OR telefone LIKE ? ESCAPE '\\')");
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
      String phoneSearch = digits(query.searchText());
      if (phoneSearch.isEmpty()) phoneSearch = "00000000000000000000";
      statement.setString(3, "%" + escapeLike(phoneSearch) + "%");
    }
  }

  private String selectClient() {
    return "SELECT id, codigo_interno, nome, telefone, contato_opcional, "
        + "observacao_operacional, ativo, criado_em_utc, atualizado_em_utc FROM cliente";
  }

  private Client client(ResultSet result) throws SQLException {
    return new Client(
        UUID.fromString(result.getString("id")),
        result.getString("codigo_interno"),
        result.getString("nome"),
        result.getString("telefone"),
        result.getString("contato_opcional"),
        result.getString("observacao_operacional"),
        result.getInt("ativo") == 1,
        Instant.parse(result.getString("criado_em_utc")),
        Instant.parse(result.getString("atualizado_em_utc")));
  }

  private String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private String digits(String value) {
    return value == null ? "" : value.replaceAll("\\D", "");
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
