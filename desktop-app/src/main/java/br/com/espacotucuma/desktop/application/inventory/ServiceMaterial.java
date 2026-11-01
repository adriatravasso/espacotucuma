package br.com.espacotucuma.desktop.application.inventory;

import java.util.Objects;
import java.util.UUID;

public record ServiceMaterial(
    UUID serviceId,
    UUID productId,
    String serviceName,
    String productName,
    long quantityMilliUnits) {
  public ServiceMaterial {
    Objects.requireNonNull(serviceId);
    Objects.requireNonNull(productId);
    serviceName = Objects.requireNonNull(serviceName);
    productName = Objects.requireNonNull(productName);
    if (quantityMilliUnits <= 0)
      throw new IllegalArgumentException("A quantidade de material deve ser positiva.");
  }
}
