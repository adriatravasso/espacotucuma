package br.com.espacotucuma.desktop.infrastructure.synchronization;

import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.outbox.ServerConfirmation;
import br.com.espacotucuma.desktop.application.port.audit.AuditRepository;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.synchronization.DeviceCredentialStore;
import br.com.espacotucuma.desktop.application.port.synchronization.ManualSynchronizationPort;
import br.com.espacotucuma.desktop.application.port.synchronization.RemoteBackupHistoryPort;
import br.com.espacotucuma.desktop.application.port.synchronization.RemoteChangeRepository;
import br.com.espacotucuma.desktop.application.port.synchronization.SynchronizationProgress;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteProtectionStatusRepository;
import br.com.espacotucuma.sync.contract.BackupRequest;
import br.com.espacotucuma.sync.contract.BackupResponse;
import br.com.espacotucuma.sync.contract.ChangesResponse;
import br.com.espacotucuma.sync.contract.OperationResult;
import br.com.espacotucuma.sync.contract.OperationSubmission;
import br.com.espacotucuma.sync.contract.OperationsRequest;
import br.com.espacotucuma.sync.contract.OperationsResponse;
import br.com.espacotucuma.sync.contract.OperationsStatusResponse;
import br.com.espacotucuma.sync.contract.SyncContract;
import br.com.espacotucuma.sync.contract.SyncStatusResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Sends pending outbox records only when explicitly invoked by the operator. */
public final class HttpManualSynchronizationClient
    implements ManualSynchronizationPort, RemoteBackupHistoryPort {
  private static final int BATCH_SIZE = 20;
  private static final int CHANGE_PAGE_SIZE = 100;
  private static final int MAX_ATTEMPTS = 2;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
  private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

  private final URI serviceBaseUri;
  private final UUID deviceId;
  private final DeviceCredentialStore credentials;
  private final OutboxRepository outbox;
  private final RemoteChangeRepository remoteChanges;
  private final UnitOfWork unitOfWork;
  private final HttpClient http;
  private final ObjectMapper json;
  private final Duration requestTimeout;
  private final AuthorizationService authorization;
  private final AuditRepository audit;
  private final SqliteProtectionStatusRepository protectionStatus;
  private final UUID stationId;

  public HttpManualSynchronizationClient(
      URI serviceBaseUri,
      UUID deviceId,
      DeviceCredentialStore credentials,
      OutboxRepository outbox,
      RemoteChangeRepository remoteChanges,
      UnitOfWork unitOfWork) {
    this(
        serviceBaseUri,
        deviceId,
        credentials,
        outbox,
        remoteChanges,
        unitOfWork,
        HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
        new ObjectMapper(),
        REQUEST_TIMEOUT,
        null,
        null,
        null,
        null);
  }

  public HttpManualSynchronizationClient(
      URI serviceBaseUri,
      UUID deviceId,
      DeviceCredentialStore credentials,
      OutboxRepository outbox,
      RemoteChangeRepository remoteChanges,
      UnitOfWork unitOfWork,
      AuthorizationService authorization,
      AuditRepository audit,
      SqliteProtectionStatusRepository protectionStatus,
      UUID stationId) {
    this(
        serviceBaseUri,
        deviceId,
        credentials,
        outbox,
        remoteChanges,
        unitOfWork,
        HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
        new ObjectMapper(),
        REQUEST_TIMEOUT,
        authorization,
        audit,
        protectionStatus,
        stationId);
  }

  HttpManualSynchronizationClient(
      URI serviceBaseUri,
      UUID deviceId,
      DeviceCredentialStore credentials,
      OutboxRepository outbox,
      RemoteChangeRepository remoteChanges,
      UnitOfWork unitOfWork,
      HttpClient http,
      ObjectMapper json,
      Duration requestTimeout) {
    this(
        serviceBaseUri,
        deviceId,
        credentials,
        outbox,
        remoteChanges,
        unitOfWork,
        http,
        json,
        requestTimeout,
        null,
        null,
        null,
        null);
  }

  HttpManualSynchronizationClient(
      URI serviceBaseUri,
      UUID deviceId,
      DeviceCredentialStore credentials,
      OutboxRepository outbox,
      RemoteChangeRepository remoteChanges,
      UnitOfWork unitOfWork,
      HttpClient http,
      ObjectMapper json,
      Duration requestTimeout,
      AuthorizationService authorization,
      AuditRepository audit,
      SqliteProtectionStatusRepository protectionStatus,
      UUID stationId) {
    this.serviceBaseUri = normalize(Objects.requireNonNull(serviceBaseUri, "serviceBaseUri"));
    this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.remoteChanges = Objects.requireNonNull(remoteChanges, "remoteChanges");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.http = Objects.requireNonNull(http, "http");
    this.json = Objects.requireNonNull(json, "json");
    this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    this.authorization = authorization;
    this.audit = audit;
    this.protectionStatus = protectionStatus;
    this.stationId = stationId;
  }

  @Override
  public void synchronizeNow() {
    synchronizeNow(progress -> {});
  }

  @Override
  public void synchronizeNow(Consumer<SynchronizationProgress> progress) {
    Objects.requireNonNull(progress, "progress");
    AuthenticatedUser operator = null;
    SyncCounts counts = new SyncCounts();
    try {
      progress.accept(SynchronizationProgress.of(SynchronizationProgress.State.VERIFYING_ACCESS));
      if (authorization != null) operator = authorization.requireUser(Permission.BACKUP_MANAGE);
      ensureNotInterrupted();
      String credential =
          credentials
              .find(deviceId)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Esta estação ainda não foi pareada para sincronização."));
      String token = requestAccessToken(credential);
      progress.accept(
          SynchronizationProgress.of(SynchronizationProgress.State.CHECKING_CHECKPOINT));
      SyncStatusResponse remoteStatus = requestRemoteStatus(token);
      if (remoteStatus.contractVersion() != SyncContract.VERSION
          || !deviceId.equals(remoteStatus.deviceId())
          || !"AVAILABLE".equals(remoteStatus.serviceStatus())
          || remoteStatus.checkpoint() < 0) {
        throw new IllegalStateException("O serviço remoto retornou um estado incompatível.");
      }
      while (true) {
        ensureNotInterrupted();
        List<PendingOperation> batch = outbox.findPending(BATCH_SIZE);
        if (batch.isEmpty()) break;
        progress.accept(
            progress(SynchronizationProgress.State.SENDING, counts, "Enviando lote local."));
        for (PendingOperation operation : batch) {
          unitOfWork.execute(() -> outbox.recordAttempt(operation.id()));
        }
        if (!synchronizeBatch(batch, token, progress, counts)) {
          throw new IllegalStateException(
              "O serviço não confirmou todas as operações; as restantes continuam pendentes.");
        }
        if (batch.size() < BATCH_SIZE) break;
      }
      synchronizeChanges(token, progress, counts);
      counts.checkpoint = remoteChanges.checkpoint();
      if (protectionStatus != null) {
        unitOfWork.execute(
            () -> {
              protectionStatus.recordSynchronization(Instant.now());
              return null;
            });
      }
      int unresolvedConflicts = remoteChanges.unresolvedConflictCount();
      boolean pendingOperations = !outbox.findPending(1).isEmpty();
      if (unresolvedConflicts > 0 || pendingOperations) {
        counts.conflicts = unresolvedConflicts;
        SynchronizationProgress result =
            progress(
                SynchronizationProgress.State.PENDING,
                counts,
                "Sincronizado com "
                    + unresolvedConflicts
                    + " conflito(s) e alterações pendentes; backup não solicitado.");
        progress.accept(result);
        recordAudit(operator, "SINCRONIZACAO_PENDENTE", result);
        return;
      }
      progress.accept(
          progress(
              SynchronizationProgress.State.REQUESTING_BACKUP,
              counts,
              "Solicitando backup remoto."));
      BackupAttempt backupAttempt = requestBackup(token, counts.checkpoint);
      BackupResponse backup = backupAttempt.response();
      if (backup != null) {
        if (protectionStatus != null) {
          unitOfWork.execute(
              () -> {
                protectionStatus.recordValidatedBackup(
                    backup.backupId().toString(), backup.createdAt());
                return null;
              });
        }
        SynchronizationProgress result =
            new SynchronizationProgress(
                SynchronizationProgress.State.BACKUP_VALIDATED,
                "Backup remoto validado: " + backup.backupId(),
                counts.sent,
                counts.received,
                counts.conflicts,
                counts.checkpoint,
                backup.backupId());
        progress.accept(result);
        recordAudit(operator, "SINCRONIZACAO_BACKUP_VALIDADO", result);
        return;
      }
      SynchronizationProgress result =
          progress(
              SynchronizationProgress.State.SYNCHRONIZED_NO_BACKUP,
              counts,
              "Dados sincronizados; backup remoto não validado. " + backupAttempt.detail());
      progress.accept(result);
      recordAudit(operator, "SINCRONIZACAO_SEM_BACKUP", result);
    } catch (RuntimeException failure) {
      SynchronizationProgress.State state =
          Thread.currentThread().isInterrupted()
              ? SynchronizationProgress.State.CANCELLED
              : isConnectionFailure(failure)
                  ? SynchronizationProgress.State.NO_CONNECTION
                  : SynchronizationProgress.State.FAILED;
      SynchronizationProgress result = progress(state, counts, safeMessage(failure, state.label()));
      progress.accept(result);
      recordAudit(operator, "SINCRONIZACAO_FALHOU", result);
      throw failure;
    }
  }

  @Override
  public boolean canListBackups() {
    return authorization != null && authorization.isAllowed(Permission.BACKUP_MANAGE);
  }

  @Override
  public List<RemoteBackupSummary> listAvailableBackups() {
    if (authorization != null) authorization.require(Permission.BACKUP_MANAGE);
    String credential =
        credentials
            .find(deviceId)
            .orElseThrow(() -> new IllegalStateException("Esta estação ainda não foi pareada."));
    String token = requestAccessToken(credential);
    HttpRequest request = versionedRequest("/sync/backups", token).GET().build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw httpFailure(response.statusCode());
      JsonNode body = json.readTree(response.body());
      if (body.path("contractVersion").asInt(-1) != SyncContract.VERSION
          || !body.path("backups").isArray()) {
        throw new IllegalStateException("A lista de backups retornou um contrato incompatível.");
      }
      List<RemoteBackupSummary> backups = new ArrayList<>();
      for (JsonNode backup : body.path("backups")) {
        if (backup.path("contractVersion").asInt(-1) != SyncContract.VERSION
            || !"VALIDADO".equals(backup.path("status").asText())
            || !backup.hasNonNull("backupId")
            || !backup.hasNonNull("createdAt")
            || backup.path("checkpoint").asLong(-1) < 0) {
          throw new IllegalStateException("A lista contém um ponto de recuperação inválido.");
        }
        backups.add(
            new RemoteBackupSummary(
                UUID.fromString(backup.path("backupId").asText()),
                backup.path("checkpoint").asLong(),
                Instant.parse(backup.path("createdAt").asText())));
      }
      return List.copyOf(backups);
    } catch (IOException failure) {
      throw new IllegalStateException("Não foi possível consultar os backups remotos.", failure);
    } catch (InterruptedException cancelled) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Consulta de backups cancelada.", cancelled);
    }
  }

  private void synchronizeChanges(
      String token, Consumer<SynchronizationProgress> progress, SyncCounts counts) {
    while (true) {
      ensureNotInterrupted();
      long checkpoint = remoteChanges.checkpoint();
      progress.accept(
          progress(
              SynchronizationProgress.State.RECEIVING,
              counts,
              "Baixando alterações após " + checkpoint + "."));
      HttpRequest request =
          versionedRequest(
                  "/sync/changes?after=" + checkpoint + "&limit=" + CHANGE_PAGE_SIZE, token)
              .GET()
              .build();
      ChangesResponse page;
      try {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw httpFailure(response.statusCode());
        page = json.readValue(response.body(), ChangesResponse.class);
      } catch (IOException failure) {
        throw new IllegalStateException("Não foi possível baixar as alterações remotas.", failure);
      } catch (InterruptedException cancelled) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Sincronização cancelada durante o download incremental.", cancelled);
      }
      validateChangePage(page, checkpoint);
      counts.received += page.changes().size();
      progress.accept(
          progress(
              SynchronizationProgress.State.RECONCILING,
              counts,
              "Persistindo página incremental."));
      unitOfWork.execute(
          () -> {
            remoteChanges.applyPage(checkpoint, page);
            return null;
          });
      if (page.changes().size() < CHANGE_PAGE_SIZE) return;
    }
  }

  private static void validateChangePage(ChangesResponse page, long after) {
    if (page == null
        || page.contractVersion() != SyncContract.VERSION
        || page.changes() == null
        || page.changes().size() > CHANGE_PAGE_SIZE) {
      throw new IllegalStateException("Resposta incremental incompatível com o contrato.");
    }
    long previous = after;
    java.util.Set<UUID> operations = new java.util.HashSet<>();
    for (ChangesResponse.RemoteChange change : page.changes()) {
      if (change == null
          || change.sequence() <= previous
          || change.operationId() == null
          || !operations.add(change.operationId())
          || change.sourceDeviceId() == null
          || change.entityType() == null
          || change.entityId() == null
          || change.operationType() == null
          || change.payloadVersion() < 1
          || change.entityVersion() < 0
          || change.outcome() == null
          || change.payload() == null) {
        throw new IllegalStateException(
            "O lote incremental contém dados malformados ou fora de ordem.");
      }
      previous = change.sequence();
    }
    long expected = page.changes().isEmpty() ? after : previous;
    if (page.checkpoint() != expected) {
      throw new IllegalStateException("O cursor remoto não corresponde ao lote recebido.");
    }
  }

  private boolean synchronizeBatch(
      List<PendingOperation> batch,
      String token,
      Consumer<SynchronizationProgress> progress,
      SyncCounts counts) {
    List<OperationSubmission> submissions = new ArrayList<>(batch.size());
    for (PendingOperation operation : batch) submissions.add(toSubmission(operation));
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      ensureNotInterrupted();
      progress.accept(
          progress(
              SynchronizationProgress.State.CONFIRMING,
              counts,
              "Conferindo confirmações do lote."));
      Map<UUID, OperationResult> known = requestStatuses(batch, token);
      Map<UUID, OperationResult> batchStatus = onlyBatchResults(batch, known);
      persistAccepted(batchStatus, counts);
      List<OperationSubmission> missing =
          submissions.stream()
              .filter(item -> !batchStatus.containsKey(item.operationId()))
              .toList();
      if (missing.isEmpty()) return allAccepted(batch, batchStatus);
      try {
        OperationsResponse response = postOperations(missing, token);
        if (response.contractVersion() != SyncContract.VERSION) {
          throw new IllegalStateException("Versão incompatível do contrato de sincronização.");
        }
        Map<UUID, OperationResult> acknowledged = new HashMap<>(batchStatus);
        Map<UUID, OperationResult> received =
            onlyBatchResults(batch, indexResults(response.results()));
        acknowledged.putAll(received);
        persistAccepted(received, counts);
        return allAccepted(batch, acknowledged);
      } catch (IOException failure) {
        if (attempt == MAX_ATTEMPTS) {
          throw new IllegalStateException(
              "A conexão caiu durante a sincronização; as operações sem confirmação continuam pendentes.",
              failure);
        }
      } catch (InterruptedException cancelled) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Sincronização cancelada; as operações sem confirmação continuam pendentes.",
            cancelled);
      }
    }
    return false;
  }

  private SyncStatusResponse requestRemoteStatus(String token) {
    HttpRequest request = versionedRequest("/sync/status", token).GET().build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw httpFailure(response.statusCode());
      return json.readValue(response.body(), SyncStatusResponse.class);
    } catch (IOException failure) {
      throw new IllegalStateException(
          "Não foi possível verificar o serviço de sincronização.", failure);
    } catch (InterruptedException cancelled) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Sincronização cancelada durante a verificação.", cancelled);
    }
  }

  private BackupAttempt requestBackup(String token, long checkpoint) {
    try {
      String body = json.writeValueAsString(new BackupRequest(SyncContract.VERSION, checkpoint));
      HttpRequest request =
          versionedRequest("/sync/backup", token)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return new BackupAttempt(
            null, "O serviço respondeu HTTP " + response.statusCode() + " sem validar o backup.");
      }
      JsonNode bodyNode = json.readTree(response.body());
      BackupResponse backup =
          new BackupResponse(
              bodyNode.path("contractVersion").asInt(-1),
              bodyNode.hasNonNull("backupId")
                  ? UUID.fromString(bodyNode.path("backupId").asText())
                  : null,
              bodyNode.path("checkpoint").asLong(-1),
              bodyNode.path("status").asText(null),
              bodyNode.hasNonNull("createdAt")
                  ? Instant.parse(bodyNode.path("createdAt").asText())
                  : null);
      if (backup.contractVersion() != SyncContract.VERSION
          || backup.backupId() == null
          || backup.checkpoint() != checkpoint
          || !"VALIDADO".equals(backup.status())
          || backup.createdAt() == null) {
        return new BackupAttempt(null, "A resposta do backup não confirmou o checkpoint enviado.");
      }
      return new BackupAttempt(backup, "Backup remoto validado.");
    } catch (IOException | InterruptedException failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      return new BackupAttempt(
          null,
          failure instanceof InterruptedException
              ? "A solicitação de backup foi cancelada."
              : "Falha de conexão ao solicitar o backup remoto.");
    } catch (RuntimeException invalidResponse) {
      return new BackupAttempt(null, "A resposta de validação do backup é inválida.");
    }
  }

  private void recordAudit(
      AuthenticatedUser operator, String action, SynchronizationProgress result) {
    if (operator == null || audit == null || stationId == null) return;
    String details =
        "{\"estado\":\""
            + result.state()
            + "\",\"enviadas\":"
            + result.sentOperations()
            + ",\"recebidas\":"
            + result.receivedChanges()
            + ",\"conflitos\":"
            + result.conflicts()
            + ",\"checkpoint\":"
            + result.checkpoint()
            + ",\"backupId\":"
            + (result.backupId() == null ? "null" : "\"" + result.backupId() + "\"")
            + "}";
    unitOfWork.execute(
        () -> {
          audit.record(
              new AuditEvent(
                  UUID.randomUUID(),
                  operator.id(),
                  UUID.randomUUID(),
                  action,
                  "SINCRONIZACAO",
                  stationId,
                  stationId,
                  details,
                  null,
                  Instant.now()));
          return null;
        });
  }

  private static SynchronizationProgress progress(
      SynchronizationProgress.State state, SyncCounts counts, String detail) {
    return new SynchronizationProgress(
        state, detail, counts.sent, counts.received, counts.conflicts, counts.checkpoint, null);
  }

  private static boolean isConnectionFailure(RuntimeException failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof IOException || current instanceof java.net.ConnectException)
        return true;
    }
    return false;
  }

  private static String safeMessage(RuntimeException failure, String fallback) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? fallback : message;
  }

  private static void ensureNotInterrupted() {
    if (Thread.currentThread().isInterrupted()) {
      throw new java.util.concurrent.CancellationException(
          "Sincronização cancelada pelo operador.");
    }
  }

  private static final class SyncCounts {
    private int sent;
    private int received;
    private int conflicts;
    private long checkpoint;
  }

  private record BackupAttempt(BackupResponse response, String detail) {}

  private static boolean allAccepted(
      List<PendingOperation> batch, Map<UUID, OperationResult> results) {
    return batch.stream()
        .allMatch(
            operation -> {
              OperationResult result = results.get(operation.id());
              return result != null
                  && result.outcome() == OperationResult.Outcome.ACCEPTED
                  && result.remoteVersion() > 0;
            });
  }

  private Map<UUID, OperationResult> requestStatuses(List<PendingOperation> batch, String token) {
    String query =
        batch.stream()
            .map(item -> "ids=" + item.id())
            .collect(java.util.stream.Collectors.joining("&"));
    HttpRequest request = versionedRequest("/sync/operations/status?" + query, token).GET().build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw httpFailure(response.statusCode());
      OperationsStatusResponse status =
          json.readValue(response.body(), OperationsStatusResponse.class);
      if (status.contractVersion() != SyncContract.VERSION) {
        throw new IllegalStateException("Versão incompatível do contrato de sincronização.");
      }
      return indexResults(status.results());
    } catch (IOException failure) {
      throw new IllegalStateException(
          "Não foi possível consultar as confirmações remotas.", failure);
    } catch (InterruptedException cancelled) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Sincronização cancelada durante a consulta de confirmações.", cancelled);
    }
  }

  private OperationsResponse postOperations(List<OperationSubmission> operations, String token)
      throws IOException, InterruptedException {
    String body = json.writeValueAsString(new OperationsRequest(SyncContract.VERSION, operations));
    HttpRequest request =
        versionedRequest("/sync/operations", token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) throw httpFailure(response.statusCode());
    return json.readValue(response.body(), OperationsResponse.class);
  }

  private String requestAccessToken(String credential) {
    HttpRequest request =
        HttpRequest.newBuilder(serviceBaseUri.resolve("/sync/devices/token"))
            .timeout(requestTimeout)
            .header("X-Tucuma-Device-Id", deviceId.toString())
            .header("Authorization", "Device " + credential)
            .header(SyncContract.VERSION_HEADER, Integer.toString(SyncContract.VERSION))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw httpFailure(response.statusCode());
      JsonNode token = json.readTree(response.body()).get("accessToken");
      if (token == null || token.asText().isBlank()) {
        throw new IllegalStateException("O serviço remoto não retornou um token de acesso.");
      }
      return token.asText();
    } catch (IOException failure) {
      throw new IllegalStateException(
          "Não foi possível autenticar a estação no serviço remoto.", failure);
    } catch (InterruptedException cancelled) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Sincronização cancelada durante a autenticação.", cancelled);
    }
  }

  private HttpRequest.Builder versionedRequest(String path, String token) {
    return HttpRequest.newBuilder(serviceBaseUri.resolve(path))
        .timeout(requestTimeout)
        .header("Authorization", "Bearer " + token)
        .header(SyncContract.VERSION_HEADER, Integer.toString(SyncContract.VERSION));
  }

  private OperationSubmission toSubmission(PendingOperation operation) {
    try {
      return new OperationSubmission(
          operation.id(),
          operation.idempotencyKey(),
          operation.entity(),
          operation.entityId(),
          operation.type(),
          operation.expectedVersion(),
          operation.payloadVersion(),
          json.readValue(operation.payloadJson(), JSON_OBJECT));
    } catch (IOException failure) {
      throw new IllegalStateException("O payload pendente não é um objeto JSON válido.", failure);
    }
  }

  private static Map<UUID, OperationResult> indexResults(List<OperationResult> results) {
    Map<UUID, OperationResult> indexed = new HashMap<>();
    for (OperationResult result : results) indexed.putIfAbsent(result.operationId(), result);
    return indexed;
  }

  private static Map<UUID, OperationResult> onlyBatchResults(
      List<PendingOperation> batch, Map<UUID, OperationResult> results) {
    return results.entrySet().stream()
        .filter(entry -> batch.stream().anyMatch(item -> item.id().equals(entry.getKey())))
        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private void persistAccepted(Map<UUID, OperationResult> results, SyncCounts counts) {
    for (OperationResult result : results.values()) {
      if (result.outcome() == OperationResult.Outcome.ACCEPTED && result.remoteVersion() > 0) {
        boolean marked =
            unitOfWork.execute(
                () ->
                    outbox.markSynchronized(
                        new ServerConfirmation(
                            result.operationId(), result.remoteVersion(), Instant.now())));
        if (marked) counts.sent++;
      }
    }
  }

  private static IllegalStateException httpFailure(int status) {
    return new IllegalStateException(
        "O serviço de sincronização respondeu com HTTP " + status + ".");
  }

  private static URI normalize(URI uri) {
    String scheme = uri.getScheme();
    boolean localHttp =
        "http".equalsIgnoreCase(scheme)
            && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
    if (!"https".equalsIgnoreCase(scheme) && !localHttp) {
      throw new IllegalArgumentException("A sincronização exige HTTPS.");
    }
    String value = uri.toString();
    return URI.create(value.endsWith("/") ? value : value + "/");
  }
}
