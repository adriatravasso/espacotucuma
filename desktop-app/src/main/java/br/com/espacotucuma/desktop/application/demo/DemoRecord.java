package br.com.espacotucuma.desktop.application.demo;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record DemoRecord(
    UUID id, Kind kind, String name, Map<String, String> attributes, Instant createdAt) {
  public DemoRecord {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(name, "name");
    attributes = Map.copyOf(attributes);
    Objects.requireNonNull(createdAt, "createdAt");
  }

  public enum Kind {
    PROFESSIONAL,
    CLIENT,
    SERVICE,
    APPOINTMENT,
    PRODUCT,
    STOCK_MOVEMENT
  }
}
