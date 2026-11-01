package br.com.espacotucuma.sync;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProductionSecurityConfigurationTest {
  @Test
  void productionRequiresTlsAndLongSecret() {
    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () ->
                ProductionSecurityConfiguration.validate(
                    true, false, "secret-value-that-is-long-enough-123"));

    assertFalse(exception.getMessage().contains("secret-value"));
    assertThrows(
        IllegalStateException.class,
        () -> ProductionSecurityConfiguration.validate(true, true, "missing"));
    assertDoesNotThrow(
        () ->
            ProductionSecurityConfiguration.validate(
                true, true, "a-secret-value-with-at-least-32-chars"));
  }

  @Test
  void invalidOptionalSecretFailsWithoutLeakingIt() {
    String secret = "short-secret";
    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> ProductionSecurityConfiguration.validate(false, false, secret));

    assertFalse(exception.getMessage().contains(secret));
    assertDoesNotThrow(() -> ProductionSecurityConfiguration.validate(false, false, ""));
  }
}
