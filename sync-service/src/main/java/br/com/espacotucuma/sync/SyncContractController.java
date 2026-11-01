package br.com.espacotucuma.sync;

import br.com.espacotucuma.sync.contract.ApiErrorResponse;
import br.com.espacotucuma.sync.contract.BackupRequest;
import br.com.espacotucuma.sync.contract.OperationResult;
import br.com.espacotucuma.sync.contract.OperationsRequest;
import br.com.espacotucuma.sync.contract.OperationsResponse;
import br.com.espacotucuma.sync.contract.OperationsStatusResponse;
import br.com.espacotucuma.sync.contract.RestoreRequest;
import br.com.espacotucuma.sync.contract.SyncContract;
import br.com.espacotucuma.sync.contract.SyncContractValidator;
import br.com.espacotucuma.sync.contract.SyncErrorCode;
import br.com.espacotucuma.sync.contract.SyncStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

  @GetMapping("/operations/status")
  OperationsStatusResponse operationStatus(
      @RequestParam("ids") List<UUID> ids, HttpServletRequest request) {
    if (ids.isEmpty() || ids.size() > 20) {
      throw new IllegalArgumentException("A consulta deve conter de 1 a 20 operações.");
    }
    var establishmentId = (UUID) request.getAttribute(SyncAuthenticationFilter.ESTABLISHMENT_ID);
    var deviceId = (UUID) request.getAttribute(SyncAuthenticationFilter.DEVICE_ID);
    List<OperationResult> results =
        jdbc.query(
            "SELECT id::text AS operation_id, resultado_estado, "
                + "COALESCE((resultado->>'remoteVersion')::bigint, 0) AS remote_version "
                + "FROM operacao_recebida WHERE estabelecimento_id = ? AND dispositivo_id = ? "
                + "AND id IN ("
                + String.join(",", java.util.Collections.nCopies(ids.size(), "?"))
                + ")",
            (rs, rowNum) -> {
              var outcome = rs.getString("resultado_estado");
              return new OperationResult(
                  UUID.fromString(rs.getString("operation_id")),
                  switch (outcome) {
                    case "ACEITA" -> OperationResult.Outcome.ACCEPTED;
                    case "REJEITADA" -> OperationResult.Outcome.REJECTED;
                    case "CONFLITO" -> OperationResult.Outcome.CONFLICT;
                    default -> throw new IllegalStateException("Resultado remoto desconhecido.");
                  },
                  rs.getLong("remote_version"),
                  "ACEITA".equals(outcome) ? null : SyncErrorCode.CONFLICT);
            },
            queryArguments(establishmentId, deviceId, ids));
    return new OperationsStatusResponse(SyncContract.VERSION, results);
  }

  private static Object[] queryArguments(UUID establishmentId, UUID deviceId, List<UUID> ids) {
    Object[] arguments = new Object[ids.size() + 2];
    arguments[0] = establishmentId;
    arguments[1] = deviceId;
    for (int index = 0; index < ids.size(); index++) arguments[index + 2] = ids.get(index);
    return arguments;
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
