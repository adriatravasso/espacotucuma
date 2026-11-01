package br.com.espacotucuma.desktop.application.port.client;

import br.com.espacotucuma.desktop.application.Page;
import br.com.espacotucuma.desktop.application.client.Client;
import br.com.espacotucuma.desktop.application.client.ClientAttendanceHistory;
import br.com.espacotucuma.desktop.application.client.ClientPageQuery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClientRepository {
  void save(Client client);

  void update(Client client);

  void deactivate(UUID id, java.time.Instant updatedAt);

  String nextInternalCode();

  Optional<Client> findById(UUID id);

  Page<Client> searchPage(ClientPageQuery query);

  List<Client> findPotentialDuplicates(String name, String phone, UUID excludingId);

  List<ClientAttendanceHistory> attendanceHistory(UUID clientId);
}
