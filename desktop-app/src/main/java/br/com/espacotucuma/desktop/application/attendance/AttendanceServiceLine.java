package br.com.espacotucuma.desktop.application.attendance;

import br.com.espacotucuma.desktop.application.commission.CommissionDecision;
import br.com.espacotucuma.desktop.domain.money.Money;
import java.util.Objects;
import java.util.UUID;

public record AttendanceServiceLine(
    UUID id,
    UUID attendanceId,
    UUID serviceId,
    String nameSnapshot,
    int quantity,
    Money unitPrice,
    CommissionDecision commission) {
  public AttendanceServiceLine {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(attendanceId, "attendanceId");
    Objects.requireNonNull(serviceId, "serviceId");
    nameSnapshot = Objects.requireNonNull(nameSnapshot, "nameSnapshot").strip();
    if (nameSnapshot.length() < 2 || nameSnapshot.length() > 120) {
      throw new IllegalArgumentException("O nome congelado do serviço é inválido.");
    }
    if (quantity < 1 || quantity > 100) {
      throw new IllegalArgumentException("A quantidade deve estar entre 1 e 100.");
    }
    Objects.requireNonNull(unitPrice, "unitPrice");
    if (unitPrice.amount().signum() < 0) {
      throw new IllegalArgumentException("O preço do serviço não pode ser negativo.");
    }
    Objects.requireNonNull(commission, "commission");
  }

  public Money total() {
    return new Money(unitPrice.amount().multiply(java.math.BigDecimal.valueOf(quantity)));
  }
}
