package br.com.espacotucuma.sync.contract;

/** Safe, stable error shape; it deliberately contains no persistence or exception details. */
public record ApiErrorResponse(int contractVersion, SyncErrorCode code, boolean retryable) {
  public ApiErrorResponse(SyncErrorCode code) {
    this(SyncContract.VERSION, code, code.retryable());
  }
}
