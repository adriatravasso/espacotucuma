package br.com.espacotucuma.desktop.infrastructure.synchronization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.outbox.ServerConfirmation;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.synchronization.DeviceCredentialStore;
import br.com.espacotucuma.desktop.application.port.synchronization.RemoteChangeRepository;
import br.com.espacotucuma.desktop.application.port.synchronization.SynchronizationProgress;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import br.com.espacotucuma.sync.SyncE2eTestConfiguration;
import br.com.espacotucuma.sync.SyncServiceApplication;
import br.com.espacotucuma.sync.contract.ChangesResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Desktop sync client against the real Spring API and disposable PostgreSQL 16. */
@SpringBootTest(
    classes = SyncServiceApplication.class,
    properties = {"tucuma.security.sync-secret=e2e-test-secret-with-more-than-32-characters"})
@AutoConfigureMockMvc
@Import(SyncE2eTestConfiguration.class)
@Testcontainers
class ManualSynchronizationEndToEndTest {
  private static final String DEVICE_CREDENTIAL = "synthetic-device-credential";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configurePostgres(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private MockMvc mockMvc;
  @Autowired private SyncE2eTestConfiguration.InMemoryBackupStorage backupStorage;

  @BeforeEach
  void clearRemoteFixture() {
    jdbc.update("DELETE FROM autorizacao_download_backup");
    jdbc.update("DELETE FROM token_acesso_dispositivo");
    jdbc.update("DELETE FROM entidade_remota_sincronizada");
    jdbc.update("DELETE FROM estado_reconciliado");
    jdbc.update("DELETE FROM operacao_recebida");
    jdbc.update("DELETE FROM checkpoint_sincronizacao");
    jdbc.update("DELETE FROM backup_remoto");
    jdbc.update("DELETE FROM dispositivo_autorizado");
    jdbc.update("DELETE FROM estabelecimento_remoto");
    backupStorage.clear();
  }

  @Test
  void lostCommitResponseAndFailedSnapshotRetryDoNotDuplicateFinancialOrStockEffects()
      throws Exception {
    UUID establishmentId = UUID.randomUUID();
    UUID deviceId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();
    UUID stockMovementId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO estabelecimento_remoto (id, nome) VALUES (?, 'Estabelecimento sintético E2E')",
        establishmentId);
    jdbc.update(
        "INSERT INTO dispositivo_autorizado (id, estabelecimento_id, nome, estado, credencial_hash) "
            + "VALUES (?, ?, 'Estação E2E', 'ATIVO', ?)",
        deviceId,
        establishmentId,
        SyncE2eTestConfiguration.hashCredential(DEVICE_CREDENTIAL));

    PendingOperation payment =
        operation(
            paymentId,
            "PAGAMENTO",
            paymentId,
            "payment-e2e-1",
            "{\"amountCents\":8500,\"status\":\"CONFIRMED\"}");
    PendingOperation stockMovement =
        operation(
            stockMovementId,
            "MOVIMENTO_ESTOQUE",
            stockMovementId,
            "stock-e2e-1",
            "{\"quantityMilli\":-250,\"productId\":\"synthetic-product\"}");
    MemoryOutbox outbox = new MemoryOutbox(List.of(payment, stockMovement));
    MemoryRemoteChanges remoteChanges = new MemoryRemoteChanges();
    DeviceCredentialStore credentials =
        new DeviceCredentialStore() {
          @Override
          public void save(UUID ignored, String value) {}

          @Override
          public Optional<String> find(UUID requestedDevice) {
            return deviceId.equals(requestedDevice)
                ? Optional.of(DEVICE_CREDENTIAL)
                : Optional.empty();
          }

          @Override
          public void delete(UUID ignored) {}
        };
    MockMvcHttpClient http = new MockMvcHttpClient(mockMvc);
    backupStorage.failNextUpload();
    HttpManualSynchronizationClient desktopClient =
        new HttpManualSynchronizationClient(
            URI.create("http://localhost/"),
            deviceId,
            credentials,
            outbox,
            remoteChanges,
            new DirectUnitOfWork(),
            http,
            new ObjectMapper(),
            Duration.ofSeconds(5));

    List<SynchronizationProgress> firstProgress = new ArrayList<>();
    desktopClient.synchronizeNow(firstProgress::add);

    assertTrue(http.droppedOperationResponse());
    assertEquals(1, http.operationPostCount());
    assertTrue(outbox.findPending(10).isEmpty());
    assertEquals(2, outbox.confirmations.size());
    assertEquals(
        SynchronizationProgress.State.SYNCHRONIZED_NO_BACKUP, firstProgress.getLast().state());
    assertFalse(
        firstProgress.stream()
            .anyMatch(item -> item.state() == SynchronizationProgress.State.BACKUP_VALIDATED));
    assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM operacao_recebida", Integer.class));
    assertEquals(
        2, jdbc.queryForObject("SELECT COUNT(*) FROM entidade_remota_sincronizada", Integer.class));
    assertEquals(
        8500L,
        jdbc.queryForObject(
            "SELECT (payload->>'amountCents')::bigint FROM entidade_remota_sincronizada "
                + "WHERE estabelecimento_id = ? AND entidade = 'PAGAMENTO' AND entidade_id = ?",
            Long.class,
            establishmentId,
            paymentId));
    assertEquals(
        -250L,
        jdbc.queryForObject(
            "SELECT (payload->>'quantityMilli')::bigint FROM entidade_remota_sincronizada "
                + "WHERE estabelecimento_id = ? AND entidade = 'MOVIMENTO_ESTOQUE' AND entidade_id = ?",
            Long.class,
            establishmentId,
            stockMovementId));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM backup_remoto WHERE estado = 'VALIDADO'", Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM backup_remoto WHERE estado = 'PENDENTE'", Integer.class));

    List<SynchronizationProgress> retryProgress = new ArrayList<>();
    desktopClient.synchronizeNow(retryProgress::add);

    assertEquals(SynchronizationProgress.State.BACKUP_VALIDATED, retryProgress.getLast().state());
    assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM operacao_recebida", Integer.class));
    assertEquals(
        2, jdbc.queryForObject("SELECT COUNT(*) FROM entidade_remota_sincronizada", Integer.class));
    assertEquals(
        8500L,
        jdbc.queryForObject(
            "SELECT (payload->>'amountCents')::bigint FROM entidade_remota_sincronizada "
                + "WHERE estabelecimento_id = ? AND entidade = 'PAGAMENTO' AND entidade_id = ?",
            Long.class,
            establishmentId,
            paymentId));
    assertEquals(
        -250L,
        jdbc.queryForObject(
            "SELECT (payload->>'quantityMilli')::bigint FROM entidade_remota_sincronizada "
                + "WHERE estabelecimento_id = ? AND entidade = 'MOVIMENTO_ESTOQUE' AND entidade_id = ?",
            Long.class,
            establishmentId,
            stockMovementId));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM backup_remoto WHERE estado = 'VALIDADO'", Integer.class));
    assertEquals(2, remoteChanges.checkpoint);
  }

  private static PendingOperation operation(
      UUID operationId, String entity, UUID entityId, String key, String payload) {
    return new PendingOperation(
        operationId,
        entity,
        entityId,
        "UPSERT",
        key,
        payload,
        1,
        0,
        UUID.randomUUID(),
        Instant.parse("2026-11-01T12:00:00Z"),
        0);
  }

  private static final class MemoryOutbox implements OutboxRepository {
    private final List<PendingOperation> pending;
    private final List<UUID> confirmations = new ArrayList<>();

    private MemoryOutbox(List<PendingOperation> operations) {
      pending = new ArrayList<>(operations);
    }

    @Override
    public void enqueue(PendingOperation operation) {
      pending.add(operation);
    }

    @Override
    public long expectedVersionForNext(String entity, UUID entityId) {
      return 0;
    }

    @Override
    public List<PendingOperation> findPending(int limit) {
      return pending.stream().limit(limit).toList();
    }

    @Override
    public boolean recordAttempt(UUID operationId) {
      return pending.stream().anyMatch(operation -> operation.id().equals(operationId));
    }

    @Override
    public boolean markSynchronized(ServerConfirmation confirmation) {
      confirmations.add(confirmation.operationId());
      return pending.removeIf(operation -> operation.id().equals(confirmation.operationId()));
    }
  }

  private static final class MemoryRemoteChanges implements RemoteChangeRepository {
    private long checkpoint;

    @Override
    public long checkpoint() {
      return checkpoint;
    }

    @Override
    public void applyPage(long expectedCheckpoint, ChangesResponse page) {
      if (checkpoint != expectedCheckpoint || page.checkpoint() < checkpoint) {
        throw new IllegalArgumentException("Checkpoint local não pode retroceder.");
      }
      checkpoint = page.checkpoint();
    }
  }

  private static final class DirectUnitOfWork implements UnitOfWork {
    @Override
    public <T> T execute(Supplier<T> operation) {
      return operation.get();
    }
  }

  private static final class MockMvcHttpClient extends HttpClient {
    private final MockMvc mockMvc;
    private boolean droppedOperationResponse;
    private int operationPostCount;

    private MockMvcHttpClient(MockMvc mockMvc) {
      this.mockMvc = mockMvc;
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        throws IOException, InterruptedException {
      byte[] requestBody = body(request);
      MockHttpServletRequestBuilder builder = request(request.method(), request.uri());
      request
          .headers()
          .map()
          .forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
      if (requestBody.length > 0) builder.content(requestBody);
      MvcResult result;
      try {
        result = mockMvc.perform(builder).andReturn();
      } catch (Exception failure) {
        throw new IOException("MockMvc did not complete the real sync API request.", failure);
      }
      String path = request.uri().getPath();
      if ("POST".equals(request.method()) && "/sync/operations".equals(path)) {
        operationPostCount++;
        if (!droppedOperationResponse) {
          droppedOperationResponse = true;
          throw new IOException("Simulated lost response after the API committed the operation.");
        }
      }
      var servletResponse = result.getResponse();
      Map<String, List<String>> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      for (String name : servletResponse.getHeaderNames()) {
        headers.put(name, new ArrayList<>(servletResponse.getHeaders(name)));
      }
      HttpHeaders responseHeaders = HttpHeaders.of(headers, (name, value) -> true);
      HttpResponse.ResponseInfo info =
          new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() {
              return servletResponse.getStatus();
            }

            @Override
            public HttpHeaders headers() {
              return responseHeaders;
            }

            @Override
            public HttpClient.Version version() {
              return HttpClient.Version.HTTP_1_1;
            }
          };
      HttpResponse.BodySubscriber<T> subscriber = handler.apply(info);
      subscriber.onSubscribe(
          new Flow.Subscription() {
            @Override
            public void request(long count) {}

            @Override
            public void cancel() {}
          });
      byte[] responseBody = servletResponse.getContentAsByteArray();
      if (responseBody.length > 0) subscriber.onNext(List.of(ByteBuffer.wrap(responseBody)));
      subscriber.onComplete();
      T decoded;
      try {
        decoded = subscriber.getBody().toCompletableFuture().get();
      } catch (java.util.concurrent.ExecutionException failure) {
        throw new IOException("Could not decode the API response body.", failure.getCause());
      }
      return new StubHttpResponse<>(request, decoded, responseHeaders, servletResponse.getStatus());
    }

    private static byte[] body(HttpRequest request) throws IOException, InterruptedException {
      if (request.bodyPublisher().isEmpty()) return new byte[0];
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      request
          .bodyPublisher()
          .orElseThrow()
          .subscribe(
              new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                  subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(ByteBuffer item) {
                  byte[] bytes = new byte[item.remaining()];
                  item.get(bytes);
                  output.writeBytes(bytes);
                }

                @Override
                public void onError(Throwable failure) {}

                @Override
                public void onComplete() {}
              });
      return output.toByteArray();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      return CompletableFuture.supplyAsync(
          () -> {
            try {
              return send(request, handler);
            } catch (Exception failure) {
              throw new java.util.concurrent.CompletionException(failure);
            }
          });
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      return sendAsync(request, handler);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.of(Duration.ofSeconds(5));
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      return null;
    }

    @Override
    public SSLParameters sslParameters() {
      return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<java.util.concurrent.Executor> executor() {
      return Optional.empty();
    }

    private boolean droppedOperationResponse() {
      return droppedOperationResponse;
    }

    private int operationPostCount() {
      return operationPostCount;
    }
  }

  private record StubHttpResponse<T>(
      HttpRequest request, T body, HttpHeaders headers, int statusCode) implements HttpResponse<T> {
    @Override
    public Optional<HttpResponse<T>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public Optional<javax.net.ssl.SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return request.uri();
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }
}
