package br.com.espacotucuma.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Isolated object storage and credential helpers shared by the cross-module sync E2E test. */
@TestConfiguration
public class SyncE2eTestConfiguration {
  @Bean
  @Primary
  public InMemoryBackupStorage inMemoryBackupStorage() {
    return new InMemoryBackupStorage();
  }

  public static String hashCredential(String credential) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(credential.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.", impossible);
    }
  }

  public static class InMemoryBackupStorage implements BackupObjectStorage {
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final AtomicBoolean failNextUpload = new AtomicBoolean();

    @Override
    public void putPrivate(String key, byte[] content) {
      if (failNextUpload.compareAndSet(true, false)) {
        objects.put(key, Arrays.copyOf(content, Math.max(1, content.length / 2)));
        throw new IllegalStateException("Synthetic partial upload failure.");
      }
      objects.put(key, Arrays.copyOf(content, content.length));
    }

    @Override
    public byte[] get(String key) {
      byte[] content = objects.get(key);
      if (content == null)
        throw new BackupObjectMissingException("Synthetic backup object missing.");
      return Arrays.copyOf(content, content.length);
    }

    @Override
    public void delete(String key) {
      objects.remove(key);
    }

    public void failNextUpload() {
      failNextUpload.set(true);
    }

    public void clear() {
      objects.clear();
      failNextUpload.set(false);
    }
  }
}
