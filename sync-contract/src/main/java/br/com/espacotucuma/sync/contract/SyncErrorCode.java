package br.com.espacotucuma.sync.contract;

/** Stable error identifiers. Connection and timeout codes are client-side transport errors. */
public enum SyncErrorCode {
  CONNECTION_UNAVAILABLE(true),
  REQUEST_TIMEOUT(true),
  RETRY_LATER(true),
  AUTHORIZATION_REQUIRED(false),
  VALIDATION_FAILED(false),
  MALFORMED_REQUEST(false),
  CONTRACT_VERSION_UNSUPPORTED(false),
  CONFLICT(false),
  PAYLOAD_TOO_LARGE(false),
  NOT_IMPLEMENTED(false),
  INTERNAL_ERROR(true);

  private final boolean retryable;

  SyncErrorCode(boolean retryable) {
    this.retryable = retryable;
  }

  public boolean retryable() {
    return retryable;
  }
}
