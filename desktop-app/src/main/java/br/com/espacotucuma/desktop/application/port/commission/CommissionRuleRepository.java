package br.com.espacotucuma.desktop.application.port.commission;

import br.com.espacotucuma.desktop.application.commission.ServiceCommissionRule;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CommissionRuleRepository {
  Optional<ServiceCommissionRule> findByProfessionalAndService(UUID professionalId, UUID serviceId);

  Optional<ServiceCommissionRule> findById(UUID id);

  Optional<ServiceCommissionRule> findAnyByProfessionalAndService(
      UUID professionalId, UUID serviceId);

  void save(ServiceCommissionRule rule);

  void deactivate(UUID id, Instant at);

  void saveAttendanceSnapshot(UUID attendanceServiceId, int percentageBasisPoints, UUID ruleId);
}
