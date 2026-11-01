package br.com.espacotucuma.desktop.application.port.finance;

import br.com.espacotucuma.desktop.application.finance.Payment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {
  Optional<Payment> findById(UUID paymentId);

  Optional<Payment> findByIdempotencyKey(String idempotencyKey);

  List<Payment> findForAttendance(UUID attendanceId);

  long confirmedTotalCents(UUID attendanceId);

  void insert(Payment payment);
}
