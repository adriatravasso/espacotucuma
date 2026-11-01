package br.com.espacotucuma.desktop.application.identity;

import java.util.Objects;

public final class LogoutUseCase {
  private final SessionContext sessions;

  public LogoutUseCase(SessionContext sessions) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
  }

  public void logout() {
    sessions.clear();
  }
}
