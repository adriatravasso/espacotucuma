package br.com.espacotucuma.desktop.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class WindowsCredentialStoreTest {
  @Test
  void storesAndRemovesCredentialInCurrentWindowsUserVault() {
    Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("windows"));
    WindowsCredentialStore store = new WindowsCredentialStore();
    UUID deviceId = UUID.randomUUID();
    String credential = UUID.randomUUID() + UUID.randomUUID().toString();

    try {
      store.save(deviceId, credential);
      assertEquals(store.find(deviceId).orElseThrow(), credential);
    } finally {
      store.delete(deviceId);
    }

    assertTrue(store.find(deviceId).isEmpty());
  }

  @Test
  void refusesCredentialOperationsOutsideWindows() {
    String originalOs = System.getProperty("os.name");
    System.setProperty("os.name", "Linux");
    try {
      org.junit.jupiter.api.Assertions.assertThrows(
          IllegalStateException.class, () -> new WindowsCredentialStore().find(UUID.randomUUID()));
    } finally {
      if (originalOs == null) {
        System.clearProperty("os.name");
      } else {
        System.setProperty("os.name", originalOs);
      }
    }
  }
}
