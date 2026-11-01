package br.com.espacotucuma.desktop.application.port.scheduling;

import br.com.espacotucuma.desktop.application.scheduling.Appointment;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository {
  Optional<Appointment> findById(UUID id);

  List<Appointment> findForDay(LocalDate day, UUID professionalId);

  boolean hasConflict(
      UUID professionalId,
      java.time.LocalDateTime startsAt,
      java.time.LocalDateTime endsAt,
      UUID excludingAppointmentId);

  void insert(Appointment appointment);

  void updateTime(Appointment appointment);

  void cancel(UUID id, java.time.LocalDateTime updatedAt);

  void confirm(UUID id, java.time.LocalDateTime updatedAt);
}
