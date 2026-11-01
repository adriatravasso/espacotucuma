package br.com.espacotucuma.desktop.application.identity;

import java.util.Objects;
import java.util.UUID;

public record LocalCredential(UUID userId, String passwordHash) {
  public LocalCredential {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(passwordHash, "passwordHash");
  }
}
