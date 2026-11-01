package br.com.espacotucuma.sync;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Fails closed when the remotely exposed production service lacks TLS or its shared secret. */
@Component
final class ProductionSecurityConfiguration implements EnvironmentAware, InitializingBean {
  private Environment environment;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void afterPropertiesSet() {
    validate(
        environment.acceptsProfiles(Profiles.of("prod", "production")),
        environment.getProperty("server.ssl.enabled", Boolean.class, false),
        environment.getProperty("tucuma.security.sync-secret", ""));
  }

  static void validate(boolean production, boolean tlsEnabled, String secret) {
    boolean configuredSecret = secret != null && secret.length() >= 32;
    if (production && (!tlsEnabled || !configuredSecret)) {
      throw new IllegalStateException(
          "Configuração de produção inválida: TLS habilitado e TUCUMA_SYNC_SECRET com ao menos 32 caracteres são obrigatórios.");
    }
    if (secret != null && !secret.isBlank() && !configuredSecret) {
      throw new IllegalStateException(
          "Configuração inválida: TUCUMA_SYNC_SECRET deve ter ao menos 32 caracteres.");
    }
  }
}
