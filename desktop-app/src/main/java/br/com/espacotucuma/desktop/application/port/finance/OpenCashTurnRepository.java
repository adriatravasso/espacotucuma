package br.com.espacotucuma.desktop.application.port.finance;

import br.com.espacotucuma.desktop.application.finance.Payment;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OpenCashTurnRepository {
  Optional<UUID> findOpenTurn(UUID operatorId);

  UUID recordReceipt(UUID turnId, Payment payment, Instant recordedAt);
}
