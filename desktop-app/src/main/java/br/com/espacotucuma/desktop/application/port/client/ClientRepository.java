package br.com.espacotucuma.desktop.application.port.client;

import br.com.espacotucuma.desktop.application.Page;
import br.com.espacotucuma.desktop.application.client.Client;
import br.com.espacotucuma.desktop.application.client.ClientPageQuery;
import java.util.Optional;
import java.util.UUID;

public interface ClientRepository {
  void save(Client client);

  Optional<Client> findById(UUID id);

  Page<Client> searchPage(ClientPageQuery query);
}
