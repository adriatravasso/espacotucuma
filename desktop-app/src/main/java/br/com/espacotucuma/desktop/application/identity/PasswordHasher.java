package br.com.espacotucuma.desktop.application.identity;

public interface PasswordHasher {
  String hash(char[] password);

  boolean verify(char[] password, String encodedHash);
}
