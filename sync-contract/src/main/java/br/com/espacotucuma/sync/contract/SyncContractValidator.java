package br.com.espacotucuma.sync.contract;

import java.util.HashSet;

/** Dependency-free validation reused by desktop and service. */
public final class SyncContractValidator {
  private SyncContractValidator() {}

  public static void validateVersion(int version) {
    if (version != SyncContract.VERSION) {
      throw new UnsupportedContractVersionException();
    }
  }

  public static void validate(OperationsRequest request) {
    if (request == null) throw new IllegalArgumentException("Missing request");
    validateVersion(request.contractVersion());
    if (request.operations() == null
        || request.operations().isEmpty()
        || request.operations().size() > SyncContract.MAX_OPERATIONS_PER_REQUEST) {
      throw new IllegalArgumentException("Invalid operation batch size");
    }
    var ids = new HashSet<java.util.UUID>();
    var keys = new HashSet<String>();
    for (OperationSubmission operation : request.operations()) {
      if (operation == null
          || operation.operationId() == null
          || !ids.add(operation.operationId())
          || operation.idempotencyKey() == null
          || operation.idempotencyKey().isBlank()
          || operation.idempotencyKey().length() > 160
          || !keys.add(operation.idempotencyKey())
          || blankOrTooLong(operation.entityType(), 80)
          || operation.entityId() == null
          || blankOrTooLong(operation.operationType(), 80)
          || operation.entityVersion() < 0
          || operation.payloadVersion() < 1
          || operation.payload() == null) {
        throw new IllegalArgumentException("Invalid operation");
      }
    }
  }

  public static void validate(BackupRequest request) {
    if (request == null) throw new IllegalArgumentException("Missing request");
    validateVersion(request.contractVersion());
    if (request.checkpoint() < 0) throw new IllegalArgumentException("Invalid checkpoint");
  }

  public static void validate(RestoreRequest request) {
    if (request == null) throw new IllegalArgumentException("Missing request");
    validateVersion(request.contractVersion());
    if (request.backupId() == null) throw new IllegalArgumentException("Missing backup identifier");
  }

  private static boolean blankOrTooLong(String value, int maxLength) {
    return value == null || value.isBlank() || value.length() > maxLength;
  }
}
