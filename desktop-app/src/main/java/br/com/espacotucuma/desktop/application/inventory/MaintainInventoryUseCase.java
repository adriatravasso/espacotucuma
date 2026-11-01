package br.com.espacotucuma.desktop.application.inventory;

import br.com.espacotucuma.desktop.application.attendance.AttendanceServiceLine;
import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.port.audit.AuditRepository;
import br.com.espacotucuma.desktop.application.port.inventory.InventoryRepository;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import br.com.espacotucuma.desktop.domain.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Catalog data and the first stock entry use the same local transaction and audit/outbox rules. */
public final class MaintainInventoryUseCase {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Manaus");
  private final UnitOfWork unitOfWork;
  private final InventoryRepository inventory;
  private final OutboxRepository outbox;
  private final AuthorizationService authorization;
  private final AuditRepository audit;
  private final UUID stationId;
  private final Clock clock;

  public MaintainInventoryUseCase(
      UnitOfWork unitOfWork,
      InventoryRepository inventory,
      OutboxRepository outbox,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID stationId) {
    this(
        unitOfWork,
        inventory,
        outbox,
        authorization,
        audit,
        stationId,
        Clock.system(BUSINESS_ZONE));
  }

  public MaintainInventoryUseCase(
      UnitOfWork unitOfWork,
      InventoryRepository inventory,
      OutboxRepository outbox,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID stationId,
      Clock clock) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork);
    this.inventory = Objects.requireNonNull(inventory);
    this.outbox = Objects.requireNonNull(outbox);
    this.authorization = Objects.requireNonNull(authorization);
    this.audit = Objects.requireNonNull(audit);
    this.stationId = Objects.requireNonNull(stationId);
    this.clock = Objects.requireNonNull(clock);
  }

  public Supplier registerSupplier(String name, String phone, String email) {
    var actor = authorization.requireUser(Permission.INVENTORY_MANAGE);
    Instant now = now();
    Supplier supplier = new Supplier(UUID.randomUUID(), name, phone, email, true, now);
    return unitOfWork.execute(
        () -> {
          inventory.insertSupplier(supplier);
          record(
              "fornecedor",
              supplier.id(),
              "FORNECEDOR_CADASTRADO",
              supplierPayload(supplier),
              "{\"nome\":\"" + escape(supplier.name()) + "\"}",
              actor.id(),
              now);
          return supplier;
        });
  }

  public Supplier updateSupplier(UUID supplierId, String name, String phone, String email) {
    var actor = authorization.requireUser(Permission.INVENTORY_MANAGE);
    Instant now = now();
    return unitOfWork.execute(
        () -> {
          Supplier previous =
              inventory
                  .findSupplier(Objects.requireNonNull(supplierId))
                  .orElseThrow(() -> new IllegalArgumentException("Fornecedor não encontrado."));
          if (!previous.active())
            throw new IllegalStateException("Reative o fornecedor antes de alterá-lo.");
          Supplier updated =
              new Supplier(previous.id(), name, phone, email, true, previous.createdAt());
          inventory.updateSupplier(updated);
          record(
              "fornecedor",
              updated.id(),
              "FORNECEDOR_ATUALIZADO",
              supplierPayload(updated),
              "{\"nomeAntes\":\""
                  + escape(previous.name())
                  + "\",\"nomeDepois\":\""
                  + escape(updated.name())
                  + "\"}",
              actor.id(),
              now);
          return updated;
        });
  }

  public void deactivateSupplier(UUID supplierId) {
    var actor = authorization.requireUser(Permission.INVENTORY_MANAGE);
    Instant now = now();
    unitOfWork.execute(
        () -> {
          Supplier previous =
              inventory
                  .findSupplier(Objects.requireNonNull(supplierId))
                  .orElseThrow(() -> new IllegalArgumentException("Fornecedor não encontrado."));
          if (!previous.active()) return null;
          inventory.deactivateSupplier(supplierId);
          Supplier inactive =
              new Supplier(
                  previous.id(),
                  previous.name(),
                  previous.phone(),
                  previous.email(),
                  false,
                  previous.createdAt());
          record(
              "fornecedor",
              supplierId,
              "FORNECEDOR_INATIVADO",
              supplierPayload(inactive),
              "{\"nome\":\"" + escape(previous.name()) + "\"}",
              actor.id(),
              now);
          return null;
        });
  }

  public Product registerProduct(
      UUID supplierId,
      String sku,
      String name,
      String category,
      UnitOfMeasure unit,
      Money cost,
      Money salePrice,
      long minimumStockMilliUnits) {
    var actor = authorization.requireUser(Permission.INVENTORY_MANAGE);
    Instant now = now();
    Product product =
        new Product(
            UUID.randomUUID(),
            supplierId,
            sku,
            name,
            category,
            unit,
            cost,
            salePrice,
            minimumStockMilliUnits,
            true,
            now,
            now);
    return unitOfWork.execute(
        () -> {
          requireActiveSupplier(supplierId);
          inventory.insertProduct(product);
          record(
              "produto",
              product.id(),
              "PRODUTO_CADASTRADO",
              productPayload(product),
              "{\"nome\":\""
                  + escape(product.name())
                  + "\",\"categoria\":\""
                  + escape(product.category())
                  + "\"}",
              actor.id(),
              now);
          return product;
        });
  }

  public Product updateProduct(
      UUID productId,
      UUID supplierId,
      String sku,
      String name,
      String category,
      UnitOfMeasure unit,
      Money cost,
      Money salePrice,
      long minimumStockMilliUnits) {
    var actor = authorization.requireUser(Permission.INVENTORY_MANAGE);
    Instant now = now();
    return unitOfWork.execute(
        () -> {
          Product previous =
              inventory
                  .findProduct(Objects.requireNonNull(productId))
                  .orElseThrow(() -> new IllegalArgumentException("Produto não encontrado."));
          if (!previous.active())
            throw new IllegalStateException("Reative o produto antes de alterá-lo.");
          requireActiveSupplier(supplierId);
          if (previous.unit() != unit && inventory.hasStockMovements(productId))
            throw new IllegalStateException(
                "A unidade não pode mudar após existirem movimentações de estoque.");
          Product updated =
              new Product(
                  previous.id(),
                  supplierId,
                  sku,
                  name,
                  category,
                  unit,
                  cost,
                  salePrice,
                  minimumStockMilliUnits,
                  true,
                  previous.createdAt(),
                  previous.updatedAt().isAfter(now) ? previous.updatedAt() : now);
          inventory.updateProduct(updated);
          record(
              "produto",
              updated.id(),
              "PRODUTO_ATUALIZADO",
              productPayload(updated),
              "{\"nomeAntes\":\""
                  + escape(previous.name())
                  + "\",\"nomeDepois\":\""
                  + escape(updated.name())
                  + "\"}",
              actor.id(),
              now);
          return updated;
        });
  }

  public void deactivateProduct(UUID productId) {
    var actor = authorization.requireUser(Permission.INVENTORY_MANAGE);
    Instant now = now();
    unitOfWork.execute(
        () -> {
          Product previous =
              inventory
                  .findProduct(Objects.requireNonNull(productId))
                  .orElseThrow(() -> new IllegalArgumentException("Produto não encontrado."));
          if (!previous.active()) return null;
          inventory.deactivateProduct(productId, now);
          record(
              "produto",
              productId,
              "PRODUTO_INATIVADO",
              productPayload(
                  new Product(
                      previous.id(),
                      previous.supplierId(),
                      previous.sku(),
                      previous.name(),
                      previous.category(),
                      previous.unit(),
                      previous.unitCost(),
                      previous.salePrice(),
                      previous.minimumStockMilliUnits(),
                      false,
                      previous.createdAt(),
                      now)),
              "{\"nome\":\"" + escape(previous.name()) + "\"}",
              actor.id(),
              now);
          return null;
        });
  }

  public StockEntry registerEntry(
      UUID productId, long quantityMilliUnits, String reason, String idempotencyKey) {
    var actor = authorization.requireUser(Permission.INVENTORY_MANAGE);
    String key = required(idempotencyKey, 1, 120, "Informe uma chave de entrada válida.");
    String normalizedReason = required(reason, 2, 240, "Informe o motivo da entrada.");
    if (quantityMilliUnits <= 0)
      throw new IllegalArgumentException("A quantidade da entrada deve ser positiva.");
    Instant now = now();
    return unitOfWork.execute(
        () -> {
          var previous = inventory.findStockEntryByKey(key);
          if (previous.isPresent()) {
            StockEntry existing = previous.get();
            if (!existing.productId().equals(productId)
                || existing.quantityMilliUnits() != quantityMilliUnits
                || !existing.reason().equals(normalizedReason))
              throw new IllegalStateException(
                  "A chave de idempotência já foi usada para outra entrada.");
            return existing;
          }
          Product product =
              inventory
                  .findProduct(Objects.requireNonNull(productId))
                  .orElseThrow(() -> new IllegalArgumentException("Produto não encontrado."));
          if (!product.active())
            throw new IllegalStateException("Não é possível movimentar um produto inativo.");
          if (product.unit().integral() && quantityMilliUnits % 1000 != 0)
            throw new IllegalArgumentException("Informe quantidade inteira para esta unidade.");
          StockEntry entry =
              new StockEntry(
                  UUID.randomUUID(),
                  productId,
                  quantityMilliUnits,
                  cents(product.unitCost()),
                  normalizedReason,
                  key,
                  actor.id(),
                  now);
          inventory.insertStockEntry(entry);
          String payload = entryPayload(entry);
          record(
              "movimento_estoque",
              entry.id(),
              "ESTOQUE_ENTRADA_REGISTRADA",
              payload,
              "{\"produtoId\":\""
                  + productId
                  + "\",\"quantidadeMilesimos\":"
                  + quantityMilliUnits
                  + "}",
              actor.id(),
              now);
          return entry;
        });
  }

  public StockMovement registerExit(
      UUID productId, long quantityMilliUnits, String reason, String idempotencyKey) {
    return registerManualMovement(
        productId,
        StockMovementType.SAIDA,
        null,
        quantityMilliUnits,
        reason,
        idempotencyKey,
        null,
        null);
  }

  public StockMovement adjust(
      UUID productId, long signedQuantityMilliUnits, String reason, String idempotencyKey) {
    if (signedQuantityMilliUnits == 0 || signedQuantityMilliUnits == Long.MIN_VALUE)
      throw new IllegalArgumentException("Informe uma quantidade de ajuste diferente de zero.");
    AdjustmentDirection direction =
        signedQuantityMilliUnits > 0 ? AdjustmentDirection.ENTRADA : AdjustmentDirection.SAIDA;
    return registerManualMovement(
        productId,
        StockMovementType.AJUSTE,
        direction,
        Math.abs(signedQuantityMilliUnits),
        reason,
        idempotencyKey,
        null,
        null);
  }

  public StockMovement reverseMovement(UUID movementId, String reason, String idempotencyKey) {
    var actor = authorization.requireUser(Permission.INVENTORY_MANAGE);
    String key = required(idempotencyKey, 1, 120, "Informe uma chave idempotente válida.");
    String normalizedReason = required(reason, 2, 240, "Informe o motivo do estorno.");
    Instant at = now();
    return unitOfWork.execute(
        () -> {
          var existing = inventory.findReversal(Objects.requireNonNull(movementId));
          if (existing.isPresent()) {
            if (existing.get().idempotencyKey().equals(key)
                && existing.get().reason().equals(normalizedReason)) return existing.get();
            throw new IllegalStateException("Este movimento já possui um estorno.");
          }
          StockMovement original =
              inventory
                  .findMovementById(movementId)
                  .orElseThrow(() -> new IllegalArgumentException("Movimento não encontrado."));
          if (original.reversesMovementId() != null)
            throw new IllegalStateException("Um estorno não pode ser estornado novamente.");
          if (inventory.findMovementByKey(key).isPresent())
            throw new IllegalStateException("A chave idempotente já foi utilizada.");
          boolean originalAddsStock = addsStock(original);
          AdjustmentDirection inverse =
              originalAddsStock ? AdjustmentDirection.SAIDA : AdjustmentDirection.ENTRADA;
          if (inverse == AdjustmentDirection.SAIDA
              && inventory.stockOnHand(original.productId()) < original.quantityMilliUnits())
            throw new IllegalStateException("Saldo insuficiente para estornar este movimento.");
          StockMovement reversal =
              new StockMovement(
                  UUID.randomUUID(),
                  original.productId(),
                  null,
                  null,
                  StockMovementType.AJUSTE,
                  inverse,
                  original.quantityMilliUnits(),
                  original.unitCostCents(),
                  normalizedReason,
                  key,
                  original.id(),
                  actor.id(),
                  at);
          inventory.insertMovement(reversal);
          recordMovement(reversal, actor.id(), at, "ESTOQUE_MOVIMENTO_ESTORNADO");
          return reversal;
        });
  }

  public ServiceMaterial configureServiceMaterial(
      UUID serviceId, UUID productId, long quantityMilliUnits) {
    var actor = authorization.requireUser(Permission.INVENTORY_MANAGE);
    if (quantityMilliUnits <= 0)
      throw new IllegalArgumentException("A quantidade por serviço deve ser positiva.");
    Instant at = now();
    return unitOfWork.execute(
        () -> {
          if (!inventory.serviceIsActive(Objects.requireNonNull(serviceId)))
            throw new IllegalArgumentException("Selecione um serviço ativo.");
          Product product =
              inventory
                  .findProduct(Objects.requireNonNull(productId))
                  .orElseThrow(() -> new IllegalArgumentException("Produto não encontrado."));
          if (!product.active()) throw new IllegalArgumentException("Selecione um produto ativo.");
          if (product.unit().integral() && quantityMilliUnits % 1000 != 0)
            throw new IllegalArgumentException("A quantidade deve ser inteira para esta unidade.");
          inventory.saveServiceMaterial(serviceId, productId, quantityMilliUnits, at);
          UUID materialId =
              UUID.nameUUIDFromBytes(
                  (serviceId + ":" + productId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
          String payload =
              "{\"servicoId\":\""
                  + serviceId
                  + "\",\"produtoId\":\""
                  + productId
                  + "\",\"quantidadeMilesimos\":"
                  + quantityMilliUnits
                  + ",\"ativo\":true}";
          record(
              "material_servico",
              materialId,
              "MATERIAL_SERVICO_CONFIGURADO",
              payload,
              "{\"servicoId\":\"" + serviceId + "\",\"produtoId\":\"" + productId + "\"}",
              actor.id(),
              at);
          return inventory.serviceMaterials(serviceId).stream()
              .filter(m -> m.productId().equals(productId))
              .findFirst()
              .orElseThrow();
        });
  }

  public void removeServiceMaterial(UUID serviceId, UUID productId) {
    var actor = authorization.requireUser(Permission.INVENTORY_MANAGE);
    Instant at = now();
    unitOfWork.execute(
        () -> {
          inventory.deactivateServiceMaterial(serviceId, productId, at);
          UUID materialId =
              UUID.nameUUIDFromBytes(
                  (serviceId + ":" + productId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
          record(
              "material_servico",
              materialId,
              "MATERIAL_SERVICO_REMOVIDO",
              "{\"servicoId\":\""
                  + serviceId
                  + "\",\"produtoId\":\""
                  + productId
                  + "\",\"ativo\":false}",
              "{\"servicoId\":\"" + serviceId + "\",\"produtoId\":\"" + productId + "\"}",
              actor.id(),
              at);
          return null;
        });
  }

  /** Called only by attendance completion while its SQLite transaction is already active. */
  public java.util.List<StockMovement> consumeForAttendanceLine(
      UUID attendanceId, AttendanceServiceLine line, UUID actorId, Instant at) {
    var actor = authorization.requireUser(Permission.ATTENDANCE_RECORD);
    if (!actor.id().equals(actorId))
      throw new IllegalArgumentException("O consumo deve ser atribuído ao usuário da sessão.");
    java.util.List<ServiceMaterial> recipe =
        inventory.serviceMaterialsForActiveServices(java.util.List.of(line.serviceId()));
    java.util.List<StockMovement> applied = new java.util.ArrayList<>();
    for (ServiceMaterial material : recipe) {
      Product product = inventory.findProduct(material.productId()).orElseThrow();
      if (!product.active())
        throw new IllegalStateException("Um material configurado está inativo.");
      long requiredQuantity = Math.multiplyExact(material.quantityMilliUnits(), line.quantity());
      String key = "consumo:" + attendanceId + ":" + line.id() + ":" + product.id();
      var prior = inventory.findMovementByKey(key);
      if (prior.isPresent()) {
        StockMovement movement = prior.get();
        if (!movement.attendanceId().equals(attendanceId)
            || !movement.attendanceServiceLineId().equals(line.id())
            || movement.quantityMilliUnits() != requiredQuantity)
          throw new IllegalStateException("A chave do consumo aponta para outro movimento.");
        applied.add(movement);
        continue;
      }
      if (inventory.stockOnHand(product.id()) < requiredQuantity)
        throw new IllegalStateException("Saldo insuficiente para consumir " + product.name() + ".");
      StockMovement movement =
          new StockMovement(
              UUID.randomUUID(),
              product.id(),
              attendanceId,
              line.id(),
              StockMovementType.CONSUMO_SERVICO,
              null,
              requiredQuantity,
              cents(product.unitCost()),
              "Consumo: " + line.nameSnapshot(),
              key,
              null,
              actorId,
              at);
      inventory.insertMovement(movement);
      recordMovement(movement, actorId, at, "ESTOQUE_CONSUMIDO_POR_SERVICO");
      applied.add(movement);
    }
    return java.util.List.copyOf(applied);
  }

  private StockMovement registerManualMovement(
      UUID productId,
      StockMovementType type,
      AdjustmentDirection direction,
      long quantity,
      String reason,
      String idempotencyKey,
      UUID originalId,
      UUID attendanceId) {
    var actor = authorization.requireUser(Permission.INVENTORY_MANAGE);
    String key = required(idempotencyKey, 1, 120, "Informe uma chave idempotente válida.");
    String normalizedReason = required(reason, 2, 240, "Informe um motivo válido.");
    if (quantity <= 0) throw new IllegalArgumentException("A quantidade deve ser positiva.");
    Instant at = now();
    return unitOfWork.execute(
        () -> {
          Product product =
              inventory
                  .findProduct(Objects.requireNonNull(productId))
                  .orElseThrow(() -> new IllegalArgumentException("Produto não encontrado."));
          if (!product.active())
            throw new IllegalStateException("Não é possível movimentar um produto inativo.");
          if (product.unit().integral() && quantity % 1000 != 0)
            throw new IllegalArgumentException("Informe quantidade inteira para esta unidade.");
          var previous = inventory.findMovementByKey(key);
          if (previous.isPresent()) {
            StockMovement p = previous.get();
            if (p.productId().equals(productId)
                && p.type() == type
                && p.quantityMilliUnits() == quantity
                && p.reason().equals(normalizedReason)
                && p.adjustmentDirection() == direction) return p;
            throw new IllegalStateException(
                "A chave idempotente já foi usada para outro movimento.");
          }
          if ((type == StockMovementType.SAIDA || direction == AdjustmentDirection.SAIDA)
              && inventory.stockOnHand(productId) < quantity)
            throw new IllegalStateException("Saldo insuficiente para esta movimentação.");
          StockMovement movement =
              new StockMovement(
                  UUID.randomUUID(),
                  productId,
                  attendanceId,
                  null,
                  type,
                  direction,
                  quantity,
                  cents(product.unitCost()),
                  normalizedReason,
                  key,
                  originalId,
                  actor.id(),
                  at);
          inventory.insertMovement(movement);
          recordMovement(movement, actor.id(), at, "ESTOQUE_" + type.name() + "_REGISTRADO");
          return movement;
        });
  }

  private boolean addsStock(StockMovement movement) {
    return movement.type() == StockMovementType.ENTRADA
        || (movement.type() == StockMovementType.AJUSTE
            && movement.adjustmentDirection() == AdjustmentDirection.ENTRADA);
  }

  private void recordMovement(StockMovement movement, UUID actorId, Instant at, String action) {
    String payload =
        "{\"id\":\""
            + movement.id()
            + "\",\"produtoId\":\""
            + movement.productId()
            + "\",\"atendimentoId\":"
            + (movement.attendanceId() == null ? "null" : "\"" + movement.attendanceId() + "\"")
            + ",\"tipo\":\""
            + movement.type().name()
            + "\",\"direcaoAjuste\":"
            + (movement.adjustmentDirection() == null
                ? "null"
                : "\"" + movement.adjustmentDirection().name() + "\"")
            + ",\"quantidadeMilesimos\":"
            + movement.quantityMilliUnits()
            + ",\"custoUnitarioCentavos\":"
            + movement.unitCostCents()
            + ",\"motivo\":\""
            + escape(movement.reason())
            + "\",\"chaveIdempotencia\":\""
            + escape(movement.idempotencyKey())
            + "\",\"movimentoOrigemId\":"
            + (movement.reversesMovementId() == null
                ? "null"
                : "\"" + movement.reversesMovementId() + "\"")
            + "}";
    record(
        "movimento_estoque",
        movement.id(),
        action,
        payload,
        "{\"produtoId\":\""
            + movement.productId()
            + "\",\"quantidadeMilesimos\":"
            + movement.quantityMilliUnits()
            + ",\"motivo\":\""
            + escape(movement.reason())
            + "\"}",
        actorId,
        at);
  }

  public void requireReadAccess() {
    authorization.require(Permission.INVENTORY_READ);
  }

  private void requireActiveSupplier(UUID supplierId) {
    if (supplierId == null) return;
    Supplier supplier =
        inventory
            .findSupplier(supplierId)
            .orElseThrow(() -> new IllegalArgumentException("Fornecedor não encontrado."));
    if (!supplier.active()) throw new IllegalArgumentException("Selecione um fornecedor ativo.");
  }

  private void record(
      String entity,
      UUID entityId,
      String action,
      String payload,
      String details,
      UUID actorId,
      Instant at) {
    UUID operationId = UUID.randomUUID();
    outbox.enqueue(
        new PendingOperation(
            operationId,
            entity,
            entityId,
            action,
            operationId.toString(),
            payload,
            1,
            outbox.expectedVersionForNext(entity, entityId),
            stationId,
            at,
            0));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actorId,
            operationId,
            action,
            entity,
            entityId,
            stationId,
            details,
            null,
            at));
  }

  private String productPayload(Product product) {
    return "{\"id\":\""
        + product.id()
        + "\",\"fornecedorId\":"
        + (product.supplierId() == null ? "null" : "\"" + product.supplierId() + "\"")
        + ",\"sku\":"
        + (product.sku() == null ? "null" : "\"" + escape(product.sku()) + "\"")
        + ",\"nome\":\""
        + escape(product.name())
        + "\",\"categoria\":\""
        + escape(product.category())
        + "\",\"unidade\":\""
        + product.unit().name()
        + "\",\"custoUnitarioCentavos\":"
        + cents(product.unitCost())
        + ",\"precoVendaCentavos\":"
        + (product.salePrice() == null ? "null" : cents(product.salePrice()))
        + ",\"estoqueMinimoMilesimos\":"
        + product.minimumStockMilliUnits()
        + ",\"ativo\":"
        + product.active()
        + "}";
  }

  private String supplierPayload(Supplier supplier) {
    return "{\"id\":\""
        + supplier.id()
        + "\",\"nome\":\""
        + escape(supplier.name())
        + "\",\"telefone\":"
        + (supplier.phone() == null ? "null" : "\"" + escape(supplier.phone()) + "\"")
        + ",\"email\":"
        + (supplier.email() == null ? "null" : "\"" + escape(supplier.email()) + "\"")
        + ",\"ativo\":"
        + supplier.active()
        + "}";
  }

  private String entryPayload(StockEntry entry) {
    return "{\"id\":\""
        + entry.id()
        + "\",\"produtoId\":\""
        + entry.productId()
        + "\",\"tipo\":\"ENTRADA\",\"quantidadeMilesimos\":"
        + entry.quantityMilliUnits()
        + ",\"custoUnitarioCentavos\":"
        + entry.unitCostCents()
        + ",\"motivo\":\""
        + escape(entry.reason())
        + "\",\"chaveIdempotencia\":\""
        + escape(entry.idempotencyKey())
        + "\"}";
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.SECONDS);
  }

  private long cents(Money value) {
    return value.amount().movePointRight(2).longValueExact();
  }

  private String escape(String text) {
    return text.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }

  private String required(String value, int min, int max, String message) {
    String normalized = Objects.requireNonNull(value).strip();
    if (normalized.length() < min || normalized.length() > max)
      throw new IllegalArgumentException(message);
    return normalized;
  }
}
