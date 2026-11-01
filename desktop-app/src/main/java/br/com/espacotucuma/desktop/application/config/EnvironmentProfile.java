package br.com.espacotucuma.desktop.application.config;

import java.util.Locale;

public enum EnvironmentProfile {
    DEVELOPMENT("development"),
    TEST("test"),
    DEMO("demo"),
    HOMOLOGATION("homologation");

    private final String directoryName;

    EnvironmentProfile(String directoryName) {
        this.directoryName = directoryName;
    }

    public String directoryName() {
        return directoryName;
    }

    public static EnvironmentProfile parse(String value) {
        String normalized = value == null ? "development" : value.trim().toLowerCase(Locale.ROOT);
        for (EnvironmentProfile profile : values()) {
            if (profile.directoryName.equals(normalized)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Perfil inválido: " + value);
    }
}
