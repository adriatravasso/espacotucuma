package br.com.espacotucuma.desktop.application.port.commission;

import br.com.espacotucuma.desktop.application.commission.CommissionCandidate;
import br.com.espacotucuma.desktop.application.commission.CommissionStatementLine;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public interface CommissionStatementRepository {
  List<CommissionCandidate> findEligible(YearMonth month);

  void closeCompetency(YearMonth month, UUID actorId, Instant at);

  void insert(CommissionStatementLine line, YearMonth month, Instant calculatedAt);

  boolean isClosed(YearMonth month);

  List<CommissionStatementLine> findStatement(YearMonth month, UUID professionalId);
}
