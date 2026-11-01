package br.com.espacotucuma.desktop.application.identity;

import java.util.Arrays;
import java.util.Objects;

public record InitialAdministratorSetup(
    String establishmentName, String administratorName, String login, char[] password) {
  public InitialAdministratorSetup {
    establishmentName = Objects.requireNonNull(establishmentName, "establishmentName").trim();
    administratorName = Objects.requireNonNull(administratorName, "administratorName").trim();
    login = Objects.requireNonNull(login, "login").trim();
    password = Objects.requireNonNull(password, "password").clone();
  }

  @Override
  public char[] password() {
    return password.clone();
  }

  public void clearPassword() {
    Arrays.fill(password, '\0');
  }
}
