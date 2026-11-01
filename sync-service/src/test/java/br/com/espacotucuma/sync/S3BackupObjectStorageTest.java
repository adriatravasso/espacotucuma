package br.com.espacotucuma.sync;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class S3BackupObjectStorageTest {
  @Test
  void productionAndNonDemoEndpointsRequireHttps() {
    assertDoesNotThrow(
        () ->
            S3BackupObjectStorage.validateEndpoint(
                URI.create("https://objects.example.test"), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> S3BackupObjectStorage.validateEndpoint(URI.create("http://rustfs:9000"), false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            S3BackupObjectStorage.validateEndpoint(
                URI.create("http://storage.example.test:9000"), true));
    assertThrows(
        IllegalArgumentException.class,
        () -> S3BackupObjectStorage.validateEndpoint(URI.create("http://rustfs:9001"), true));
  }

  @Test
  void onlyTheInternalRustfsEndpointUsesHttpInTheIsolatedDemoProfile() {
    assertDoesNotThrow(
        () -> S3BackupObjectStorage.validateEndpoint(URI.create("http://rustfs:9000"), true));
  }
}
