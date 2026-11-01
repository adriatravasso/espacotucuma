package br.com.espacotucuma.desktop.application.professional;

import java.time.LocalDateTime;
import java.util.Objects;

public record OneOffUnavailability(LocalDateTime startsAt, LocalDateTime endsAt, String reason) {
  public OneOffUnavailability {
    Objects.requireNonNull(startsAt, "startsAt");
    Objects.requireNonNull(endsAt, "endsAt");
    if (!startsAt.isBefore(endsAt)) {
      throw new IllegalArgumentException("O fim do afastamento deve ser posterior ao início.");
    }
    reason = reason == null || reason.isBlank() ? null : reason.strip();
    if (reason != null && reason.length() > 120) {
      throw new IllegalArgumentException("O motivo do afastamento deve ter até 120 caracteres.");
    }
  }

  public boolean overlaps(LocalDateTime start, LocalDateTime end) {
    return startsAt.isBefore(end) && start.isBefore(endsAt);
  }
}
