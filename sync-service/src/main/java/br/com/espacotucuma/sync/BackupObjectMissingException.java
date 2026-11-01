package br.com.espacotucuma.sync;

final class BackupObjectMissingException extends RuntimeException {
  BackupObjectMissingException(String message) {
    super(message);
  }
}
