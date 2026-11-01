package br.com.espacotucuma.desktop.integration.clock;

import br.com.espacotucuma.desktop.application.port.clock.TimeProvider;

import java.time.Clock;
import java.time.Instant;

public final class SystemTimeProvider implements TimeProvider {
    private final Clock clock;

    public SystemTimeProvider() {
        this(Clock.systemUTC());
    }

    SystemTimeProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Instant now() {
        return clock.instant();
    }
}
