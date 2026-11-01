package br.com.espacotucuma.desktop.infrastructure.synchronization;

import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.outbox.ServerConfirmation;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.synchronization.DeviceCredentialStore;
import br.com.espacotucuma.desktop.application.port.synchronization.ManualSynchronizationPort;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import br.com.espacotucuma.sync.contract.OperationResult;
import br.com.espacotucuma.sync.contract.OperationSubmission;
import br.com.espacotucuma.sync.contract.OperationsRequest;
import br.com.espacotucuma.sync.contract.OperationsResponse;
import br.com.espacotucuma.sync.contract.OperationsStatusResponse;
import br.com.espacotucuma.sync.contract.SyncContract;
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

/** Sends pending outbox records only when explicitly invoked by the operator. */
public final class HttpManualSynchronizationClient implements ManualSynchronizationPort {
  private static final int BATCH_SIZE = 20;
  private static final int MAX_ATTEMPTS = 2;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
  private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

  private final URI serviceBaseUri;
  private final UUID deviceId;
  private final DeviceCredentialStore credentials;
  private final OutboxRepository outbox;
  private final UnitOfWork unitOfWork;
  private final HttpClient http;
  private final ObjectMapper json;
  private final Duration requestTimeout;

  public HttpManualSynchronizationClient(
      URI serviceBaseUri,
      UUID deviceId,
      DeviceCredentialStore credentials,
      OutboxRepository outbox,
      UnitOfWork unitOfWork) {
    this(
        serviceBaseUri,
        deviceId,
        credentials,
        outbox,
        unitOfWork,
        HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
        new ObjectMapper(),
        REQUEST_TIMEOUT);
  }

  HttpManualSynchronizationClient(
      URI serviceBaseUri,
      UUID deviceId,
      DeviceCredentialStore credentials,
      OutboxRepository outbox,
      UnitOfWork unitOfWork,
      HttpClient http,
      ObjectMapper json,
      Duration requestTimeout) {
    this.serviceBaseUri = normalize(Objects.requireNonNull(serviceBaseUri, "serviceBaseUri"));
    this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.http = Objects.requireNonNull(http, "http");
    this.json = Objects.requireNonNull(json, "json");
    this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
  }

  @Override
  public void synchronizeNow() {
    String credential =
        credentials
            .find(deviceId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Esta estação ainda não foi pareada para sincronização."));
    String token = requestAccessToken(credential);
    while (true) {
      List<PendingOperation> batch = outbox.findPending(BATCH_SIZE);
      if (batch.isEmpty()) return;
      for (PendingOperation operation : batch) {
        unitOfWork.execute(() -> outbox.recordAttempt(operation.id()));
      }
      if (!synchronizeBatch(batch, token)) {
        throw new IllegalStateException(
            "O serviço não confirmou todas as operações; as restantes continuam pendentes.");
      }
      if (batch.size() < BATCH_SIZE) return;
    }
  }

  private boolean synchronizeBatch(List<PendingOperation> batch, String token) {
    List<OperationSubmission> submissions = new ArrayList<>(batch.size());
    for (PendingOperation operation : batch) submissions.add(toSubmission(operation));
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      Map<UUID, OperationResult> known = requestStatuses(batch, token);
      Map<UUID, OperationResult> batchStatus = onlyBatchResults(batch, known);
      persistAccepted(batchStatus);
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
        persistAccepted(received);
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

  private void persistAccepted(Map<UUID, OperationResult> results) {
    for (OperationResult result : results.values()) {
      if (result.outcome() == OperationResult.Outcome.ACCEPTED && result.remoteVersion() > 0) {
        unitOfWork.execute(
            () ->
                outbox.markSynchronized(
                    new ServerConfirmation(
                        result.operationId(), result.remoteVersion(), Instant.now())));
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
