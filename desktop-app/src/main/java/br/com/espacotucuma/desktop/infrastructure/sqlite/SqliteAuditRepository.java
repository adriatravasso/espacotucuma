package br.com.espacotucuma.desktop.infrastructure.sqlite;

import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.port.audit.AuditRepository;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public final class SqliteAuditRepository implements AuditRepository {
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
  private final java.sql.Connection connection;
  private final SqliteUnitOfWork unitOfWork;

  public SqliteAuditRepository(java.sql.Connection connection, SqliteUnitOfWork unitOfWork) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
  }

  @Override
  public void record(AuditEvent event) {
    Objects.requireNonNull(event, "event");
    if (!unitOfWork.isExecutingOnCurrentThread()) {
      throw new IllegalStateException(
          "A auditoria de negócio exige a transação local da operação.");
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO evento_auditoria (id, usuario_id, operacao_id, acao, entidade, entidade_id, "
                + "detalhes_json, ocorrido_em_utc, origem_estacao_id, motivo) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      statement.setString(1, event.id().toString());
      statement.setString(2, event.userId().toString());
      statement.setString(3, event.operationId().toString());
      statement.setString(4, event.action());
      statement.setString(5, event.entity());
      statement.setString(6, event.entityId().toString());
      statement.setString(7, event.detailsJson());
      statement.setString(8, UTC.format(event.occurredAt()));
      statement.setString(9, event.originStationId().toString());
      statement.setString(10, event.reason());
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException("Não foi possível gravar a auditoria local.", failure);
    }
  }
}
