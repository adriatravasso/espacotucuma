package br.com.espacotucuma.desktop.application.professional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Professional(
    UUID id,
    String name,
    String phone,
    boolean active,
    ProfessionalSchedule schedule,
    Instant createdAt,
    Instant updatedAt) {
  public Professional {
    Objects.requireNonNull(id, "id");
    name = Objects.requireNonNull(name, "name").strip();
    if (name.length() < 2 || name.length() > 120) {
      throw new IllegalArgumentException("O nome deve conter de 2 a 120 caracteres.");
    }
    phone = phone == null || phone.isBlank() ? null : phone.replaceAll("\\D", "");
    if (phone != null && (phone.length() < 8 || phone.length() > 15)) {
      throw new IllegalArgumentException("O telefone deve conter de 8 a 15 dígitos.");
    }
    Objects.requireNonNull(schedule, "schedule");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("A atualização não pode anteceder o cadastro.");
    }
  }

  public boolean canReceive(java.time.LocalDateTime startsAt, java.time.LocalDateTime endsAt) {
    return active && schedule.allows(startsAt, endsAt);
  }
}
