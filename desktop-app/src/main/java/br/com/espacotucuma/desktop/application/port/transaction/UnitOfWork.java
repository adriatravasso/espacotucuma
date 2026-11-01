package br.com.espacotucuma.desktop.application.port.transaction;

import java.util.function.Supplier;

public interface UnitOfWork {
    <T> T execute(Supplier<T> operation);
}
