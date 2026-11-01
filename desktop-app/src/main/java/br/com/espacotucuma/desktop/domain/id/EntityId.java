package br.com.espacotucuma.desktop.domain.id;

import java.util.Objects;
import java.util.UUID;

public record EntityId(UUID value) {
    public EntityId {
        Objects.requireNonNull(value, "value");
    }

    public static EntityId generate() {
        return new EntityId(UUID.randomUUID());
    }
}
