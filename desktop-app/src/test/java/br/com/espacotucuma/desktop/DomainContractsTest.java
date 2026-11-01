package br.com.espacotucuma.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.domain.id.EntityId;
import br.com.espacotucuma.desktop.domain.money.Money;
import br.com.espacotucuma.desktop.integration.clock.SystemTimeProvider;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainContractsTest {

  @Test
  void entityIdsAreStableUuidValuesGeneratedLocally() {
    EntityId first = EntityId.generate();
    EntityId second = EntityId.generate();

    assertNotEquals(first, second);
    assertEquals(first, new EntityId(UUID.fromString(first.value().toString())));
  }

  @Test
  void moneyUsesExactTwoDecimalPlacesAndAddsWithoutBinaryRounding() {
    assertEquals(Money.of("0.30"), Money.of("0.10").add(Money.of("0.20")));
    assertEquals(Money.of("1.20"), Money.of("1.2"));
  }

  @Test
  void moneyRejectsFractionsSmallerThanOneCent() {
    assertThrows(ArithmeticException.class, () -> Money.of("1.001"));
  }

  @Test
  void systemTimeProviderReturnsAnInstantInUtcTimeline() {
    Instant before = Instant.now();
    Instant actual = new SystemTimeProvider().now();
    Instant after = Instant.now();

    assertTrue(!actual.isBefore(before) && !actual.isAfter(after));
  }
}
