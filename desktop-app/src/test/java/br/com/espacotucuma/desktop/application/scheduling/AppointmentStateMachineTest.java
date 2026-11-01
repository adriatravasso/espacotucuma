package br.com.espacotucuma.desktop.application.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppointmentStateMachineTest {
  private static final LocalDateTime CREATED = LocalDateTime.of(2026, 11, 2, 8, 0);

  @Test
  void permitsOnlyNextLifecycleStepOrTerminalCancellationAndNoShow() {
    for (AppointmentStatus current : AppointmentStatus.values()) {
      for (AppointmentStatus next : AppointmentStatus.values()) {
        boolean allowed = current.allowsTransitionTo(next);
        if (allowed) {
          assertEquals(
              next, appointment(current).transitionTo(next, CREATED.plusMinutes(1)).status());
        } else {
          assertThrows(
              IllegalStateException.class,
              () -> appointment(current).transitionTo(next, CREATED.plusMinutes(1)),
              current + " -> " + next);
        }
      }
    }
  }

  @Test
  void confirmsLifecycleEdgesAndKeepsTerminalStatesClosed() {
    assertTrue(AppointmentStatus.AGENDADO.allowsTransitionTo(AppointmentStatus.CONFIRMADO));
    assertTrue(AppointmentStatus.CONFIRMADO.allowsTransitionTo(AppointmentStatus.EM_ATENDIMENTO));
    assertTrue(AppointmentStatus.EM_ATENDIMENTO.allowsTransitionTo(AppointmentStatus.CONCLUIDO));
    assertTrue(AppointmentStatus.AGENDADO.allowsTransitionTo(AppointmentStatus.CANCELADO));
    assertTrue(AppointmentStatus.CONFIRMADO.allowsTransitionTo(AppointmentStatus.NAO_COMPARECEU));
    assertFalse(AppointmentStatus.CONCLUIDO.allowsTransitionTo(AppointmentStatus.AGENDADO));
  }

  private static Appointment appointment(AppointmentStatus status) {
    return new Appointment(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        CREATED,
        CREATED.plusMinutes(30),
        status,
        null,
        UUID.randomUUID(),
        CREATED,
        CREATED);
  }
}
