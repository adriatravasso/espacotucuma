package br.com.espacotucuma.desktop.application.port.scheduling;

import br.com.espacotucuma.desktop.application.scheduling.Appointment;
import br.com.espacotucuma.desktop.application.scheduling.AppointmentStatus;
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

  void transition(
      UUID id,
      AppointmentStatus expected,
      AppointmentStatus next,
      java.time.LocalDateTime updatedAt);

  boolean hasCompletedAttendance(UUID appointmentId, UUID attendanceId);
}
