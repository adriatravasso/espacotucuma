package br.com.espacotucuma.desktop.infrastructure.synchronization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.outbox.ServerConfirmation;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.synchronization.DeviceCredentialStore;
import br.com.espacotucuma.desktop.application.port.synchronization.RemoteChangeRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import br.com.espacotucuma.sync.contract.ChangesResponse;
import br.com.espacotucuma.sync.contract.OperationResult;
import br.com.espacotucuma.sync.contract.OperationsResponse;
import br.com.espacotucuma.sync.contract.OperationsStatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;

class HttpManualSynchronizationClientTest {
  private static final UUID DEVICE_ID = UUID.fromString("a58f36e1-10f6-45e8-85f5-981dd99e8166");
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void lostBatchAcknowledgementIsQueriedBeforeOnlyMissingOperationIsRetried() throws Exception {
    MemoryOutbox outbox = new MemoryOutbox(List.of(operation(1), operation(2)));
    PendingOperation first = outbox.pending.getFirst();
    PendingOperation second = outbox.pending.get(1);
    OperationResult acceptedFirst = accepted(first.id());
    SimulatedHttpClient http =
        new SimulatedHttpClient(
            response("{\"accessToken\":\"token\"}"),
            response(status(List.of())),
            new IOException("connection dropped after the first remote commit"),
            response(status(List.of(acceptedFirst))),
            response(operations(List.of(accepted(second.id())))),
            response(changes(0, List.of())));

    client(outbox, http).synchronizeNow();

    assertTrue(outbox.pending.isEmpty());
    assertEquals(List.of(first.id(), second.id()), outbox.confirmations);
    assertEquals(
        List.of(
            "/sync/devices/token",
            "/sync/operations/status",
            "/sync/operations",
            "/sync/operations/status",
            "/sync/operations",
            "/sync/changes"),
        http.paths);
    assertEquals(first.payloadJson(), outbox.originalPayloads.get(first.id()));
    assertEquals(second.idempotencyKey(), outbox.originalKeys.get(second.id()));
  }

  @Test
  void timeoutLeavesOperationPendingForTheNextManualAttempt() throws Exception {
    PendingOperation operation = operation(1);
    MemoryOutbox outbox = new MemoryOutbox(List.of(operation));
    SimulatedHttpClient http =
        new SimulatedHttpClient(
            response("{\"accessToken\":\"token\"}"),
            response(status(List.of())),
            new HttpTimeoutException("simulated timeout"),
            response(status(List.of())),
            new HttpTimeoutException("simulated timeout"));

    assertThrows(IllegalStateException.class, () -> client(outbox, http).synchronizeNow());

    assertEquals(
        List.of(operation.id()), outbox.pending.stream().map(PendingOperation::id).toList());
    assertTrue(outbox.confirmations.isEmpty());
    assertEquals(2, http.paths.stream().filter("/sync/operations"::equals).count());
  }

  @Test
  void duplicateAcknowledgementMarksOnlyTheConfirmedOperationOnce() throws Exception {
    PendingOperation operation = operation(1);
    MemoryOutbox outbox = new MemoryOutbox(List.of(operation));
    OperationResult accepted = accepted(operation.id());
    SimulatedHttpClient http =
        new SimulatedHttpClient(
            response("{\"accessToken\":\"token\"}"),
            response(status(List.of())),
            response(operations(List.of(accepted, accepted))),
            response(changes(0, List.of())));

    client(outbox, http).synchronizeNow();

    assertTrue(outbox.pending.isEmpty());
    assertEquals(List.of(operation.id()), outbox.confirmations);
  }

  @Test
  void downloadsRemotePagesInSequenceAndPersistsBeforeRequestingNextPage() throws Exception {
    List<ChangesResponse.RemoteChange> firstPage =
        java.util.stream.LongStream.rangeClosed(1, 100)
            .mapToObj(HttpManualSynchronizationClientTest::remoteChange)
            .toList();
    MemoryRemoteChanges changes = new MemoryRemoteChanges();
    SimulatedHttpClient http =
        new SimulatedHttpClient(
            response("{\"accessToken\":\"token\"}"),
            response(changes(100, firstPage)),
            response(changes(100, List.of())));

    client(new MemoryOutbox(List.of()), changes, http).synchronizeNow();

    assertEquals(100, changes.checkpoint);
    assertEquals(100, changes.applied.getFirst().changes().size());
    assertEquals(List.of("/sync/devices/token", "/sync/changes", "/sync/changes"), http.paths);
  }

  @Test
  void malformedOutOfOrderPageIsRejectedWithoutAdvancingCheckpoint() throws Exception {
    MemoryRemoteChanges changes = new MemoryRemoteChanges();
    SimulatedHttpClient http =
        new SimulatedHttpClient(
            response("{\"accessToken\":\"token\"}"),
            response(changes(2, List.of(remoteChange(2), remoteChange(1)))));

    assertThrows(
        IllegalStateException.class,
        () -> client(new MemoryOutbox(List.of()), changes, http).synchronizeNow());

    assertEquals(0, changes.checkpoint);
    assertTrue(changes.applied.isEmpty());
  }

  @Test
  void interruptedDownloadResumesFromLastCommittedCheckpoint() throws Exception {
    List<ChangesResponse.RemoteChange> firstPage =
        java.util.stream.LongStream.rangeClosed(1, 100)
            .mapToObj(HttpManualSynchronizationClientTest::remoteChange)
            .toList();
    MemoryRemoteChanges changes = new MemoryRemoteChanges();
    SimulatedHttpClient interrupted =
        new SimulatedHttpClient(
            response("{\"accessToken\":\"token\"}"),
            response(changes(100, firstPage)),
            new IOException("network dropped before the second page"));

    assertThrows(
        IllegalStateException.class,
        () -> client(new MemoryOutbox(List.of()), changes, interrupted).synchronizeNow());
    assertEquals(100, changes.checkpoint);

    SimulatedHttpClient resumed =
        new SimulatedHttpClient(
            response("{\"accessToken\":\"token\"}"),
            response(changes(101, List.of(remoteChange(101)))),
            response(changes(101, List.of())));
    client(new MemoryOutbox(List.of()), changes, resumed).synchronizeNow();

    assertEquals(101, changes.checkpoint);
    assertEquals(100, changes.applied.getFirst().changes().size());
    assertEquals(1, changes.applied.get(1).changes().size());
    assertTrue(resumed.paths.contains("/sync/changes"));
  }

  private static HttpManualSynchronizationClient client(
      MemoryOutbox outbox, SimulatedHttpClient http) {
    return client(outbox, new MemoryRemoteChanges(), http);
  }

  private static HttpManualSynchronizationClient client(
      MemoryOutbox outbox, MemoryRemoteChanges remoteChanges, SimulatedHttpClient http) {
    DeviceCredentialStore credentials =
        new DeviceCredentialStore() {
          public void save(UUID deviceId, String credential) {}

          public Optional<String> find(UUID deviceId) {
            return Optional.of("credential");
          }

          public void delete(UUID deviceId) {}
        };
    UnitOfWork unitOfWork =
        new UnitOfWork() {
          public <T> T execute(java.util.function.Supplier<T> operation) {
            return operation.get();
          }
        };
    return new HttpManualSynchronizationClient(
        URI.create("http://localhost"),
        DEVICE_ID,
        credentials,
        outbox,
        remoteChanges,
        unitOfWork,
        http,
        JSON,
        Duration.ofMillis(100));
  }

  private static PendingOperation operation(int index) {
    return new PendingOperation(
        UUID.fromString("00000000-0000-0000-0000-00000000000" + index),
        "cliente",
        UUID.fromString("10000000-0000-0000-0000-00000000000" + index),
        "CLIENTE_CRIADO",
        "stable-key-" + index,
        "{\"nome\":\"Cliente " + index + "\"}",
        1,
        0,
        DEVICE_ID,
        Instant.parse("2026-11-01T12:00:00Z").plusSeconds(index),
        0);
  }

  private static OperationResult accepted(UUID operationId) {
    return new OperationResult(operationId, OperationResult.Outcome.ACCEPTED, 1, null);
  }

  private static String status(List<OperationResult> results) throws IOException {
    return JSON.writeValueAsString(new OperationsStatusResponse(1, results));
  }

  private static String operations(List<OperationResult> results) throws IOException {
    return JSON.writeValueAsString(new OperationsResponse(1, results.size(), results));
  }

  private static String changes(long checkpoint, List<ChangesResponse.RemoteChange> changes)
      throws IOException {
    return JSON.writeValueAsString(new ChangesResponse(1, checkpoint, changes));
  }

  private static ChangesResponse.RemoteChange remoteChange(long sequence) {
    return new ChangesResponse.RemoteChange(
        sequence,
        UUID.nameUUIDFromBytes(
            ("remote-operation-" + sequence).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        UUID.fromString("30000000-0000-0000-0000-000000000001"),
        "cliente",
        UUID.fromString("40000000-0000-0000-0000-000000000001"),
        "CLIENTE_CRIADO",
        1,
        sequence,
        "ACEITA",
        Map.of("nome", "Pessoa sintética"));
  }

  private static final class MemoryRemoteChanges implements RemoteChangeRepository {
    private long checkpoint;
    private final List<ChangesResponse> applied = new ArrayList<>();

    public long checkpoint() {
      return checkpoint;
    }

    public void applyPage(long expectedCheckpoint, ChangesResponse page) {
      if (expectedCheckpoint != checkpoint) throw new IllegalStateException("stale cursor");
      applied.add(page);
      checkpoint = page.checkpoint();
    }
  }

  private static HttpResponse<String> response(String body) {
    return new SimulatedResponse(body);
  }

  private static final class MemoryOutbox implements OutboxRepository {
    private final List<PendingOperation> pending;
    private final List<UUID> confirmations = new ArrayList<>();
    private final Map<UUID, String> originalPayloads = new java.util.HashMap<>();
    private final Map<UUID, String> originalKeys = new java.util.HashMap<>();

    private MemoryOutbox(List<PendingOperation> operations) {
      pending = new ArrayList<>(operations);
      operations.forEach(
          item -> {
            originalPayloads.put(item.id(), item.payloadJson());
            originalKeys.put(item.id(), item.idempotencyKey());
          });
    }

    public void enqueue(PendingOperation operation) {
      pending.add(operation);
    }

    public long expectedVersionForNext(String entity, UUID entityId) {
      return 0;
    }

    public List<PendingOperation> findPending(int limit) {
      return List.copyOf(pending.subList(0, Math.min(limit, pending.size())));
    }

    public boolean recordAttempt(UUID operationId) {
      return pending.stream().anyMatch(item -> item.id().equals(operationId));
    }

    public boolean markSynchronized(ServerConfirmation confirmation) {
      if (pending.removeIf(item -> item.id().equals(confirmation.operationId()))) {
        confirmations.add(confirmation.operationId());
        return true;
      }
      return false;
    }
  }

  private static final class SimulatedHttpClient extends HttpClient {
    private final Deque<Object> responses = new ArrayDeque<>();
    private final List<String> paths = new ArrayList<>();

    private SimulatedHttpClient(Object... responses) {
      this.responses.addAll(List.of(responses));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        throws IOException {
      paths.add(request.uri().getPath());
      Object next = responses.removeFirst();
      if (next instanceof IOException failure) throw failure;
      return (HttpResponse<T>) next;
    }

    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    public SSLContext sslContext() {
      return null;
    }

    public SSLParameters sslParameters() {
      return new SSLParameters();
    }

    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }

    public Optional<Executor> executor() {
      return Optional.empty();
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
      throw new UnsupportedOperationException();
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> responseBodyHandler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException();
    }
  }

  private record SimulatedResponse(String body) implements HttpResponse<String> {
    public int statusCode() {
      return 200;
    }

    public HttpRequest request() {
      return null;
    }

    public Optional<HttpResponse<String>> previousResponse() {
      return Optional.empty();
    }

    public HttpHeaders headers() {
      return HttpHeaders.of(Map.of(), (name, value) -> true);
    }

    public Optional<javax.net.ssl.SSLSession> sslSession() {
      return Optional.empty();
    }

    public URI uri() {
      return URI.create("http://localhost");
    }

    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }
}
