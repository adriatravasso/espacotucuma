package br.com.espacotucuma.desktop.infrastructure.security;

import br.com.espacotucuma.desktop.application.port.synchronization.DeviceCredentialStore;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import com.sun.jna.win32.W32APITypeMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/** Stores device credentials in the current Windows user's Credential Manager vault. */
public final class WindowsCredentialStore implements DeviceCredentialStore {
  private static final int GENERIC_CREDENTIAL = 1;
  private static final int PERSIST_LOCAL_MACHINE = 2;
  private static final int ERROR_NOT_FOUND = 1168;
  private static final String TARGET_PREFIX = "EspacoTucuma/SyncDevice/";

  @Override
  public void save(UUID deviceId, String credential) {
    requireWindows();
    if (credential == null || credential.isBlank()) {
      throw new IllegalArgumentException("A credencial da estação não pode estar vazia.");
    }
    byte[] credentialBytes = credential.getBytes(StandardCharsets.UTF_8);
    if (credentialBytes.length > 5_120) {
      Arrays.fill(credentialBytes, (byte) 0);
      throw new IllegalArgumentException("A credencial da estação excede o limite seguro.");
    }

    try (Memory blob = new Memory(credentialBytes.length)) {
      blob.write(0, credentialBytes, 0, credentialBytes.length);
      Credential entry = new Credential();
      entry.type = GENERIC_CREDENTIAL;
      entry.targetName = target(deviceId);
      entry.credentialBlobSize = credentialBytes.length;
      entry.credentialBlob = blob;
      entry.persist = PERSIST_LOCAL_MACHINE;
      entry.userName = "Espaco Tucuma sync device";
      entry.write();
      if (!CredentialManager.INSTANCE.CredWriteW(entry, 0)) {
        throw storageFailure();
      }
    } finally {
      Arrays.fill(credentialBytes, (byte) 0);
    }
  }

  @Override
  public Optional<String> find(UUID deviceId) {
    requireWindows();
    PointerByReference result = new PointerByReference();
    if (!CredentialManager.INSTANCE.CredReadW(target(deviceId), GENERIC_CREDENTIAL, 0, result)) {
      return Native.getLastError() == ERROR_NOT_FOUND ? Optional.empty() : failRead();
    }

    Pointer allocation = result.getValue();
    try {
      Credential entry = new Credential(allocation);
      entry.read();
      byte[] bytes = entry.credentialBlob.getByteArray(0, entry.credentialBlobSize);
      try {
        return Optional.of(new String(bytes, StandardCharsets.UTF_8));
      } finally {
        Arrays.fill(bytes, (byte) 0);
      }
    } finally {
      CredentialManager.INSTANCE.CredFree(allocation);
    }
  }

  @Override
  public void delete(UUID deviceId) {
    requireWindows();
    if (!CredentialManager.INSTANCE.CredDeleteW(target(deviceId), GENERIC_CREDENTIAL, 0)
        && Native.getLastError() != ERROR_NOT_FOUND) {
      throw storageFailure();
    }
  }

  private static String target(UUID deviceId) {
    return TARGET_PREFIX + deviceId;
  }

  private static void requireWindows() {
    if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
      throw new IllegalStateException("Credenciais remotas só podem ser usadas no Windows.");
    }
  }

  private static Optional<String> failRead() {
    throw storageFailure();
  }

  private static IllegalStateException storageFailure() {
    return new IllegalStateException("O Credential Manager do Windows não concluiu a operação.");
  }

  @Structure.FieldOrder({
    "flags",
    "type",
    "targetName",
    "comment",
    "lastWritten",
    "credentialBlobSize",
    "credentialBlob",
    "persist",
    "attributeCount",
    "attributes",
    "targetAlias",
    "userName"
  })
  public static final class Credential extends Structure {
    public int flags;
    public int type;
    public String targetName;
    public String comment;
    public WinBase.FILETIME lastWritten = new WinBase.FILETIME();
    public int credentialBlobSize;
    public Pointer credentialBlob;
    public int persist;
    public int attributeCount;
    public Pointer attributes;
    public String targetAlias;
    public String userName;

    public Credential() {
      super(W32APITypeMapper.UNICODE);
    }

    public Credential(Pointer pointer) {
      super(pointer, ALIGN_DEFAULT, W32APITypeMapper.UNICODE);
    }
  }

  private interface CredentialManager extends StdCallLibrary {
    CredentialManager INSTANCE =
        Native.load("Advapi32", CredentialManager.class, W32APIOptions.UNICODE_OPTIONS);

    boolean CredWriteW(Credential credential, int flags);

    boolean CredReadW(String targetName, int type, int flags, PointerByReference credential);

    boolean CredDeleteW(String targetName, int type, int flags);

    void CredFree(Pointer buffer);
  }
}
