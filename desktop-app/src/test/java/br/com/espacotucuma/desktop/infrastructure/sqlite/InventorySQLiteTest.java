package br.com.espacotucuma.desktop.infrastructure.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import br.com.espacotucuma.desktop.application.identity.*;
import br.com.espacotucuma.desktop.application.inventory.*;
import br.com.espacotucuma.desktop.domain.money.Money;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InventorySQLiteTest {
  private static final UUID STATION = UUID.fromString("91fd36bb-78b7-4c44-9fbf-49316fc5299a");

  @Test
  void supplierStockEntryPrecisionMinimumAndInactiveHistoryArePreserved(@TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("inventory.sqlite"))) {
      var supplier =
          fixture.useCase.registerSupplier("Fornecedor Tucumã", null, "contato@example.test");
      var product =
          fixture.useCase.registerProduct(
              supplier.id(),
              "SKU-01",
              "Óleo vegetal",
              "Insumos",
              UnitOfMeasure.MILILITRO,
              Money.of("12.34"),
              Money.of("20.00"),
              6_000);
      fixture.useCase.registerEntry(
          product.id(), 1_500, "Compra fracionada", UUID.randomUUID().toString());

      String key = UUID.randomUUID().toString();
      var entry = fixture.useCase.registerEntry(product.id(), 4_000, "Compra local", key);
      assertEquals(entry, fixture.useCase.registerEntry(product.id(), 4_000, "Compra local", key));
      assertThrows(
          IllegalStateException.class,
          () -> fixture.useCase.registerEntry(product.id(), 3_000, "Outra compra", key));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM movimento_estoque WHERE chave_idempotencia='" + key + "'"));
      var stock = fixture.repository.searchProducts(fixture.query(false, true)).items().getFirst();
      assertEquals(5_500, stock.onHandMilliUnits());
      assertTrue(stock.belowMinimum());

      fixture.useCase.deactivateSupplier(supplier.id());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.useCase.updateProduct(
                  product.id(),
                  supplier.id(),
                  product.sku(),
                  product.name(),
                  product.category(),
                  product.unit(),
                  product.unitCost(),
                  product.salePrice(),
                  product.minimumStockMilliUnits()));
      fixture.useCase.deactivateProduct(product.id());
      var inactive =
          fixture.repository.searchProducts(fixture.query(true, false)).items().getFirst();
      assertFalse(inactive.product().active());
      assertEquals(5_500, inactive.onHandMilliUnits());
      assertEquals("Insumos", inactive.product().category());
    }
  }

  @Test
  void integralUnitsRejectFractionalStockAndMoneyRejectsSubCentPrecision(@TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("precision.sqlite"))) {
      assertThrows(ArithmeticException.class, () -> new Money(new java.math.BigDecimal("1.001")));
      var product =
          fixture.useCase.registerProduct(
              null,
              null,
              "Algodão",
              "Descartáveis",
              UnitOfMeasure.UNIDADE,
              Money.of("2.50"),
              null,
              2_000);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.useCase.registerEntry(
                  product.id(), 1_500, "Compra", UUID.randomUUID().toString()));
      assertTrue(
          fixture
              .repository
              .searchProducts(fixture.query(false, true))
              .items()
              .getFirst()
              .belowMinimum());
    }
  }

  @Test
  void exitsAdjustmentsAndCompensatingReversalsNeverCreateNegativeOrDuplicateStock(
      @TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("movement-reversal.sqlite"))) {
      var product =
          fixture.useCase.registerProduct(
              null, null, "Gaze", "Insumos", UnitOfMeasure.UNIDADE, Money.of("1.00"), null, 0);
      var receipt =
          fixture.useCase.registerEntry(
              product.id(), 10_000, "Compra", UUID.randomUUID().toString());
      var exit =
          fixture.useCase.registerExit(
              product.id(), 2_000, "Uso interno", UUID.randomUUID().toString());
      var adjustment =
          fixture.useCase.adjust(
              product.id(), -1_000, "Contagem física", UUID.randomUUID().toString());
      assertEquals(7_000, fixture.repository.stockOnHand(product.id()));
      assertThrows(
          IllegalStateException.class,
          () ->
              fixture.useCase.registerExit(
                  product.id(), 8_000, "Retirada", UUID.randomUUID().toString()));

      String reversalKey = UUID.randomUUID().toString();
      var reversal = fixture.useCase.reverseMovement(exit.id(), "Retirada cancelada", reversalKey);
      assertEquals(
          reversal, fixture.useCase.reverseMovement(exit.id(), "Retirada cancelada", reversalKey));
      assertEquals(9_000, fixture.repository.stockOnHand(product.id()));
      assertThrows(
          IllegalStateException.class,
          () ->
              fixture.useCase.reverseMovement(
                  exit.id(), "Duplicado", UUID.randomUUID().toString()));
      assertThrows(
          IllegalStateException.class,
          () ->
              fixture.useCase.reverseMovement(
                  receipt.id(), "Sem saldo suficiente", UUID.randomUUID().toString()));
      assertEquals(9_000, fixture.repository.stockOnHand(product.id()));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM movimento_estoque WHERE movimento_origem_id='"
                  + exit.id()
                  + "'"));
      assertThrows(
          java.sql.SQLException.class,
          () ->
              fixture.execute(
                  "UPDATE movimento_estoque SET quantidade_milesimos=1 WHERE id='"
                      + exit.id()
                      + "'"));
      assertThrows(
          java.sql.SQLException.class,
          () -> fixture.execute("DELETE FROM movimento_estoque WHERE id='" + exit.id() + "'"));
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final LocalDatabaseSession database;
    private final SqliteInventoryRepository repository;
    private final MaintainInventoryUseCase useCase;
    private final AuthorizationService authorization;
    private final SqliteUnitOfWork unitOfWork;

    Fixture(Path path) throws Exception {
      database = LocalDatabaseSession.open(path);
      unitOfWork = new SqliteUnitOfWork(database.connection());
      var audit = new SqliteAuditRepository(database.connection(), unitOfWork);
      new CreateFirstAdministratorUseCase(
              unitOfWork,
              new SqliteInitialSetupRepository(database.connection(), unitOfWork),
              new Pbkdf2PasswordHasher(),
              audit,
              STATION)
          .create(
              new InitialAdministratorSetup(
                  "Espaço Tucumã", "Admin Teste", "admin", "SenhaTesteOffline2026!".toCharArray()));
      try (Statement s = database.connection().createStatement();
          ResultSet r =
              s.executeQuery("SELECT id,nome,login FROM usuario WHERE perfil='ADMINISTRADOR'")) {
        assertTrue(r.next());
        SessionContext sessions = new SessionContext();
        sessions.establish(
            new AuthenticatedUser(
                UUID.fromString(r.getString("id")),
                r.getString("nome"),
                r.getString("login"),
                UserRole.ADMINISTRADOR));
        authorization = new AuthorizationService(sessions);
      }
      repository = new SqliteInventoryRepository(database.connection(), unitOfWork);
      useCase =
          new MaintainInventoryUseCase(
              unitOfWork,
              repository,
              new SqliteOutboxRepository(database.connection(), unitOfWork),
              authorization,
              audit,
              STATION);
    }

    ProductPageQuery query(boolean includeInactive, boolean low) {
      return new ProductPageQuery(
          "", "ALL", includeInactive, low, ProductSortField.NAME, true, 0, 25);
    }

    int scalar(String sql) throws Exception {
      try (Statement s = database.connection().createStatement();
          ResultSet r = s.executeQuery(sql)) {
        return r.next() ? r.getInt(1) : -1;
      }
    }

    void execute(String sql) throws Exception {
      try (Statement statement = database.connection().createStatement()) {
        statement.execute(sql);
      }
    }

    public void close() throws Exception {
      database.close();
    }
  }
}
