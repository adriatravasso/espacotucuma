package br.com.espacotucuma.desktop.application.professional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record ProfessionalSchedule(
    List<WeeklyInterval> weeklyIntervals, List<OneOffUnavailability> unavailabilities) {
  public ProfessionalSchedule {
    weeklyIntervals = List.copyOf(Objects.requireNonNull(weeklyIntervals, "weeklyIntervals"));
    unavailabilities = List.copyOf(Objects.requireNonNull(unavailabilities, "unavailabilities"));
    for (int i = 0; i < weeklyIntervals.size(); i++) {
      for (int j = i + 1; j < weeklyIntervals.size(); j++) {
        if (weeklyIntervals.get(i).overlaps(weeklyIntervals.get(j))) {
          throw new IllegalArgumentException("A jornada contém horários semanais sobrepostos.");
        }
      }
    }
    List<OneOffUnavailability> ordered = new ArrayList<>(unavailabilities);
    ordered.sort(Comparator.comparing(OneOffUnavailability::startsAt));
    for (int i = 1; i < ordered.size(); i++) {
      if (ordered.get(i - 1).overlaps(ordered.get(i).startsAt(), ordered.get(i).endsAt())) {
        throw new IllegalArgumentException("Há afastamentos pontuais sobrepostos.");
      }
    }
  }

  public boolean allows(LocalDateTime start, LocalDateTime end) {
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(end, "end");
    if (!start.isBefore(end) || !start.toLocalDate().equals(end.toLocalDate())) return false;
    boolean insideShift =
        weeklyIntervals.stream()
            .filter(
                interval -> interval.day().getValue() % 7 == start.getDayOfWeek().getValue() % 7)
            .anyMatch(interval -> interval.contains(start.toLocalTime(), end.toLocalTime()));
    return insideShift && unavailabilities.stream().noneMatch(block -> block.overlaps(start, end));
  }
}
