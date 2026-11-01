package br.com.espacotucuma.desktop.application.port.professional;

import br.com.espacotucuma.desktop.application.Page;
import br.com.espacotucuma.desktop.application.professional.Professional;
import br.com.espacotucuma.desktop.application.professional.ProfessionalPageQuery;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfessionalRepository {
  Page<Professional> searchPage(ProfessionalPageQuery query);

  Optional<Professional> findById(UUID id);

  List<Professional> findActiveForScheduling();

  boolean canReceive(UUID professionalId, LocalDateTime startsAt, LocalDateTime endsAt);

  void insert(Professional professional);

  void update(Professional professional);

  void deactivate(UUID id, java.time.Instant at);
}
