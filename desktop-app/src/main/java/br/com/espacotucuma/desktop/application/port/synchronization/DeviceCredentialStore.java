package br.com.espacotucuma.desktop.application.port.synchronization;

import java.util.Optional;
import java.util.UUID;

/** Stores the long-lived device credential separately from local operator credentials. */
public interface DeviceCredentialStore {
  void save(UUID deviceId, String credential);

  Optional<String> find(UUID deviceId);

  void delete(UUID deviceId);
}
