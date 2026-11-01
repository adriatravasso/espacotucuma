package br.com.espacotucuma.sync;

interface BackupObjectStorage {
  void putPrivate(String key, byte[] content);

  byte[] get(String key);

  void delete(String key);
}
