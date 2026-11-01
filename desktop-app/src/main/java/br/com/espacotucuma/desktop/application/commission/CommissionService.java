package br.com.espacotucuma.desktop.application.commission;

import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.port.commission.CommissionRuleRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import br.com.espacotucuma.desktop.domain.money.Money;
import java.util.Objects;
import java.util.UUID;

public final class CommissionService {
  private final CommissionRuleRepository rules;
  private final UnitOfWork unitOfWork;
  private final AuthorizationService authorization;

  public CommissionService(
      CommissionRuleRepository rules, UnitOfWork unitOfWork, AuthorizationService authorization) {
    this.rules = Objects.requireNonNull(rules, "rules");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
  }

  public CommissionQuote preview(
      UUID professionalId,
      UUID serviceId,
      int serviceDefaultBasisPoints,
      Money serviceAmount,
      Money discount) {
    authorization.require(Permission.SERVICE_READ);
    CommissionDecision decision =
        CommissionCalculator.resolve(
            serviceDefaultBasisPoints,
            rules.findByProfessionalAndService(professionalId, serviceId));
    return new CommissionQuote(
        decision, CommissionCalculator.calculate(serviceAmount, discount, decision));
  }

  /** Persist the resolved rate and source in the attendance line before it is confirmed. */
  public void snapshot(UUID attendanceServiceId, CommissionDecision decision) {
    authorization.require(Permission.ATTENDANCE_RECORD);
    Objects.requireNonNull(attendanceServiceId, "attendanceServiceId");
    Objects.requireNonNull(decision, "decision");
    unitOfWork.execute(
        () -> {
          rules.saveAttendanceSnapshot(
              attendanceServiceId, decision.percentageBasisPoints(), decision.ruleId());
          return null;
        });
  }
}
