package br.com.espacotucuma.desktop.infrastructure.security;

import br.com.espacotucuma.desktop.application.identity.PasswordHasher;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** PBKDF2-HMAC-SHA256 password hashing using the JDK provider. */
public final class Pbkdf2PasswordHasher implements PasswordHasher {

  private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
  private static final String PREFIX = "pbkdf2-sha256";
  private static final int ITERATIONS = 600_000;
  private static final int SALT_BYTES = 16;
  private static final int HASH_BITS = 256;
  private final SecureRandom random = new SecureRandom();

  @Override
  public String hash(char[] password) {
    byte[] salt = new byte[SALT_BYTES];
    random.nextBytes(salt);
    byte[] derived = derive(password, salt, ITERATIONS);
    return PREFIX
        + "$"
        + ITERATIONS
        + "$"
        + Base64.getEncoder().withoutPadding().encodeToString(salt)
        + "$"
        + Base64.getEncoder().withoutPadding().encodeToString(derived);
  }

  @Override
  public boolean verify(char[] password, String encodedHash) {
    try {
      String[] parts = encodedHash.split("\\$", -1);
      if (parts.length != 4 || !PREFIX.equals(parts[0])) {
        return false;
      }
      int iterations = Integer.parseInt(parts[1]);
      if (iterations < 1 || iterations > 10_000_000) {
        return false;
      }
      byte[] salt = Base64.getDecoder().decode(parts[2]);
      byte[] expected = Base64.getDecoder().decode(parts[3]);
      if (salt.length != SALT_BYTES || expected.length != HASH_BITS / Byte.SIZE) {
        return false;
      }
      return MessageDigest.isEqual(expected, derive(password, salt, iterations));
    } catch (IllegalArgumentException malformedHash) {
      return false;
    }
  }

  private byte[] derive(char[] password, byte[] salt, int iterations) {
    PBEKeySpec specification = new PBEKeySpec(password, salt, iterations, HASH_BITS);
    try {
      return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(specification).getEncoded();
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("O provedor Java não suporta PBKDF2-HMAC-SHA256.", failure);
    } finally {
      specification.clearPassword();
    }
  }
}
