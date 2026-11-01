package br.com.espacotucuma.desktop.application.identity;

import br.com.espacotucuma.desktop.application.port.identity.LocalCredentialRepository;
import br.com.espacotucuma.desktop.application.port.identity.LoginAttemptRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class LoginUseCase {
  private final UnitOfWork unitOfWork;
  private final LocalCredentialRepository credentials;
  private final LoginAttemptRepository attempts;
  private final PasswordHasher passwordHasher;
  private final SessionContext sessions;

  public LoginUseCase(
      UnitOfWork unitOfWork,
      LocalCredentialRepository credentials,
      LoginAttemptRepository attempts,
      PasswordHasher passwordHasher,
      SessionContext sessions) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.attempts = Objects.requireNonNull(attempts, "attempts");
    this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
  }

  public Optional<AuthenticatedSession> login(String login, char[] password) {
    Objects.requireNonNull(login, "login");
    Objects.requireNonNull(password, "password");
    sessions.clear();
    try {
      String normalized = login.trim().toLowerCase(Locale.ROOT);
      if (normalized.isEmpty() || normalized.length() > 80) {
        return Optional.empty();
      }
      Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
      Optional<AuthenticatedUser> authenticated =
          unitOfWork.execute(
              () -> {
                Optional<LocalCredential> credential = credentials.findActiveByLogin(normalized);
                boolean valid =
                    credential
                        .filter(value -> passwordHasher.verify(password, value.passwordHash()))
                        .isPresent();
                attempts.record(credential.map(LocalCredential::userId), normalized, valid, now);
                return valid
                    ? credential.map(
                        value ->
                            new AuthenticatedUser(
                                value.userId(), value.name(), value.login(), value.role()))
                    : Optional.empty();
              });
      return authenticated.map(sessions::establish);
    } finally {
      Arrays.fill(password, '\0');
    }
  }
}
