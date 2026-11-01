package br.com.espacotucuma.sync.contract;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ChangesResponse(int contractVersion, long checkpoint, List<RemoteChange> changes) {
  public record RemoteChange(
      long sequence,
      UUID operationId,
      UUID sourceDeviceId,
      String entityType,
      UUID entityId,
      String operationType,
      int payloadVersion,
      long entityVersion,
      String outcome,
      Map<String, Object> payload) {}
}
