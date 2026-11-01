package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.Page;
import br.com.espacotucuma.desktop.application.inventory.*;
import br.com.espacotucuma.desktop.application.port.inventory.InventoryRepository;
import br.com.espacotucuma.desktop.domain.money.Money;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class SqliteInventoryRepository implements InventoryRepository {
  private final Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteInventoryRepository(Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection);
    this.unitOfWork = Objects.requireNonNull(unitOfWork);
  }

  @Override
  public Page<StockProduct> searchProducts(ProductPageQuery query) {
    String condition =
        "(?='' OR p.nome LIKE ? ESCAPE '\\' OR COALESCE(p.sku,'') LIKE ? ESCAPE '\\' OR p.categoria LIKE ? ESCAPE '\\' OR COALESCE(f.nome,'') LIKE ? ESCAPE '\\') AND (?='ALL' OR p.categoria=? COLLATE NOCASE) AND (?=1 OR p.ativo=1)";
    String term =
        "%"
            + query.searchText().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            + "%";
    String order =
        switch (query.sortField()) {
          case CATEGORY -> "p.categoria COLLATE NOCASE,p.nome COLLATE NOCASE";
          case ON_HAND -> "saldo";
          case UNIT_COST -> "p.custo_unitario_centavos";
          default -> "p.nome COLLATE NOCASE";
        };
    if (!query.ascending()) order += " DESC";
    String base =
        " FROM produto p LEFT JOIN fornecedor f ON f.id=p.fornecedor_id LEFT JOIN (SELECT produto_id,SUM(CASE WHEN tipo='ENTRADA' OR (tipo='AJUSTE' AND direcao_ajuste='ENTRADA') THEN quantidade_milesimos WHEN tipo IN ('SAIDA','CONSUMO_SERVICO') OR (tipo='AJUSTE' AND direcao_ajuste='SAIDA') THEN -quantidade_milesimos ELSE 0 END) saldo FROM movimento_estoque GROUP BY produto_id) m ON m.produto_id=p.id WHERE "
            + condition
            + " AND (?=0 OR COALESCE(m.saldo,0)<=p.estoque_minimo_milesimos)";
    try {
      long total;
      try (PreparedStatement s = connection.prepareStatement("SELECT COUNT(*)" + base)) {
        bindQuery(s, query, term);
        try (ResultSet r = s.executeQuery()) {
          r.next();
          total = r.getLong(1);
        }
      }
      List<StockProduct> items = new ArrayList<>();
      String sql =
          "SELECT p.id,p.fornecedor_id,p.sku,p.nome,p.categoria,p.unidade,p.custo_unitario_centavos,p.preco_venda_centavos,p.estoque_minimo_milesimos,p.ativo,p.criado_em_utc,p.atualizado_em_utc,COALESCE(f.nome,'—') fornecedor,COALESCE(m.saldo,0) saldo"
              + base
              + " ORDER BY "
              + order
              + ",p.id LIMIT ? OFFSET ?";
      try (PreparedStatement s = connection.prepareStatement(sql)) {
        bindQuery(s, query, term);
        s.setInt(10, query.pageSize());
        s.setLong(11, (long) query.pageIndex() * query.pageSize());
        try (ResultSet r = s.executeQuery()) {
          while (r.next())
            items.add(
                new StockProduct(readProduct(r), r.getString("fornecedor"), r.getLong("saldo")));
        }
      }
      return new Page<>(items, total, query.pageIndex(), query.pageSize());
    } catch (SQLException e) {
      throw failure("consultar o estoque", e);
    }
  }

  private void bindQuery(PreparedStatement s, ProductPageQuery q, String term) throws SQLException {
    s.setString(1, q.searchText());
    for (int i = 2; i <= 5; i++) s.setString(i, term);
    s.setString(6, q.category());
    s.setString(7, q.category());
    s.setInt(8, q.includeInactive() ? 1 : 0);
    s.setInt(9, q.belowMinimumOnly() ? 1 : 0);
  }

  @Override
  public List<String> categories() {
    try (PreparedStatement s =
            connection.prepareStatement(
                "SELECT DISTINCT categoria FROM produto ORDER BY categoria COLLATE NOCASE");
        ResultSet r = s.executeQuery()) {
      List<String> out = new ArrayList<>();
      while (r.next()) out.add(r.getString(1));
      return List.copyOf(out);
    } catch (SQLException e) {
      throw failure("consultar categorias de produtos", e);
    }
  }

  @Override
  public List<Supplier> suppliers(boolean includeInactive) {
    try (PreparedStatement s =
        connection.prepareStatement(
            "SELECT * FROM fornecedor WHERE (?=1 OR ativo=1) ORDER BY nome COLLATE NOCASE")) {
      s.setInt(1, includeInactive ? 1 : 0);
      try (ResultSet r = s.executeQuery()) {
        List<Supplier> out = new ArrayList<>();
        while (r.next()) out.add(readSupplier(r));
        return List.copyOf(out);
      }
    } catch (SQLException e) {
      throw failure("consultar fornecedores", e);
    }
  }

  @Override
  public Optional<Supplier> findSupplier(UUID id) {
    try (PreparedStatement s = connection.prepareStatement("SELECT * FROM fornecedor WHERE id=?")) {
      s.setString(1, id.toString());
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? Optional.of(readSupplier(r)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw failure("consultar fornecedor", e);
    }
  }

  @Override
  public Optional<Product> findProduct(UUID id) {
    try (PreparedStatement s = connection.prepareStatement("SELECT * FROM produto WHERE id=?")) {
      s.setString(1, id.toString());
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? Optional.of(readProduct(r)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw failure("consultar produto", e);
    }
  }

  @Override
  public Optional<StockEntry> findStockEntryByKey(String key) {
    try (PreparedStatement s =
        connection.prepareStatement("SELECT * FROM movimento_estoque WHERE chave_idempotencia=?")) {
      s.setString(1, key);
      try (ResultSet r = s.executeQuery()) {
        return r.next()
            ? Optional.of(
                new StockEntry(
                    UUID.fromString(r.getString("id")),
                    UUID.fromString(r.getString("produto_id")),
                    r.getLong("quantidade_milesimos"),
                    r.getLong("custo_unitario_centavos"),
                    r.getString("motivo"),
                    key,
                    UUID.fromString(r.getString("criado_por_usuario_id")),
                    Instant.parse(r.getString("criado_em_utc"))))
            : Optional.empty();
      }
    } catch (SQLException e) {
      throw failure("consultar entrada de estoque", e);
    }
  }

  @Override
  public boolean hasStockMovements(UUID id) {
    try (PreparedStatement s =
        connection.prepareStatement("SELECT 1 FROM movimento_estoque WHERE produto_id=? LIMIT 1")) {
      s.setString(1, id.toString());
      try (ResultSet r = s.executeQuery()) {
        return r.next();
      }
    } catch (SQLException e) {
      throw failure("consultar movimentações", e);
    }
  }

  @Override
  public long stockOnHand(UUID productId) {
    try (PreparedStatement s =
        connection.prepareStatement(
            "SELECT COALESCE(SUM(CASE WHEN tipo='ENTRADA' OR (tipo='AJUSTE' AND direcao_ajuste='ENTRADA') THEN quantidade_milesimos WHEN tipo IN ('SAIDA','CONSUMO_SERVICO') OR (tipo='AJUSTE' AND direcao_ajuste='SAIDA') THEN -quantidade_milesimos ELSE 0 END),0) FROM movimento_estoque WHERE produto_id=?")) {
      s.setString(1, productId.toString());
      try (ResultSet r = s.executeQuery()) {
        r.next();
        long balance = r.getLong(1);
        if (balance < 0)
          throw new IllegalStateException("O histórico de estoque contém um saldo negativo.");
        return balance;
      }
    } catch (SQLException e) {
      throw failure("calcular saldo de estoque", e);
    }
  }

  @Override
  public List<StockMovement> movementHistory(UUID productId) {
    try (PreparedStatement s =
        connection.prepareStatement(
            "SELECT * FROM movimento_estoque WHERE produto_id=? ORDER BY criado_em_utc DESC,id")) {
      s.setString(1, productId.toString());
      try (ResultSet r = s.executeQuery()) {
        List<StockMovement> movements = new ArrayList<>();
        while (r.next()) movements.add(readMovement(r));
        return List.copyOf(movements);
      }
    } catch (SQLException e) {
      throw failure("consultar histórico de estoque", e);
    }
  }

  @Override
  public Optional<StockMovement> findMovementByKey(String key) {
    return findMovement("chave_idempotencia", key);
  }

  @Override
  public Optional<StockMovement> findMovementById(UUID id) {
    return findMovement("id", id.toString());
  }

  @Override
  public Optional<StockMovement> findReversal(UUID originalMovementId) {
    return findMovement("movimento_origem_id", originalMovementId.toString());
  }

  private Optional<StockMovement> findMovement(String field, String value) {
    try (PreparedStatement s =
        connection.prepareStatement("SELECT * FROM movimento_estoque WHERE " + field + "=?")) {
      s.setString(1, value);
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? Optional.of(readMovement(r)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw failure("consultar movimento de estoque", e);
    }
  }

  @Override
  public List<ServiceMaterial> serviceMaterials(UUID serviceId) {
    try (PreparedStatement s =
        connection.prepareStatement(
            "SELECT m.servico_id,m.produto_id,s.nome servico,p.nome produto,m.quantidade_milesimos FROM material_servico m JOIN servico s ON s.id=m.servico_id JOIN produto p ON p.id=m.produto_id WHERE m.servico_id=? AND m.ativo=1 ORDER BY p.nome COLLATE NOCASE")) {
      s.setString(1, serviceId.toString());
      try (ResultSet r = s.executeQuery()) {
        List<ServiceMaterial> materials = new ArrayList<>();
        while (r.next()) materials.add(readMaterial(r));
        return List.copyOf(materials);
      }
    } catch (SQLException e) {
      throw failure("consultar materiais do serviço", e);
    }
  }

  @Override
  public List<ServiceMaterial> serviceMaterialsForActiveServices(List<UUID> serviceIds) {
    if (serviceIds.isEmpty()) return List.of();
    String placeholders = String.join(",", Collections.nCopies(serviceIds.size(), "?"));
    try (PreparedStatement s =
        connection.prepareStatement(
            "SELECT m.servico_id,m.produto_id,s.nome servico,p.nome produto,m.quantidade_milesimos FROM material_servico m JOIN servico s ON s.id=m.servico_id JOIN produto p ON p.id=m.produto_id WHERE m.ativo=1 AND m.servico_id IN ("
                + placeholders
                + ") ORDER BY m.servico_id,p.nome COLLATE NOCASE")) {
      for (int i = 0; i < serviceIds.size(); i++) s.setString(i + 1, serviceIds.get(i).toString());
      try (ResultSet r = s.executeQuery()) {
        List<ServiceMaterial> materials = new ArrayList<>();
        while (r.next()) materials.add(readMaterial(r));
        return List.copyOf(materials);
      }
    } catch (SQLException e) {
      throw failure("consultar materiais de atendimento", e);
    }
  }

  @Override
  public boolean serviceIsActive(UUID serviceId) {
    try (PreparedStatement s =
        connection.prepareStatement("SELECT 1 FROM servico WHERE id=? AND ativo=1")) {
      s.setString(1, serviceId.toString());
      try (ResultSet r = s.executeQuery()) {
        return r.next();
      }
    } catch (SQLException e) {
      throw failure("validar serviço", e);
    }
  }

  @Override
  public void saveServiceMaterial(
      UUID serviceId, UUID productId, long quantityMilliUnits, Instant at) {
    requireTx();
    try (PreparedStatement s =
        connection.prepareStatement(
            "INSERT INTO material_servico(id,servico_id,produto_id,quantidade_milesimos,ativo,criado_em_utc,atualizado_em_utc) VALUES(?,?,?,?,1,?,?) ON CONFLICT(servico_id,produto_id) DO UPDATE SET quantidade_milesimos=excluded.quantidade_milesimos,ativo=1,atualizado_em_utc=excluded.atualizado_em_utc")) {
      s.setString(1, UUID.randomUUID().toString());
      s.setString(2, serviceId.toString());
      s.setString(3, productId.toString());
      s.setLong(4, quantityMilliUnits);
      s.setString(5, at.toString());
      s.setString(6, at.toString());
      s.executeUpdate();
    } catch (SQLException e) {
      throw failure("configurar material do serviço", e);
    }
  }

  @Override
  public void deactivateServiceMaterial(UUID serviceId, UUID productId, Instant at) {
    requireTx();
    try (PreparedStatement s =
        connection.prepareStatement(
            "UPDATE material_servico SET ativo=0,atualizado_em_utc=? WHERE servico_id=? AND produto_id=? AND ativo=1")) {
      s.setString(1, at.toString());
      s.setString(2, serviceId.toString());
      s.setString(3, productId.toString());
      s.executeUpdate();
    } catch (SQLException e) {
      throw failure("remover material do serviço", e);
    }
  }

  @Override
  public void insertSupplier(Supplier x) {
    requireTx();
    try (PreparedStatement s =
        connection.prepareStatement(
            "INSERT INTO fornecedor(id,nome,telefone,email,ativo,criado_em_utc) VALUES(?,?,?,?,?,?)")) {
      bindSupplier(s, x);
      s.executeUpdate();
    } catch (SQLException e) {
      throw failure("cadastrar fornecedor", e);
    }
  }

  @Override
  public void updateSupplier(Supplier x) {
    requireTx();
    try (PreparedStatement s =
        connection.prepareStatement(
            "UPDATE fornecedor SET nome=?,telefone=?,email=? WHERE id=? AND ativo=1")) {
      s.setString(1, x.name());
      s.setString(2, x.phone());
      s.setString(3, x.email());
      s.setString(4, x.id().toString());
      if (s.executeUpdate() != 1)
        throw new IllegalStateException("O fornecedor não existe ou está inativo.");
    } catch (SQLException e) {
      throw failure("atualizar fornecedor", e);
    }
  }

  @Override
  public void deactivateSupplier(UUID id) {
    requireTx();
    try (PreparedStatement s =
        connection.prepareStatement("UPDATE fornecedor SET ativo=0 WHERE id=? AND ativo=1")) {
      s.setString(1, id.toString());
      if (s.executeUpdate() != 1)
        throw new IllegalStateException("O fornecedor não existe ou já está inativo.");
    } catch (SQLException e) {
      throw failure("inativar fornecedor", e);
    }
  }

  @Override
  public void insertProduct(Product x) {
    requireTx();
    try (PreparedStatement s =
        connection.prepareStatement(
            "INSERT INTO produto(id,fornecedor_id,sku,nome,categoria,unidade,custo_unitario_centavos,preco_venda_centavos,estoque_minimo_milesimos,ativo,criado_em_utc,atualizado_em_utc) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
      bindProduct(s, x);
      s.executeUpdate();
    } catch (SQLException e) {
      throw failure("cadastrar produto", e);
    }
  }

  @Override
  public void updateProduct(Product x) {
    requireTx();
    try (PreparedStatement s =
        connection.prepareStatement(
            "UPDATE produto SET fornecedor_id=?,sku=?,nome=?,categoria=?,unidade=?,custo_unitario_centavos=?,preco_venda_centavos=?,estoque_minimo_milesimos=?,atualizado_em_utc=? WHERE id=? AND ativo=1")) {
      s.setString(1, x.supplierId() == null ? null : x.supplierId().toString());
      s.setString(2, x.sku());
      s.setString(3, x.name());
      s.setString(4, x.category());
      s.setString(5, x.unit().name());
      s.setLong(6, cents(x.unitCost()));
      setNullableLong(s, 7, x.salePrice() == null ? null : cents(x.salePrice()));
      s.setLong(8, x.minimumStockMilliUnits());
      s.setString(9, x.updatedAt().toString());
      s.setString(10, x.id().toString());
      if (s.executeUpdate() != 1)
        throw new IllegalStateException("O produto não existe ou está inativo.");
    } catch (SQLException e) {
      throw failure("atualizar produto", e);
    }
  }

  @Override
  public void deactivateProduct(UUID id, Instant at) {
    requireTx();
    try (PreparedStatement s =
        connection.prepareStatement(
            "UPDATE produto SET ativo=0,atualizado_em_utc=? WHERE id=? AND ativo=1")) {
      s.setString(1, at.toString());
      s.setString(2, id.toString());
      if (s.executeUpdate() != 1)
        throw new IllegalStateException("O produto não existe ou já está inativo.");
    } catch (SQLException e) {
      throw failure("inativar produto", e);
    }
  }

  @Override
  public void insertStockEntry(StockEntry x) {
    requireTx();
    try (PreparedStatement s =
        connection.prepareStatement(
            "INSERT INTO movimento_estoque(id,produto_id,atendimento_id,tipo,quantidade_milesimos,custo_unitario_centavos,motivo,criado_por_usuario_id,criado_em_utc,chave_idempotencia) VALUES(?,?,NULL,'ENTRADA',?,?,?,?,?,?)")) {
      s.setString(1, x.id().toString());
      s.setString(2, x.productId().toString());
      s.setLong(3, x.quantityMilliUnits());
      s.setLong(4, x.unitCostCents());
      s.setString(5, x.reason());
      s.setString(6, x.createdBy().toString());
      s.setString(7, x.createdAt().toString());
      s.setString(8, x.idempotencyKey());
      s.executeUpdate();
    } catch (SQLException e) {
      throw failure("registrar entrada", e);
    }
  }

  @Override
  public void insertMovement(StockMovement movement) {
    requireTx();
    String sql =
        "INSERT INTO movimento_estoque(id,produto_id,atendimento_id,tipo,quantidade_milesimos,custo_unitario_centavos,motivo,criado_por_usuario_id,criado_em_utc,chave_idempotencia,direcao_ajuste,atendimento_servico_id,movimento_origem_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)";
    try (PreparedStatement s = connection.prepareStatement(sql)) {
      s.setString(1, movement.id().toString());
      s.setString(2, movement.productId().toString());
      s.setString(3, movement.attendanceId() == null ? null : movement.attendanceId().toString());
      s.setString(4, movement.type().name());
      s.setLong(5, movement.quantityMilliUnits());
      s.setLong(6, movement.unitCostCents());
      s.setString(7, movement.reason());
      s.setString(8, movement.createdBy().toString());
      s.setString(9, movement.createdAt().toString());
      s.setString(10, movement.idempotencyKey());
      s.setString(
          11,
          movement.adjustmentDirection() == null ? null : movement.adjustmentDirection().name());
      s.setString(
          12,
          movement.attendanceServiceLineId() == null
              ? null
              : movement.attendanceServiceLineId().toString());
      s.setString(
          13,
          movement.reversesMovementId() == null ? null : movement.reversesMovementId().toString());
      s.executeUpdate();
    } catch (SQLException e) {
      throw failure("registrar movimento de estoque", e);
    }
  }

  private void bindSupplier(PreparedStatement s, Supplier x) throws SQLException {
    s.setString(1, x.id().toString());
    s.setString(2, x.name());
    s.setString(3, x.phone());
    s.setString(4, x.email());
    s.setInt(5, x.active() ? 1 : 0);
    s.setString(6, x.createdAt().toString());
  }

  private void bindProduct(PreparedStatement s, Product x) throws SQLException {
    s.setString(1, x.id().toString());
    s.setString(2, x.supplierId() == null ? null : x.supplierId().toString());
    s.setString(3, x.sku());
    s.setString(4, x.name());
    s.setString(5, x.category());
    s.setString(6, x.unit().name());
    s.setLong(7, cents(x.unitCost()));
    setNullableLong(s, 8, x.salePrice() == null ? null : cents(x.salePrice()));
    s.setLong(9, x.minimumStockMilliUnits());
    s.setInt(10, x.active() ? 1 : 0);
    s.setString(11, x.createdAt().toString());
    s.setString(12, x.updatedAt().toString());
  }

  private Product readProduct(ResultSet r) throws SQLException {
    return new Product(
        UUID.fromString(r.getString("id")),
        uuid(r.getString("fornecedor_id")),
        r.getString("sku"),
        r.getString("nome"),
        r.getString("categoria"),
        UnitOfMeasure.valueOf(r.getString("unidade")),
        new Money(BigDecimal.valueOf(r.getLong("custo_unitario_centavos"), 2)),
        nullableMoney(r, "preco_venda_centavos"),
        r.getLong("estoque_minimo_milesimos"),
        r.getInt("ativo") == 1,
        Instant.parse(r.getString("criado_em_utc")),
        Instant.parse(r.getString("atualizado_em_utc")));
  }

  private StockMovement readMovement(ResultSet r) throws SQLException {
    String direction = r.getString("direcao_ajuste");
    return new StockMovement(
        UUID.fromString(r.getString("id")),
        UUID.fromString(r.getString("produto_id")),
        uuid(r.getString("atendimento_id")),
        uuid(r.getString("atendimento_servico_id")),
        StockMovementType.valueOf(r.getString("tipo")),
        direction == null ? null : AdjustmentDirection.valueOf(direction),
        r.getLong("quantidade_milesimos"),
        r.getLong("custo_unitario_centavos"),
        r.getString("motivo"),
        r.getString("chave_idempotencia"),
        uuid(r.getString("movimento_origem_id")),
        UUID.fromString(r.getString("criado_por_usuario_id")),
        Instant.parse(r.getString("criado_em_utc")));
  }

  private ServiceMaterial readMaterial(ResultSet r) throws SQLException {
    return new ServiceMaterial(
        UUID.fromString(r.getString("servico_id")),
        UUID.fromString(r.getString("produto_id")),
        r.getString("servico"),
        r.getString("produto"),
        r.getLong("quantidade_milesimos"));
  }

  private Supplier readSupplier(ResultSet r) throws SQLException {
    return new Supplier(
        UUID.fromString(r.getString("id")),
        r.getString("nome"),
        r.getString("telefone"),
        r.getString("email"),
        r.getInt("ativo") == 1,
        Instant.parse(r.getString("criado_em_utc")));
  }

  private Money nullableMoney(ResultSet r, String c) throws SQLException {
    long n = r.getLong(c);
    return r.wasNull() ? null : new Money(BigDecimal.valueOf(n, 2));
  }

  private UUID uuid(String value) {
    return value == null ? null : UUID.fromString(value);
  }

  private long cents(Money m) {
    return m.amount().movePointRight(2).longValueExact();
  }

  private void setNullableLong(PreparedStatement s, int i, Long n) throws SQLException {
    if (n == null) s.setNull(i, Types.INTEGER);
    else s.setLong(i, n);
  }

  private void requireTx() {
    if (!unitOfWork.isExecutingOnCurrentThread())
      throw new IllegalStateException("A alteração do estoque exige uma transação SQLite.");
  }

  private IllegalStateException failure(String action, SQLException e) {
    return new IllegalStateException("Não foi possível " + action + ".", e);
  }
}
