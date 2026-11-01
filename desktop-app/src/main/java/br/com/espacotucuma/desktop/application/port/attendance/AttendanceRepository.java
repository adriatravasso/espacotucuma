package br.com.espacotucuma.desktop.application.port.attendance;

import br.com.espacotucuma.desktop.application.attendance.Attendance;
import br.com.espacotucuma.desktop.application.attendance.AttendanceServiceLine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceRepository {
  Optional<Attendance> findById(UUID id);

  Optional<Attendance> findByAppointment(UUID appointmentId);

  List<AttendanceServiceLine> findServices(UUID attendanceId);

  Optional<AttendanceServiceLine> findService(UUID attendanceId, UUID serviceId);

  void insert(Attendance attendance);

  void insertService(AttendanceServiceLine service);

  void updateTotals(Attendance attendance);

  void updateNotes(Attendance attendance);

  void complete(Attendance attendance);
}
