package br.com.espacotucuma.desktop.application.scheduling;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** Appointment time is expressed as local wall-clock time in the establishment zone. */
public record Appointment(
    UUID id,
    UUID clientId,
    UUID professionalId,
    UUID serviceId,
    LocalDateTime startsAt,
    LocalDateTime endsAt,
    AppointmentStatus status,
    String notes,
    UUID createdBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {
  public Appointment {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(clientId, "clientId");
    Objects.requireNonNull(professionalId, "professionalId");
    Objects.requireNonNull(serviceId, "serviceId");
    Objects.requireNonNull(startsAt, "startsAt");
    Objects.requireNonNull(endsAt, "endsAt");
    if (!startsAt.isBefore(endsAt)
        || !startsAt.toLocalDate().equals(endsAt.toLocalDate())
        || startsAt.getSecond() != 0
        || startsAt.getNano() != 0
        || endsAt.getSecond() != 0
        || endsAt.getNano() != 0) {
      throw new IllegalArgumentException(
          "O agendamento deve ser positivo, no mesmo dia e alinhado a minutos.");
    }
    Objects.requireNonNull(status, "status");
    notes = notes == null || notes.isBlank() ? null : notes.strip();
    if (notes != null && notes.length() > 500) {
      throw new IllegalArgumentException("A observação deve ter até 500 caracteres.");
    }
    Objects.requireNonNull(createdBy, "createdBy");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("A atualização não pode anteceder o cadastro.");
    }
  }

  public boolean occupiesTime() {
    return status == AppointmentStatus.AGENDADO
        || status == AppointmentStatus.CONFIRMADO
        || status == AppointmentStatus.EM_ATENDIMENTO;
  }

  public Appointment transitionTo(AppointmentStatus next, LocalDateTime changedAt) {
    Objects.requireNonNull(next, "next");
    Objects.requireNonNull(changedAt, "changedAt");
    if (!status.allowsTransitionTo(next)) {
      throw new IllegalStateException("Transição inválida de " + status + " para " + next + ".");
    }
    LocalDateTime updated = changedAt.isBefore(updatedAt) ? updatedAt : changedAt;
    return new Appointment(
        id,
        clientId,
        professionalId,
        serviceId,
        startsAt,
        endsAt,
        next,
        notes,
        createdBy,
        createdAt,
        updated);
  }
}
