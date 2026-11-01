package br.com.espacotucuma.desktop.application.demo;

public interface DemoSeedRepository {
  boolean insertIfAbsent(DemoRecord record);
}
