package br.com.espacotucuma.desktop.application.port.finance;

import br.com.espacotucuma.desktop.application.finance.PaymentRefund;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRefundRepository {
  Optional<PaymentRefund> findByIdempotencyKey(String key);

  long refundedTotalCents(UUID paymentId);

  List<PaymentRefund> findForPayment(UUID paymentId);

  void insert(PaymentRefund refund);
}
