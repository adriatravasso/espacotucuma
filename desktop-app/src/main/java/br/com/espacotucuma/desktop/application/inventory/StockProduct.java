package br.com.espacotucuma.desktop.application.inventory;

import java.util.Objects;

public record StockProduct(Product product, String supplierName, long onHandMilliUnits) {
  public StockProduct {
    Objects.requireNonNull(product, "product");
    supplierName = supplierName == null ? "—" : supplierName;
    if (onHandMilliUnits < 0)
      throw new IllegalArgumentException("O saldo calculado não pode ser negativo.");
  }

  public boolean belowMinimum() {
    return onHandMilliUnits <= product.minimumStockMilliUnits();
  }
}
