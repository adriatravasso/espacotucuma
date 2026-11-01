package br.com.espacotucuma.desktop.application.identity;

import br.com.espacotucuma.desktop.application.port.identity.LocalCredentialRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** Changes a local credential only after verifying the current password. */
public final class ChangeLocalPasswordUseCase {

  private final UnitOfWork unitOfWork;
  private final LocalCredentialRepository credentials;
  private final PasswordHasher passwordHasher;

  public ChangeLocalPasswordUseCase(
      UnitOfWork unitOfWork, LocalCredentialRepository credentials, PasswordHasher passwordHasher) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher");
  }

  public void change(String login, char[] currentPassword, char[] newPassword) {
    Objects.requireNonNull(login, "login");
    Objects.requireNonNull(currentPassword, "currentPassword");
    Objects.requireNonNull(newPassword, "newPassword");
    try {
      if (newPassword.length < 12 || newPassword.length > 1024) {
        throw new IllegalArgumentException("A nova senha deve ter entre 12 e 1024 caracteres.");
      }
      String normalizedLogin = login.trim().toLowerCase(Locale.ROOT);
      if (normalizedLogin.length() < 3 || normalizedLogin.length() > 80) {
        throw new IllegalArgumentException("O login deve ter de 3 a 80 caracteres.");
      }
      unitOfWork.execute(
          () -> {
            LocalCredential credential =
                credentials
                    .findActiveByLogin(normalizedLogin)
                    .orElseThrow(() -> new SecurityException("Credenciais locais inválidas."));
            if (!passwordHasher.verify(currentPassword, credential.passwordHash())) {
              throw new SecurityException("Credenciais locais inválidas.");
            }
            String newHash = passwordHasher.hash(newPassword);
            credentials.updateHash(
                credential.userId(), newHash, Instant.now().truncatedTo(ChronoUnit.SECONDS));
            return null;
          });
    } finally {
      Arrays.fill(currentPassword, '\0');
      Arrays.fill(newPassword, '\0');
    }
  }
}
