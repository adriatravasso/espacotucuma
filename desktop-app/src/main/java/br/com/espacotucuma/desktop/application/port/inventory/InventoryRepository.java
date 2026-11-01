package br.com.espacotucuma.desktop.application.port.inventory;

import br.com.espacotucuma.desktop.application.Page;
import br.com.espacotucuma.desktop.application.inventory.Product;
import br.com.espacotucuma.desktop.application.inventory.ProductPageQuery;
import br.com.espacotucuma.desktop.application.inventory.StockEntry;
import br.com.espacotucuma.desktop.application.inventory.StockProduct;
import br.com.espacotucuma.desktop.application.inventory.Supplier;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryRepository {
  Page<StockProduct> searchProducts(ProductPageQuery query);

  List<String> categories();

  List<Supplier> suppliers(boolean includeInactive);

  Optional<Supplier> findSupplier(UUID id);

  Optional<Product> findProduct(UUID id);

  Optional<StockEntry> findStockEntryByKey(String key);

  boolean hasStockMovements(UUID productId);

  void insertSupplier(Supplier supplier);

  void updateSupplier(Supplier supplier);

  void deactivateSupplier(UUID id);

  void insertProduct(Product product);

  void updateProduct(Product product);

  void deactivateProduct(UUID id, Instant at);

  void insertStockEntry(StockEntry entry);
}
