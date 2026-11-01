package br.com.espacotucuma.sync.contract;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ChangesResponse(int contractVersion, long checkpoint, List<RemoteChange> changes) {
  public record RemoteChange(
      UUID operationId,
      String entityType,
      UUID entityId,
      String operationType,
      long entityVersion,
      Map<String, Object> payload) {}
}
