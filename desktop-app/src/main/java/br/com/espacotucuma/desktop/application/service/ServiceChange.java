package br.com.espacotucuma.desktop.application.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ServiceChange(UUID eventId, String action, Instant occurredAt, String summary) {
  public ServiceChange {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(occurredAt, "occurredAt");
    summary = summary == null ? "" : summary;
  }
}
