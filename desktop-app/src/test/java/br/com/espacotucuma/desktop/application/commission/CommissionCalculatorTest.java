package br.com.espacotucuma.desktop.application.commission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.espacotucuma.desktop.domain.money.Money;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommissionCalculatorTest {
  private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

  @Test
  void professionalAndServiceOverrideTakesPrecedenceAndServiceDefaultIsFallback() {
    ServiceCommissionRule rule = rule(2500, true);
    assertEquals(rule.decision(), CommissionCalculator.resolve(1500, Optional.of(rule)));
    assertEquals(
        new CommissionDecision(1500, CommissionDecision.Source.SERVICE, null),
        CommissionCalculator.resolve(1500, Optional.empty()));
    assertEquals(
        new CommissionDecision(1500, CommissionDecision.Source.SERVICE, null),
        CommissionCalculator.resolve(1500, Optional.of(rule(2500, false))));
  }

  @Test
  void calculatesDiscountedCommissionAndRoundsHalfUpToCent() {
    CommissionDecision decision =
        new CommissionDecision(1250, CommissionDecision.Source.SERVICE, null);
    assertEquals(
        Money.of("1.00"),
        CommissionCalculator.calculate(Money.of("10.00"), Money.of("2.00"), decision));
    assertEquals(
        Money.of("0.01"),
        CommissionCalculator.calculate(
            Money.of("0.01"),
            Money.of("0.00"),
            new CommissionDecision(5000, CommissionDecision.Source.SERVICE, null)));
    assertEquals(
        Money.of("0.00"),
        CommissionCalculator.calculate(
            Money.of("150.00"),
            Money.of("150.00"),
            new CommissionDecision(3000, CommissionDecision.Source.SERVICE, null)));
  }

  @Test
  void validatesPercentageDiscountAndMoneyPrecisionAtBoundaries() {
    assertEquals(
        0,
        new CommissionDecision(0, CommissionDecision.Source.SERVICE, null).percentageBasisPoints());
    assertEquals(
        10000,
        new CommissionDecision(10000, CommissionDecision.Source.SERVICE, null)
            .percentageBasisPoints());
    assertThrows(
        IllegalArgumentException.class,
        () -> new CommissionDecision(10001, CommissionDecision.Source.SERVICE, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CommissionCalculator.calculate(
                Money.of("20.00"),
                Money.of("20.01"),
                new CommissionDecision(2000, CommissionDecision.Source.SERVICE, null)));
    assertThrows(ArithmeticException.class, () -> Money.of("0.001"));
  }

  @Test
  void allocatesAttendanceDiscountInCentsDeterministicallyAcrossServiceLines() {
    UUID attendanceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    CommissionCandidate first = candidate("00000000-0000-0000-0000-000000000003", attendanceId, 1);
    CommissionCandidate second = candidate("00000000-0000-0000-0000-000000000002", attendanceId, 1);
    CommissionCandidate third = candidate("00000000-0000-0000-0000-000000000004", attendanceId, 1);

    var allocation =
        CloseCommissionCompetencyUseCase.allocateDiscounts(java.util.List.of(first, second, third));

    assertEquals(0, allocation.get(first.serviceLineId()));
    assertEquals(1, allocation.get(second.serviceLineId()));
    assertEquals(0, allocation.get(third.serviceLineId()));
  }

  private CommissionCandidate candidate(String id, UUID attendanceId, long cents) {
    return new CommissionCandidate(
        UUID.fromString(id),
        attendanceId,
        UUID.fromString("00000000-0000-0000-0000-000000000010"),
        "Profissional",
        "Serviço",
        NOW,
        cents,
        1,
        5000);
  }

  private ServiceCommissionRule rule(int percentage, boolean active) {
    return new ServiceCommissionRule(
        UUID.fromString("2e3b78dd-6cfe-4ad4-baa8-0e027108dcef"),
        UUID.fromString("fc57f43b-4d8a-4b85-a1bb-9a2a79640412"),
        UUID.fromString("9c0f014d-8d5c-4ffb-bef2-7a6bf13c1ea8"),
        percentage,
        active,
        NOW,
        NOW);
  }
}
