package br.com.espacotucuma.desktop.application.port.clock;

import java.time.Instant;

@FunctionalInterface
public interface TimeProvider {
  Instant now();
}
