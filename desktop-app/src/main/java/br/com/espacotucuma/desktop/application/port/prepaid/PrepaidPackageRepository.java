package br.com.espacotucuma.desktop.application.port.prepaid;

import br.com.espacotucuma.desktop.application.Page;
import br.com.espacotucuma.desktop.application.prepaid.AttendanceOption;
import br.com.espacotucuma.desktop.application.prepaid.PackagePageQuery;
import br.com.espacotucuma.desktop.application.prepaid.PackageUsage;
import br.com.espacotucuma.desktop.application.prepaid.PrepaidPackage;
import br.com.espacotucuma.desktop.application.prepaid.SessionBalance;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrepaidPackageRepository {
  Page<PrepaidPackage> searchPackages(PackagePageQuery query);

  Optional<PrepaidPackage> findPackage(UUID id);

  void insertPackage(PrepaidPackage prepaidPackage);

  void updatePackage(PrepaidPackage prepaidPackage);

  void deactivatePackage(UUID id, Instant at);

  Page<SessionBalance> searchBalances(String clientSearch, int pageIndex, int pageSize);

  Optional<SessionBalance> findBalance(UUID id);

  void contract(SessionBalance balance);

  List<AttendanceOption> eligibleAttendances(UUID clientId);

  Optional<AttendanceOption> findAttendance(UUID attendanceId);

  void consume(
      UUID balanceId,
      UUID attendanceId,
      UUID usageId,
      int sessions,
      UUID userId,
      Instant at,
      LocalDate today);

  List<PackageUsage> history(UUID balanceId);

  Optional<PackageUsage> historyForUsage(UUID usageId);

  void reverse(UUID usageId, String reason, UUID userId, Instant at, LocalDate today);
}
