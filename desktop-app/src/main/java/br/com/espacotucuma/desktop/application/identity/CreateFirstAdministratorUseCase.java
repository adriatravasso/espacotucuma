package br.com.espacotucuma.desktop.application.identity;

import br.com.espacotucuma.desktop.application.port.identity.InitialSetupRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class CreateFirstAdministratorUseCase {

  private final UnitOfWork unitOfWork;
  private final InitialSetupRepository setupRepository;
  private final PasswordHasher passwordHasher;

  public CreateFirstAdministratorUseCase(
      UnitOfWork unitOfWork,
      InitialSetupRepository setupRepository,
      PasswordHasher passwordHasher) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.setupRepository = Objects.requireNonNull(setupRepository, "setupRepository");
    this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher");
  }

  public boolean isSetupRequired() {
    return !setupRepository.isComplete();
  }

  public void create(InitialAdministratorSetup setup) {
    Objects.requireNonNull(setup, "setup");
    char[] password = setup.password();
    try {
      validate(setup, password);
      if (!isSetupRequired()) {
        throw new IllegalStateException("A configuração inicial já foi concluída.");
      }
      String encodedHash = passwordHasher.hash(password);
      Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
      unitOfWork.execute(
          () -> {
            if (setupRepository.isComplete()) {
              throw new IllegalStateException("A configuração inicial já foi concluída.");
            }
            setupRepository.save(
                UUID.randomUUID(),
                setup.establishmentName(),
                UUID.randomUUID(),
                setup.administratorName(),
                setup.login().toLowerCase(Locale.ROOT),
                encodedHash,
                now);
            return null;
          });
    } finally {
      Arrays.fill(password, '\0');
      setup.clearPassword();
    }
  }

  private void validate(InitialAdministratorSetup setup, char[] password) {
    if (setup.establishmentName().length() < 2 || setup.establishmentName().length() > 120) {
      throw new IllegalArgumentException(
          "O nome do estabelecimento deve ter de 2 a 120 caracteres.");
    }
    if (setup.administratorName().length() < 2 || setup.administratorName().length() > 120) {
      throw new IllegalArgumentException("O nome do administrador deve ter de 2 a 120 caracteres.");
    }
    if (setup.login().length() < 3 || setup.login().length() > 80) {
      throw new IllegalArgumentException("O login deve ter de 3 a 80 caracteres.");
    }
    if (password.length < 12 || password.length > 1024) {
      throw new IllegalArgumentException("A senha deve ter entre 12 e 1024 caracteres.");
    }
  }
}
