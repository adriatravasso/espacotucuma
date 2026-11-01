package br.com.espacotucuma.sync;

import br.com.espacotucuma.sync.contract.ApiErrorResponse;
import br.com.espacotucuma.sync.contract.BackupRequest;
import br.com.espacotucuma.sync.contract.OperationResult;
import br.com.espacotucuma.sync.contract.OperationsRequest;
import br.com.espacotucuma.sync.contract.OperationsResponse;
import br.com.espacotucuma.sync.contract.RestoreRequest;
import br.com.espacotucuma.sync.contract.SyncContract;
import br.com.espacotucuma.sync.contract.SyncContractValidator;
import br.com.espacotucuma.sync.contract.SyncErrorCode;
import br.com.espacotucuma.sync.contract.SyncStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Versioned REST boundary; operation persistence is delegated to the transactional receiver. */
@RestController
@RequestMapping("/sync")
class SyncContractController {
  private final JdbcTemplate jdbc;
  private final SyncOperationReceiver operationReceiver;

  SyncContractController(JdbcTemplate jdbc, SyncOperationReceiver operationReceiver) {
    this.jdbc = jdbc;
    this.operationReceiver = operationReceiver;
  }

  @GetMapping("/status")
  SyncStatusResponse status(HttpServletRequest request) {
    var establishmentId =
        (java.util.UUID) request.getAttribute(SyncAuthenticationFilter.ESTABLISHMENT_ID);
    var deviceId = (java.util.UUID) request.getAttribute(SyncAuthenticationFilter.DEVICE_ID);
    long checkpoint =
        jdbc.queryForObject(
            "SELECT COALESCE((SELECT ultima_sequencia FROM checkpoint_sincronizacao "
                + "WHERE estabelecimento_id = ? AND dispositivo_id = ?), 0)",
            Long.class,
            establishmentId,
            deviceId);
    return new SyncStatusResponse(
        SyncContract.VERSION, establishmentId, deviceId, "AVAILABLE", checkpoint);
  }

  @PostMapping("/operations")
  OperationsResponse submitOperations(
      @RequestBody OperationsRequest request, HttpServletRequest httpRequest) {
    SyncContractValidator.validate(request);
    var establishmentId =
        (java.util.UUID) httpRequest.getAttribute(SyncAuthenticationFilter.ESTABLISHMENT_ID);
    var deviceId = (java.util.UUID) httpRequest.getAttribute(SyncAuthenticationFilter.DEVICE_ID);
    List<OperationResult> results = new ArrayList<>(request.operations().size());
    for (var operation : request.operations()) {
      results.add(operationReceiver.receiveOne(establishmentId, deviceId, operation));
    }
    long checkpoint =
        jdbc.queryForObject(
            "SELECT COALESCE((SELECT ultima_sequencia FROM checkpoint_sincronizacao "
                + "WHERE estabelecimento_id = ? AND dispositivo_id = ?), 0)",
            Long.class,
            establishmentId,
            deviceId);
    return new OperationsResponse(SyncContract.VERSION, checkpoint, List.copyOf(results));
  }

  @GetMapping("/changes")
  ResponseEntity<ApiErrorResponse> changes(@RequestParam long after) {
    if (after < 0) throw new IllegalArgumentException("Invalid checkpoint");
    return notImplemented();
  }

  @PostMapping("/backup")
  ResponseEntity<ApiErrorResponse> createBackup(@RequestBody BackupRequest request) {
    SyncContractValidator.validate(request);
    return notImplemented();
  }

  @GetMapping("/backups")
  ResponseEntity<ApiErrorResponse> listBackups() {
    return notImplemented();
  }

  @PostMapping("/restore-request")
  ResponseEntity<ApiErrorResponse> requestRestore(@RequestBody RestoreRequest request) {
    SyncContractValidator.validate(request);
    return notImplemented();
  }

  private static ResponseEntity<ApiErrorResponse> notImplemented() {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
        .body(new ApiErrorResponse(SyncErrorCode.NOT_IMPLEMENTED));
  }
}
