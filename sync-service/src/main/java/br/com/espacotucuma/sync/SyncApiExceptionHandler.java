package br.com.espacotucuma.sync;

import br.com.espacotucuma.sync.contract.ApiErrorResponse;
import br.com.espacotucuma.sync.contract.SyncErrorCode;
import br.com.espacotucuma.sync.contract.UnsupportedContractVersionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class SyncApiExceptionHandler {
  @ExceptionHandler(UnsupportedContractVersionException.class)
  ResponseEntity<ApiErrorResponse> unsupportedVersion() {
    return ResponseEntity.badRequest()
        .body(new ApiErrorResponse(SyncErrorCode.CONTRACT_VERSION_UNSUPPORTED));
  }

  @ExceptionHandler(DeviceAuthorizationException.class)
  ResponseEntity<ApiErrorResponse> unauthorizedDevice() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(new ApiErrorResponse(SyncErrorCode.AUTHORIZATION_REQUIRED));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  ResponseEntity<ApiErrorResponse> malformedRequest() {
    return ResponseEntity.badRequest().body(new ApiErrorResponse(SyncErrorCode.MALFORMED_REQUEST));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ApiErrorResponse> invalidRequest() {
    return ResponseEntity.badRequest().body(new ApiErrorResponse(SyncErrorCode.VALIDATION_FAILED));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiErrorResponse> internalError() {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ApiErrorResponse(SyncErrorCode.INTERNAL_ERROR));
  }
}
