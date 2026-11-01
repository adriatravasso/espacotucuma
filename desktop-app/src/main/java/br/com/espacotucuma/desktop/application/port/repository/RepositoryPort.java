package br.com.espacotucuma.desktop.application.port.repository;

import java.util.Optional;

public interface RepositoryPort<T, ID> {
    Optional<T> findById(ID id);

    void save(T aggregate);
}
