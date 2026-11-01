package br.com.espacotucuma.desktop.application.port.finance;

import br.com.espacotucuma.desktop.application.finance.CashMovement;
import br.com.espacotucuma.desktop.application.finance.CashReconciliation;
import br.com.espacotucuma.desktop.application.finance.CashTurn;
import br.com.espacotucuma.desktop.application.finance.PaymentMethod;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface CashTurnRepository {
  Optional<CashTurn> findOpenTurn(UUID operatorId);

  Optional<CashTurn> findLatestTurn(UUID operatorId);

  Optional<CashTurn> findById(UUID turnId);

  List<CashMovement> findMovements(UUID turnId);

  Map<PaymentMethod, Long> expectedTotals(UUID turnId);

  List<CashReconciliation> findLatestClosing(UUID turnId);

  void open(CashTurn turn);

  void recordMovement(CashMovement movement);

  void close(
      UUID turnId,
      UUID closingId,
      UUID operatorId,
      Instant closedAt,
      Map<PaymentMethod, CashReconciliation> totals,
      String reason);

  void reopen(UUID turnId);
}
