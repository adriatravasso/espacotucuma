package br.com.espacotucuma.desktop.application.professional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;

public record WeeklyInterval(DayOfWeek day, LocalTime startsAt, LocalTime endsAt) {
  public WeeklyInterval {
    Objects.requireNonNull(day, "day");
    Objects.requireNonNull(startsAt, "startsAt");
    Objects.requireNonNull(endsAt, "endsAt");
    if (!startsAt.isBefore(endsAt)) {
      throw new IllegalArgumentException("O fim do horário deve ser posterior ao início.");
    }
  }

  public boolean overlaps(WeeklyInterval other) {
    return day == other.day && startsAt.isBefore(other.endsAt) && other.startsAt.isBefore(endsAt);
  }

  public boolean contains(LocalTime start, LocalTime end) {
    return !start.isBefore(startsAt) && !end.isAfter(endsAt) && start.isBefore(end);
  }
}
