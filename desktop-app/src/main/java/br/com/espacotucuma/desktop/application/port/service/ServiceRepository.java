package br.com.espacotucuma.desktop.application.port.service;

import br.com.espacotucuma.desktop.application.Page;
import br.com.espacotucuma.desktop.application.service.BeautyService;
import br.com.espacotucuma.desktop.application.service.ServiceChange;
import br.com.espacotucuma.desktop.application.service.ServicePageQuery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRepository {
  Page<BeautyService> searchPage(ServicePageQuery query);

  List<String> categories();

  Optional<BeautyService> findById(UUID id);

  List<ServiceChange> changeHistory(UUID serviceId);

  void insert(BeautyService service);

  void update(BeautyService service);

  void deactivate(UUID id, Instant at);
}
