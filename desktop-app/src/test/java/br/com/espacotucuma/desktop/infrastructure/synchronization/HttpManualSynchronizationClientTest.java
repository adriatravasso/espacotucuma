package br.com.espacotucuma.desktop.infrastructure.synchronization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationException;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.outbox.ServerConfirmation;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.synchronization.DeviceCredentialStore;
import br.com.espacotucuma.desktop.application.port.synchronization.RemoteChangeRepository;
import br.com.espacotucuma.desktop.application.port.synchronization.SynchronizationProgress;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteAuditRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteOutboxRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteProtectionStatusRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteRemoteChangeRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteUnitOfWork;
import br.com.espacotucuma.sync.contract.ChangesResponse;
import br.com.espacotucuma.sync.contract.OperationResult;
import br.com.espacotucuma.sync.contract.OperationsResponse;
import br.com.espacotucuma.sync.contract.OperationsStatusResponse;
import br.com.espacotucuma.sync.contract.SyncStatusResponse;
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
import java.nio.file.Path;
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
import org.junit.jupiter.api.io.TempDir;

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
            "/sync/status",
            "/sync/operations/status",
            "/sync/operations",
            "/sync/operations/status",
            "/sync/operations",
            "/sync/changes",
            "/sync/backup"),
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
    assertEquals(
        List.of(
            "/sync/devices/token",
            "/sync/status",
            "/sync/changes",
            "/sync/changes",
            "/sync/backup"),
        http.paths);
  }

  @Test
  void explicitlyRequestedSyncReportsItsStagesAndOnlyAcceptsValidatedBackup() throws Exception {
    UUID backupId = UUID.fromString("70000000-0000-0000-0000-000000000001");
    String validated =
        "{\"contractVersion\":1,\"backupId\":\""
            + backupId
            + "\",\"checkpoint\":0,\"status\":\"VALIDADO\","
            + "\"createdAt\":\"2026-11-01T12:00:00Z\"}";
    SimulatedHttpClient http =
        new SimulatedHttpClient(
            response("{\"accessToken\":\"token\"}"),
            response(changes(0, List.of())),
            response(validated));
    List<SynchronizationProgress> progress = new ArrayList<>();

    client(new MemoryOutbox(List.of()), http).synchronizeNow(progress::add);

    assertEquals(
        List.of(
            SynchronizationProgress.State.VERIFYING_ACCESS,
            SynchronizationProgress.State.CHECKING_CHECKPOINT,
            SynchronizationProgress.State.RECEIVING,
            SynchronizationProgress.State.RECONCILING,
            SynchronizationProgress.State.REQUESTING_BACKUP,
            SynchronizationProgress.State.BACKUP_VALIDATED),
        progress.stream().map(SynchronizationProgress::state).toList());
    assertEquals(backupId, progress.getLast().backupId());
    assertTrue(progress.stream().noneMatch(item -> item.detail().contains("token")));
    assertEquals(
        List.of("/sync/devices/token", "/sync/status", "/sync/changes", "/sync/backup"),
        http.paths);
  }

  @Test
  void backupUnavailableLeavesSynchronizationRecordedAsPendingBackup() throws Exception {
    SimulatedHttpClient http =
        new SimulatedHttpClient(
            response("{\"accessToken\":\"token\"}"), response(changes(0, List.of())));
    List<SynchronizationProgress> progress = new ArrayList<>();

    client(new MemoryOutbox(List.of()), http).synchronizeNow(progress::add);

    assertEquals(SynchronizationProgress.State.SYNCHRONIZED_NO_BACKUP, progress.getLast().state());
    assertEquals(
        List.of("/sync/devices/token", "/sync/status", "/sync/changes", "/sync/backup"),
        http.paths);
  }

  @Test
  void unresolvedConflictSkipsBackupRequestAndReportsPendingState() throws Exception {
    MemoryRemoteChanges changes = new MemoryRemoteChanges();
    changes.unresolvedConflicts = 1;
    SimulatedHttpClient http =
        new SimulatedHttpClient(
            response("{\"accessToken\":\"token\"}"), response(changes(0, List.of())));
    List<SynchronizationProgress> progress = new ArrayList<>();

    client(new MemoryOutbox(List.of()), changes, http).synchronizeNow(progress::add);

    assertEquals(SynchronizationProgress.State.PENDING, progress.getLast().state());
    assertEquals(1, progress.getLast().conflicts());
    assertTrue(http.paths.stream().noneMatch("/sync/backup"::equals));
  }

  @Test
  void cancellationBetweenBatchesKeepsOnlyUnconfirmedOperationsPending() throws Exception {
    MemoryOutbox outbox =
        new MemoryOutbox(
            java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(HttpManualSynchronizationClientTest::operation)
                .toList());
    outbox.interruptAfterTwentyConfirmations = true;
    List<OperationResult> accepted =
        outbox.pending.subList(0, 20).stream().map(operation -> accepted(operation.id())).toList();
    SimulatedHttpClient http =
        new SimulatedHttpClient(
            response("{\"accessToken\":\"token\"}"),
            response(status(List.of())),
            response(operations(accepted)));
    List<SynchronizationProgress> progress = new ArrayList<>();

    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> client(outbox, http).synchronizeNow(progress::add));

    assertEquals(20, outbox.confirmations.size());
    assertEquals(1, outbox.pending.size());
    assertEquals(SynchronizationProgress.State.CANCELLED, progress.getLast().state());
    assertEquals(
        List.of(
            "/sync/devices/token", "/sync/status", "/sync/operations/status", "/sync/operations"),
        http.paths);
  }

  @Test
  void unauthorizedSessionIsRejectedBeforeAnyRemoteRequest() {
    SimulatedHttpClient http = new SimulatedHttpClient();
    AuthorizationService authorization = new AuthorizationService(new SessionContext());
    HttpManualSynchronizationClient client =
        new HttpManualSynchronizationClient(
            URI.create("http://localhost"),
            DEVICE_ID,
            emptyCredentials(),
            new MemoryOutbox(List.of()),
            new MemoryRemoteChanges(),
            directUnitOfWork(),
            http,
            JSON,
            Duration.ofMillis(100),
            authorization,
            null,
            null,
            DEVICE_ID);
    List<SynchronizationProgress> progress = new ArrayList<>();

    assertThrows(AuthorizationException.class, () -> client.synchronizeNow(progress::add));

    assertEquals(SynchronizationProgress.State.FAILED, progress.getLast().state());
    assertTrue(http.paths.isEmpty());
  }

  @Test
  void unreachableServiceReportsNoConnectionAndLeavesOutboxPending() {
    PendingOperation pending = operation(1);
    MemoryOutbox outbox = new MemoryOutbox(List.of(pending));
    SimulatedHttpClient http = new SimulatedHttpClient(new IOException("offline"));
    List<SynchronizationProgress> progress = new ArrayList<>();

    assertThrows(
        IllegalStateException.class, () -> client(outbox, http).synchronizeNow(progress::add));

    assertEquals(SynchronizationProgress.State.NO_CONNECTION, progress.getLast().state());
    assertEquals(List.of(pending.id()), outbox.pending.stream().map(PendingOperation::id).toList());
    assertTrue(http.paths.contains("/sync/devices/token"));
  }

  @Test
  void authorizedSyncRecordsDetailedAuditAndNeverPromotesUnavailableBackup(@TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database = LocalDatabaseSession.open(temp.resolve("sync.sqlite"))) {
      SqliteUnitOfWork unit = new SqliteUnitOfWork(database.connection());
      UUID userId = UUID.fromString("10000000-0000-0000-0000-000000000001");
      try (var insert =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO usuario (id, nome, login, senha_hash, perfil, ativo, criado_em_utc, "
                      + "atualizado_em_utc) VALUES (?, ?, ?, ?, 'ADMINISTRADOR', 1, ?, ?)")) {
        insert.setString(1, userId.toString());
        insert.setString(2, "Admin Sintético");
        insert.setString(3, "admin.sync");
        insert.setString(4, "synthetic-password-hash-long-enough");
        insert.setString(5, "2026-11-01T12:00:00Z");
        insert.setString(6, "2026-11-01T12:00:00Z");
        insert.executeUpdate();
      }
      SessionContext sessions = new SessionContext();
      sessions.establish(
          new AuthenticatedUser(userId, "Admin Sintético", "admin.sync", UserRole.ADMINISTRADOR));
      AuthorizationService authorization = new AuthorizationService(sessions);
      SqliteAuditRepository audit = new SqliteAuditRepository(database.connection(), unit);
      SqliteProtectionStatusRepository protection =
          new SqliteProtectionStatusRepository(database.connection(), unit);
      SqliteOutboxRepository outbox = new SqliteOutboxRepository(database.connection(), unit);
      SqliteRemoteChangeRepository remoteChanges =
          new SqliteRemoteChangeRepository(database.connection(), unit, JSON, DEVICE_ID);
      SimulatedHttpClient http =
          new SimulatedHttpClient(
              response("{\"accessToken\":\"token\"}"), response(changes(0, List.of())));
      HttpManualSynchronizationClient client =
          new HttpManualSynchronizationClient(
              URI.create("http://localhost"),
              DEVICE_ID,
              emptyCredentials(),
              outbox,
              remoteChanges,
              unit,
              http,
              JSON,
              Duration.ofMillis(100),
              authorization,
              audit,
              protection,
              DEVICE_ID);
      List<SynchronizationProgress> progress = new ArrayList<>();

      client.synchronizeNow(progress::add);

      var status = protection.load("Admin Sintético", database.databaseFile());
      assertEquals(1, status.synchronizationGeneration());
      assertEquals(
          SynchronizationProgress.State.SYNCHRONIZED_NO_BACKUP, progress.getLast().state());
      assertTrue(progress.getLast().detail().contains("HTTP 501"));
      assertTrue(!status.backupCompletedForCurrentState());
      try (var query = database.connection().createStatement();
          var result =
              query.executeQuery(
                  "SELECT acao, detalhes_json FROM evento_auditoria WHERE entidade = 'SINCRONIZACAO'")) {
        assertTrue(result.next());
        assertEquals("SINCRONIZACAO_SEM_BACKUP", result.getString("acao"));
        assertTrue(result.getString("detalhes_json").contains("SYNCHRONIZED_NO_BACKUP"));
        assertTrue(!result.getString("detalhes_json").contains("token"));
        assertTrue(!result.next());
      }
    }
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

  @Test
  void backupHistoryQueryAuthenticatesAndDoesNotRequestDownload() {
    UUID backupId = UUID.fromString("00000000-0000-0000-0000-000000000047");
    SimulatedHttpClient http =
        new SimulatedHttpClient(
            response("{\"accessToken\":\"token\"}"),
            response(
                "{\"contractVersion\":1,\"backups\":[{\"contractVersion\":1,"
                    + "\"backupId\":\""
                    + backupId
                    + "\",\"checkpoint\":17,\"status\":\"VALIDADO\","
                    + "\"createdAt\":\"2026-11-01T12:00:00Z\"}]}"));

    var backups = client(new MemoryOutbox(List.of()), http).listAvailableBackups();

    assertEquals(1, backups.size());
    assertEquals(backupId, backups.getFirst().backupId());
    assertEquals(17, backups.getFirst().checkpoint());
    assertEquals(List.of("/sync/devices/token", "/sync/backups"), http.paths);
    assertTrue(http.paths.stream().noneMatch(path -> path.contains("restore-download")));
  }

  @Test
  void backupHistoryIsHiddenAndRejectedForUnauthorizedLocalRole() {
    SessionContext sessions = new SessionContext();
    sessions.establish(
        new AuthenticatedUser(UUID.randomUUID(), "Recepção", "recepcao", UserRole.RECEPCAO));
    AuthorizationService authorization = new AuthorizationService(sessions);
    SimulatedHttpClient http = new SimulatedHttpClient();
    HttpManualSynchronizationClient client =
        new HttpManualSynchronizationClient(
            URI.create("http://localhost"),
            DEVICE_ID,
            emptyCredentials(),
            new MemoryOutbox(List.of()),
            new MemoryRemoteChanges(),
            directUnitOfWork(),
            http,
            JSON,
            Duration.ofMillis(100),
            authorization,
            null,
            null,
            DEVICE_ID);

    assertFalse(client.canListBackups());
    assertThrows(AuthorizationException.class, client::listAvailableBackups);
    assertTrue(http.paths.isEmpty());
  }

  private static HttpManualSynchronizationClient client(
      MemoryOutbox outbox, SimulatedHttpClient http) {
    return client(outbox, new MemoryRemoteChanges(), http);
  }

  private static DeviceCredentialStore emptyCredentials() {
    return new DeviceCredentialStore() {
      public void save(UUID deviceId, String credential) {}

      public Optional<String> find(UUID deviceId) {
        return Optional.of("credential");
      }

      public void delete(UUID deviceId) {}
    };
  }

  private static UnitOfWork directUnitOfWork() {
    return new UnitOfWork() {
      public <T> T execute(java.util.function.Supplier<T> operation) {
        return operation.get();
      }
    };
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
        UUID.fromString("00000000-0000-0000-0000-%012d".formatted(index)),
        "cliente",
        UUID.fromString("10000000-0000-0000-0000-%012d".formatted(index)),
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
    private int unresolvedConflicts;
    private final List<ChangesResponse> applied = new ArrayList<>();

    public long checkpoint() {
      return checkpoint;
    }

    public void applyPage(long expectedCheckpoint, ChangesResponse page) {
      if (expectedCheckpoint != checkpoint) throw new IllegalStateException("stale cursor");
      applied.add(page);
      checkpoint = page.checkpoint();
    }

    @Override
    public int unresolvedConflictCount() {
      return unresolvedConflicts;
    }
  }

  private static HttpResponse<String> response(String body) {
    return new SimulatedResponse(body, 200);
  }

  private static HttpResponse<String> response(int statusCode, String body) {
    return new SimulatedResponse(body, statusCode);
  }

  private static final class MemoryOutbox implements OutboxRepository {
    private final List<PendingOperation> pending;
    private final List<UUID> confirmations = new ArrayList<>();
    private final Map<UUID, String> originalPayloads = new java.util.HashMap<>();
    private final Map<UUID, String> originalKeys = new java.util.HashMap<>();
    private boolean interruptAfterTwentyConfirmations;

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
        if (interruptAfterTwentyConfirmations && confirmations.size() == 20) {
          Thread.currentThread().interrupt();
        }
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
      if ("/sync/status".equals(request.uri().getPath())) {
        return (HttpResponse<T>)
            response(
                JSON.writeValueAsString(
                    new SyncStatusResponse(1, UUID.randomUUID(), DEVICE_ID, "AVAILABLE", 0)));
      }
      if ("/sync/backup".equals(request.uri().getPath()) && responses.isEmpty()) {
        return (HttpResponse<T>) response(501, "{\"code\":\"NOT_IMPLEMENTED\"}");
      }
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

  private record SimulatedResponse(String body, int statusCode) implements HttpResponse<String> {

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
