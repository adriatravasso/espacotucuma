package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.application.port.identity.UserAdministrationRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

public final class SqliteUserAdministrationRepository implements UserAdministrationRepository {
  private final java.sql.Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteUserAdministrationRepository(
      java.sql.Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public boolean isActive(UUID userId) {
    return scalar("SELECT ativo FROM usuario WHERE id = ?", userId) == 1;
  }

  @Override
  public UserRole roleOf(UUID userId) {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT perfil FROM usuario WHERE id = ?")) {
      statement.setString(1, userId.toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new IllegalArgumentException("Conta local não encontrada.");
        return UserRole.valueOf(result.getString(1));
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível consultar a conta local.", failure);
    }
  }

  @Override
  public int activeAdministratorCount() {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT count(*) FROM usuario WHERE ativo = 1 AND perfil = 'ADMINISTRADOR'")) {
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getInt(1) : 0;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "Não foi possível consultar administradores ativos.", failure);
    }
  }

  @Override
  public void changeRole(UUID userId, UserRole role) {
    requireTransaction();
    execute(
        "UPDATE usuario SET perfil = ?, atualizado_em_utc = strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE id = ? AND ativo = 1",
        role.name(), userId.toString());
  }

  @Override
  public void deactivate(UUID userId) {
    requireTransaction();
    execute(
        "UPDATE usuario SET ativo = 0, atualizado_em_utc = strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE id = ? AND ativo = 1",
        userId.toString());
  }

  private int scalar(String sql, UUID userId) {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, userId.toString());
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getInt(1) : 0;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível consultar a conta local.", failure);
    }
  }

  private void execute(String sql, String first, String second) {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, first);
      statement.setString(2, second);
      if (statement.executeUpdate() != 1)
        throw new IllegalArgumentException("Conta ativa não encontrada.");
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível atualizar a conta local.", failure);
    }
  }

  private void execute(String sql, String only) {
    requireTransaction();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, only);
      if (statement.executeUpdate() != 1)
        throw new IllegalArgumentException("Conta ativa não encontrada.");
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível atualizar a conta local.", failure);
    }
  }

  private void requireTransaction() {
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException("A gestão de contas exige transação local.");
    }
  }
}
