package br.com.espacotucuma.desktop.application.attendance;

import br.com.espacotucuma.desktop.domain.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Attendance(
    UUID id,
    UUID appointmentId,
    UUID clientId,
    UUID professionalId,
    AttendanceStatus status,
    Money subtotal,
    Money discount,
    Money total,
    String notes,
    Instant startedAt,
    Instant completedAt,
    UUID createdBy,
    Instant updatedAt) {
  public Attendance {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(appointmentId, "appointmentId");
    Objects.requireNonNull(clientId, "clientId");
    Objects.requireNonNull(professionalId, "professionalId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(subtotal, "subtotal");
    Objects.requireNonNull(discount, "discount");
    Objects.requireNonNull(total, "total");
    if (subtotal.amount().signum() < 0
        || discount.amount().signum() < 0
        || discount.amount().compareTo(subtotal.amount()) > 0
        || !total.equals(new Money(subtotal.amount().subtract(discount.amount())))) {
      throw new IllegalArgumentException("Os valores do atendimento são inválidos.");
    }
    notes = notes == null || notes.isBlank() ? null : notes.strip();
    if (notes != null && notes.length() > 500) {
      throw new IllegalArgumentException("As observações devem ter até 500 caracteres.");
    }
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(createdBy, "createdBy");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if ((status == AttendanceStatus.CONCLUIDO) != (completedAt != null)) {
      throw new IllegalArgumentException("A conclusão do atendimento está inconsistente.");
    }
    if (completedAt != null && completedAt.isBefore(startedAt)) {
      throw new IllegalArgumentException("A conclusão não pode anteceder o início.");
    }
  }

  public Attendance withLines(Money newSubtotal, Instant at) {
    if (status != AttendanceStatus.EM_ANDAMENTO) {
      throw new IllegalStateException("Somente atendimentos em andamento podem ser alterados.");
    }
    return new Attendance(
        id,
        appointmentId,
        clientId,
        professionalId,
        status,
        newSubtotal,
        discount,
        new Money(newSubtotal.amount().subtract(discount.amount())),
        notes,
        startedAt,
        null,
        createdBy,
        at.isBefore(updatedAt) ? updatedAt : at);
  }

  public Attendance withDiscount(Money newDiscount, Instant at) {
    if (status != AttendanceStatus.EM_ANDAMENTO) {
      throw new IllegalStateException("Somente atendimentos em andamento podem ser alterados.");
    }
    return new Attendance(
        id,
        appointmentId,
        clientId,
        professionalId,
        status,
        subtotal,
        newDiscount,
        new Money(subtotal.amount().subtract(newDiscount.amount())),
        notes,
        startedAt,
        null,
        createdBy,
        at.isBefore(updatedAt) ? updatedAt : at);
  }

  public Attendance withNotes(String newNotes, Instant at) {
    if (status != AttendanceStatus.EM_ANDAMENTO) {
      throw new IllegalStateException("Somente atendimentos em andamento podem ser alterados.");
    }
    return new Attendance(
        id,
        appointmentId,
        clientId,
        professionalId,
        status,
        subtotal,
        discount,
        total,
        newNotes,
        startedAt,
        null,
        createdBy,
        at.isBefore(updatedAt) ? updatedAt : at);
  }

  public Attendance complete(Instant at) {
    if (status != AttendanceStatus.EM_ANDAMENTO) {
      throw new IllegalStateException("O atendimento já foi encerrado.");
    }
    return new Attendance(
        id,
        appointmentId,
        clientId,
        professionalId,
        AttendanceStatus.CONCLUIDO,
        subtotal,
        discount,
        total,
        notes,
        startedAt,
        at.isBefore(startedAt) ? startedAt : at,
        createdBy,
        at.isBefore(updatedAt) ? updatedAt : at);
  }
}
