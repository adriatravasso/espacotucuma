package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.Page;
import br.com.espacotucuma.desktop.application.port.service.ServiceRepository;
import br.com.espacotucuma.desktop.application.service.BeautyService;
import br.com.espacotucuma.desktop.application.service.ServiceChange;
import br.com.espacotucuma.desktop.application.service.ServicePageQuery;
import br.com.espacotucuma.desktop.domain.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SqliteServiceRepository implements ServiceRepository {
  private static final Pattern SUMMARY_FIELD =
      Pattern.compile("\"resumo\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
  private final Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteServiceRepository(Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public Page<BeautyService> searchPage(ServicePageQuery query) {
    Objects.requireNonNull(query, "query");
    String condition =
        "(? = '' OR nome LIKE ? ESCAPE '\\' OR categoria LIKE ? ESCAPE '\\') "
            + "AND (? = 'ALL' OR categoria = ? COLLATE NOCASE) AND (? = 1 OR ativo = 1)";
    String term = "%" + escapeLike(query.searchText()) + "%";
    String order =
        switch (query.sortField()) {
          case NAME -> "nome COLLATE NOCASE";
          case CATEGORY -> "categoria COLLATE NOCASE, nome COLLATE NOCASE";
          case PRICE -> "preco_centavos";
        };
    if (!query.ascending()) order += " DESC";
    try {
      long total;
      try (PreparedStatement count =
          connection.prepareStatement("SELECT COUNT(*) FROM servico WHERE " + condition)) {
        bindCondition(count, query, term);
        try (ResultSet row = count.executeQuery()) {
          row.next();
          total = row.getLong(1);
        }
      }
      List<BeautyService> items = new ArrayList<>();
      try (PreparedStatement select =
          connection.prepareStatement(
              "SELECT id,nome,categoria,duracao_minutos,preco_centavos,comissao_pontos_base,ativo,criado_em_utc,atualizado_em_utc "
                  + "FROM servico WHERE "
                  + condition
                  + " ORDER BY "
                  + order
                  + ", id LIMIT ? OFFSET ?")) {
        bindCondition(select, query, term);
        select.setInt(7, query.pageSize());
        select.setLong(8, (long) query.pageIndex() * query.pageSize());
        try (ResultSet rows = select.executeQuery()) {
          while (rows.next()) items.add(readService(rows));
        }
      }
      return new Page<>(items, total, query.pageIndex(), query.pageSize());
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "Não foi possível consultar o catálogo de serviços.", failure);
    }
  }

  @Override
  public List<String> categories() {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT DISTINCT categoria FROM servico ORDER BY categoria COLLATE NOCASE")) {
      List<String> categories = new ArrayList<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) categories.add(rows.getString(1));
      }
      return List.copyOf(categories);
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "Não foi possível consultar categorias de serviços.", failure);
    }
  }

  @Override
  public Optional<BeautyService> findById(UUID id) {
    Objects.requireNonNull(id, "id");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,nome,categoria,duracao_minutos,preco_centavos,comissao_pontos_base,ativo,criado_em_utc,atualizado_em_utc "
                + "FROM servico WHERE id = ?")) {
      statement.setString(1, id.toString());
      try (ResultSet row = statement.executeQuery()) {
        return row.next() ? Optional.of(readService(row)) : Optional.empty();
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível consultar o serviço.", failure);
    }
  }

  @Override
  public List<ServiceChange> changeHistory(UUID serviceId) {
    Objects.requireNonNull(serviceId, "serviceId");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id,acao,ocorrido_em_utc,detalhes_json FROM evento_auditoria "
                + "WHERE entidade='servico' AND entidade_id=? ORDER BY ocorrido_em_utc DESC, rowid DESC")) {
      statement.setString(1, serviceId.toString());
      List<ServiceChange> changes = new ArrayList<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          changes.add(
              new ServiceChange(
                  UUID.fromString(rows.getString("id")),
                  rows.getString("acao"),
                  Instant.parse(rows.getString("ocorrido_em_utc")),
                  summary(rows.getString("detalhes_json"))));
        }
      }
      return List.copyOf(changes);
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "Não foi possível consultar o histórico do serviço.", failure);
    }
  }

  @Override
  public void insert(BeautyService service) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO servico (id,nome,categoria,duracao_minutos,preco_centavos,comissao_pontos_base,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,?,?,?,?,?,?,?,?)")) {
      bind(statement, service);
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível cadastrar o serviço.", failure);
    }
  }

  @Override
  public void update(BeautyService service) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE servico SET nome=?,categoria=?,duracao_minutos=?,preco_centavos=?,comissao_pontos_base=?,atualizado_em_utc=? WHERE id=? AND ativo=1")) {
      statement.setString(1, service.name());
      statement.setString(2, service.category());
      statement.setInt(3, service.durationMinutes());
      statement.setLong(4, cents(service));
      statement.setInt(5, service.commissionBasisPoints());
      statement.setString(6, service.updatedAt().toString());
      statement.setString(7, service.id().toString());
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException("O serviço não existe ou está inativo.");
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível atualizar o serviço.", failure);
    }
  }

  @Override
  public void deactivate(UUID id, Instant at) {
    requireTransaction();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE servico SET ativo=0,atualizado_em_utc=? WHERE id=? AND ativo=1")) {
      statement.setString(1, at.toString());
      statement.setString(2, id.toString());
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException("O serviço não existe ou já está inativo.");
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível inativar o serviço.", failure);
    }
  }

  private void bindCondition(PreparedStatement statement, ServicePageQuery query, String term)
      throws SQLException {
    statement.setString(1, query.searchText());
    statement.setString(2, term);
    statement.setString(3, term);
    statement.setString(4, query.category());
    statement.setString(5, query.category());
    statement.setInt(6, query.includeInactive() ? 1 : 0);
  }

  private BeautyService readService(ResultSet row) throws SQLException {
    return new BeautyService(
        UUID.fromString(row.getString("id")),
        row.getString("nome"),
        row.getString("categoria"),
        row.getInt("duracao_minutos"),
        new Money(BigDecimal.valueOf(row.getLong("preco_centavos"), 2)),
        row.getInt("comissao_pontos_base"),
        row.getInt("ativo") == 1,
        Instant.parse(row.getString("criado_em_utc")),
        Instant.parse(row.getString("atualizado_em_utc")));
  }

  private void bind(PreparedStatement statement, BeautyService service) throws SQLException {
    statement.setString(1, service.id().toString());
    statement.setString(2, service.name());
    statement.setString(3, service.category());
    statement.setInt(4, service.durationMinutes());
    statement.setLong(5, cents(service));
    statement.setInt(6, service.commissionBasisPoints());
    statement.setInt(7, service.active() ? 1 : 0);
    statement.setString(8, service.createdAt().toString());
    statement.setString(9, service.updatedAt().toString());
  }

  private long cents(BeautyService service) {
    return service.price().amount().movePointRight(2).longValueExact();
  }

  private String summary(String details) {
    Matcher matcher = SUMMARY_FIELD.matcher(details);
    if (!matcher.find()) return "";
    return matcher.group(1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
  }

  private String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private void requireTransaction() {
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException("A alteração do catálogo exige uma transação SQLite.");
    }
  }
}
