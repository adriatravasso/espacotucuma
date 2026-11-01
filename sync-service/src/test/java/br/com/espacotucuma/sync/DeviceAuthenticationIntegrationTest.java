package br.com.espacotucuma.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.espacotucuma.sync.contract.OperationResult;
import br.com.espacotucuma.sync.contract.OperationSubmission;
import br.com.espacotucuma.sync.contract.OperationsRequest;
import br.com.espacotucuma.sync.contract.OperationsResponse;
import br.com.espacotucuma.sync.contract.SyncContract;
import br.com.espacotucuma.sync.contract.SyncErrorCode;
import br.com.espacotucuma.sync.contract.SyncStatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    properties = "tucuma.security.sync-secret=bootstrap-test-secret-with-more-than-32-chars")
@AutoConfigureMockMvc
@Testcontainers
class DeviceAuthenticationIntegrationTest {
  private static final String ADMIN_SECRET = "bootstrap-test-secret-with-more-than-32-chars";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configurePostgres(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void clearRemoteIdentity() {
    jdbc.update("DELETE FROM entidade_remota_sincronizada");
    jdbc.update("DELETE FROM token_acesso_dispositivo");
    jdbc.update("DELETE FROM codigo_pareamento");
    jdbc.update("DELETE FROM operacao_recebida");
    jdbc.update("DELETE FROM checkpoint_sincronizacao");
    jdbc.update("DELETE FROM estado_reconciliado");
    jdbc.update("DELETE FROM backup_remoto");
    jdbc.update("DELETE FROM dispositivo_autorizado");
    jdbc.update("DELETE FROM estabelecimento_remoto");
  }

  @Test
  void pairsStationOnceAndIssuesShortLivedAccessToken() throws Exception {
    Enrollment enrollment = enrollNewEstablishment("Salão de teste");
    Pairing pairing = pair(enrollment.pairingCode(), "Estação principal");
    AccessToken token = issueAccessToken(pairing);

    assertNotNull(token.expiresAt());
    assertEquals(
        DeviceAuthenticationController.hash(enrollment.pairingCode()),
        jdbc.queryForObject("SELECT token_hash FROM codigo_pareamento", String.class));
    assertEquals(
        DeviceAuthenticationController.hash(pairing.deviceCredential()),
        jdbc.queryForObject(
            "SELECT credencial_hash FROM dispositivo_autorizado WHERE id = ?",
            String.class,
            pairing.deviceId()));
    assertEquals(
        DeviceAuthenticationController.hash(token.accessToken()),
        jdbc.queryForObject("SELECT token_hash FROM token_acesso_dispositivo", String.class));
    String statusResponse =
        mockMvc
            .perform(
                get("/sync/status")
                    .header("Authorization", "Bearer " + token.accessToken())
                    .header(SyncContract.VERSION_HEADER, SyncContract.VERSION))
            .andExpect(status().isOk())
            .andExpect(header().string(SyncContract.VERSION_HEADER, "1"))
            .andExpect(jsonPath("$.contractVersion").value(1))
            .andExpect(jsonPath("$.establishmentId").value(enrollment.establishmentId().toString()))
            .andExpect(jsonPath("$.deviceId").value(pairing.deviceId().toString()))
            .andExpect(jsonPath("$.checkpoint").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    SyncStatusResponse status = objectMapper.readValue(statusResponse, SyncStatusResponse.class);
    assertEquals(SyncContract.VERSION, status.contractVersion());
    assertEquals(enrollment.establishmentId(), status.establishmentId());
    assertEquals(pairing.deviceId(), status.deviceId());

    mockMvc
        .perform(
            post("/sync/devices/pair")
                .contentType("application/json")
                .content(
                    objectMapper.writeValueAsString(
                        new DeviceAuthenticationController.PairingRequest(
                            enrollment.pairingCode(), "Duplicada"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsExpiredTokenAndUnauthorizedRequest() throws Exception {
    mockMvc.perform(get("/sync/status")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/sync/status").header("Authorization", "Bearer invalido"))
        .andExpect(status().isUnauthorized());

    AccessToken token =
        issueAccessToken(pair(enrollNewEstablishment("Salão de teste").pairingCode(), "Principal"));
    jdbc.update(
        "UPDATE token_acesso_dispositivo SET criado_em = ?, expira_em = ? WHERE token_hash = ?",
        Timestamp.from(Instant.now().minusSeconds(2)),
        Timestamp.from(Instant.now().minusSeconds(1)),
        DeviceAuthenticationController.hash(token.accessToken()));

    mockMvc
        .perform(get("/sync/status").header("Authorization", "Bearer " + token.accessToken()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void versionedContractRejectsUnsupportedVersionsUnknownFieldsAndMalformedPayloads()
      throws Exception {
    AccessToken token =
        issueAccessToken(pair(enrollNewEstablishment("Salão de teste").pairingCode(), "Principal"));
    String authorization = "Bearer " + token.accessToken();

    mockMvc
        .perform(
            get("/sync/status")
                .header("Authorization", authorization)
                .header(SyncContract.VERSION_HEADER, "99"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.contractVersion").value(1))
        .andExpect(jsonPath("$.code").value("CONTRACT_VERSION_UNSUPPORTED"))
        .andExpect(jsonPath("$.retryable").value(false));

    mockMvc
        .perform(get("/sync/status").header("Authorization", authorization))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    String validOperation =
        "{\"operationId\":\""
            + UUID.randomUUID()
            + "\",\"idempotencyKey\":\"idem-1\",\"entityType\":\"cliente\","
            + "\"entityId\":\""
            + UUID.randomUUID()
            + "\",\"operationType\":\"upsert\",\"entityVersion\":0,"
            + "\"payloadVersion\":1,\"payload\":{\"name\":\"Dado sintético\"}}";
    String validBatch = "{\"contractVersion\":1,\"operations\":[" + validOperation + "]}";

    MvcResult accepted =
        mockMvc
            .perform(
                post("/sync/operations")
                    .header("Authorization", authorization)
                    .header(SyncContract.VERSION_HEADER, "1")
                    .contentType("application/json")
                    .content(validBatch))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contractVersion").value(1))
            .andExpect(jsonPath("$.results[0].outcome").value("ACCEPTED"))
            .andExpect(jsonPath("$.results[0].remoteVersion").value(1))
            .andReturn();
    OperationsResponse acceptedResponse =
        objectMapper.readValue(
            accepted.getResponse().getContentAsString(), OperationsResponse.class);
    assertEquals(1, acceptedResponse.results().size());

    mockMvc
        .perform(
            get("/sync/operations/status")
                .param("ids", acceptedResponse.results().getFirst().operationId().toString())
                .param("ids", UUID.randomUUID().toString())
                .header("Authorization", authorization)
                .header(SyncContract.VERSION_HEADER, "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contractVersion").value(1))
        .andExpect(jsonPath("$.results.length()").value(1))
        .andExpect(
            jsonPath("$.results[0].operationId")
                .value(acceptedResponse.results().getFirst().operationId().toString()))
        .andExpect(jsonPath("$.results[0].outcome").value("ACCEPTED"))
        .andExpect(jsonPath("$.results[0].remoteVersion").value(1));

    mockMvc
        .perform(
            post("/sync/operations")
                .header("Authorization", authorization)
                .header(SyncContract.VERSION_HEADER, "1")
                .contentType("application/json")
                .content(validBatch.replace("\"contractVersion\":1", "\"contractVersion\":2")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONTRACT_VERSION_UNSUPPORTED"));

    mockMvc
        .perform(
            post("/sync/operations")
                .header("Authorization", authorization)
                .header(SyncContract.VERSION_HEADER, "1")
                .contentType("application/json")
                .content(validBatch.substring(0, validBatch.length() - 1) + ",\"surprise\":true}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

    mockMvc
        .perform(
            post("/sync/operations")
                .header("Authorization", authorization)
                .header(SyncContract.VERSION_HEADER, "1")
                .contentType("application/json")
                .content("{not-json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
  }

  @Test
  void contractValidationAndTransportErrorsExposeRetrySemantics() {
    assertTrue(SyncErrorCode.CONNECTION_UNAVAILABLE.retryable());
    assertTrue(SyncErrorCode.REQUEST_TIMEOUT.retryable());
    assertTrue(SyncErrorCode.RETRY_LATER.retryable());
    assertFalse(SyncErrorCode.AUTHORIZATION_REQUIRED.retryable());
    assertFalse(SyncErrorCode.CONFLICT.retryable());
    assertFalse(SyncErrorCode.VALIDATION_FAILED.retryable());
  }

  @Test
  void repeatedOperationReturnsPersistedResultWithoutApplyingItsEffectTwice() throws Exception {
    Enrollment enrollment = enrollNewEstablishment("Salão idempotente");
    Pairing pairing = pair(enrollment.pairingCode(), "Principal");
    AccessToken token = issueAccessToken(pairing);
    UUID entityId = UUID.randomUUID();
    OperationSubmission operation = operation(UUID.randomUUID(), entityId, 0, "recebimento");
    OperationsRequest request = new OperationsRequest(1, java.util.List.of(operation));

    OperationsResponse first = submitOperations(token, request);
    OperationsResponse replay = submitOperations(token, request);

    assertEquals(first.results(), replay.results());
    assertEquals(OperationResult.Outcome.ACCEPTED, replay.results().getFirst().outcome());
    assertEquals(1, replay.results().getFirst().remoteVersion());
    assertEquals(first.checkpoint(), replay.checkpoint());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM operacao_recebida WHERE id = ?",
            Integer.class,
            operation.operationId()));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT versao FROM entidade_remota_sincronizada "
                + "WHERE estabelecimento_id = ? AND entidade = 'recebimento' AND entidade_id = ?",
            Integer.class,
            enrollment.establishmentId(),
            entityId));
  }

  @Test
  void staleEntityVersionIsPersistedAsConflictWithoutOverwritingRemoteState() throws Exception {
    Enrollment enrollment = enrollNewEstablishment("Salão com versão");
    Pairing pairing = pair(enrollment.pairingCode(), "Principal");
    AccessToken token = issueAccessToken(pairing);
    UUID entityId = UUID.randomUUID();
    submitOperations(
        token,
        new OperationsRequest(
            1, java.util.List.of(operation(UUID.randomUUID(), entityId, 0, "agendamento"))));
    OperationSubmission stale = operation(UUID.randomUUID(), entityId, 0, "agendamento");

    OperationsResponse response =
        submitOperations(token, new OperationsRequest(1, java.util.List.of(stale)));

    assertEquals(OperationResult.Outcome.CONFLICT, response.results().getFirst().outcome());
    assertEquals(SyncErrorCode.CONFLICT, response.results().getFirst().errorCode());
    assertEquals(1, response.results().getFirst().remoteVersion());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT versao FROM entidade_remota_sincronizada "
                + "WHERE estabelecimento_id = ? AND entidade = 'agendamento' AND entidade_id = ?",
            Integer.class,
            enrollment.establishmentId(),
            entityId));
  }

  @Test
  void databaseFailureRollsBackCurrentOperationAndRetryContinuesFromPersistedCheckpoint()
      throws Exception {
    Enrollment enrollment = enrollNewEstablishment("Salão transacional");
    Pairing pairing = pair(enrollment.pairingCode(), "Principal");
    AccessToken token = issueAccessToken(pairing);
    UUID firstEntityId = UUID.randomUUID();
    UUID failedEntityId = UUID.randomUUID();
    OperationSubmission first = operation(UUID.randomUUID(), firstEntityId, 0, "recebimento");
    OperationSubmission second = operation(UUID.randomUUID(), failedEntityId, 0, "estorno");
    OperationsRequest request = new OperationsRequest(1, java.util.List.of(first, second));

    jdbc.execute(
        "CREATE FUNCTION fail_sync_entity_for_test() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN IF NEW.entidade_id = '"
            + failedEntityId
            + "'::uuid THEN RAISE EXCEPTION 'simulated database failure'; END IF; "
            + "RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER fail_sync_entity_for_test BEFORE INSERT OR UPDATE "
            + "ON entidade_remota_sincronizada FOR EACH ROW "
            + "EXECUTE FUNCTION fail_sync_entity_for_test()");
    try {
      mockMvc
          .perform(
              post("/sync/operations")
                  .header("Authorization", "Bearer " + token.accessToken())
                  .header(SyncContract.VERSION_HEADER, "1")
                  .contentType("application/json")
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isInternalServerError())
          .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
          .andExpect(jsonPath("$.retryable").value(true));

      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM operacao_recebida WHERE id = ?",
              Integer.class,
              first.operationId()));
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM operacao_recebida WHERE id = ?",
              Integer.class,
              second.operationId()));
      long persistedCheckpoint =
          jdbc.queryForObject(
              "SELECT ultima_sequencia FROM checkpoint_sincronizacao WHERE estabelecimento_id = ? "
                  + "AND dispositivo_id = ?",
              Long.class,
              enrollment.establishmentId(),
              pairing.deviceId());
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM entidade_remota_sincronizada "
                  + "WHERE estabelecimento_id = ? AND entidade_id = ?",
              Integer.class,
              enrollment.establishmentId(),
              firstEntityId));

      jdbc.execute("DROP TRIGGER fail_sync_entity_for_test ON entidade_remota_sincronizada");
      jdbc.execute("DROP FUNCTION fail_sync_entity_for_test()");
      OperationsResponse retried = submitOperations(token, request);

      assertEquals(2, retried.results().size());
      assertEquals(OperationResult.Outcome.ACCEPTED, retried.results().get(0).outcome());
      assertEquals(OperationResult.Outcome.ACCEPTED, retried.results().get(1).outcome());
      assertEquals(1, retried.results().get(0).remoteVersion());
      assertEquals(1, retried.results().get(1).remoteVersion());
      assertTrue(retried.checkpoint() > persistedCheckpoint);
      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM operacao_recebida WHERE id IN (?, ?)",
              Integer.class,
              first.operationId(),
              second.operationId()));
    } finally {
      jdbc.execute(
          "DROP TRIGGER IF EXISTS fail_sync_entity_for_test ON entidade_remota_sincronizada");
      jdbc.execute("DROP FUNCTION IF EXISTS fail_sync_entity_for_test()");
    }
  }

  @Test
  void revokingTheStationImmediatelyInvalidatesItsAccessToken() throws Exception {
    Pairing pairing = pair(enrollNewEstablishment("Salão de teste").pairingCode(), "Principal");
    AccessToken token = issueAccessToken(pairing);

    mockMvc
        .perform(
            post("/sync/admin/devices/{deviceId}/revoke", pairing.deviceId())
                .header("Authorization", "Bearer " + ADMIN_SECRET))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(get("/sync/status").header("Authorization", "Bearer " + token.accessToken()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsASecondActiveStationAndDoesNotRevealProvisioningSecret() throws Exception {
    MvcResult invalidProvisioning =
        mockMvc
            .perform(
                post("/sync/admin/enrollments")
                    .header("Authorization", "Bearer credencial-incorreta")
                    .contentType("application/json")
                    .content("{\"establishmentName\":\"Salão de teste\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHORIZATION_REQUIRED"))
            .andReturn();
    assertFalse(invalidProvisioning.getResponse().getContentAsString().contains(ADMIN_SECRET));

    Enrollment enrollment = enrollNewEstablishment("Salão de teste");
    pair(enrollment.pairingCode(), "Principal");
    Enrollment secondEnrollment = enrollExisting(enrollment.establishmentId());
    mockMvc
        .perform(
            post("/sync/devices/pair")
                .contentType("application/json")
                .content(
                    objectMapper.writeValueAsString(
                        new DeviceAuthenticationController.PairingRequest(
                            secondEnrollment.pairingCode(), "Outra estação"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"));
  }

  private Enrollment enrollNewEstablishment(String name) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/sync/admin/enrollments")
                    .header("Authorization", "Bearer " + ADMIN_SECRET)
                    .contentType("application/json")
                    .content(
                        objectMapper.writeValueAsString(
                            new DeviceAuthenticationController.EnrollmentRequest(null, name))))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readValue(response, Enrollment.class);
  }

  private Enrollment enrollExisting(UUID establishmentId) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/sync/admin/enrollments")
                    .header("Authorization", "Bearer " + ADMIN_SECRET)
                    .contentType("application/json")
                    .content(
                        objectMapper.writeValueAsString(
                            new DeviceAuthenticationController.EnrollmentRequest(
                                establishmentId, null))))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readValue(response, Enrollment.class);
  }

  private Pairing pair(String pairingCode, String deviceName) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/sync/devices/pair")
                    .contentType("application/json")
                    .content(
                        objectMapper.writeValueAsString(
                            new DeviceAuthenticationController.PairingRequest(
                                pairingCode, deviceName))))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readValue(response, Pairing.class);
  }

  private AccessToken issueAccessToken(Pairing pairing) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/sync/devices/token")
                    .header("X-Tucuma-Device-Id", pairing.deviceId())
                    .header("Authorization", "Device " + pairing.deviceCredential()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readValue(response, AccessToken.class);
  }

  private OperationsResponse submitOperations(AccessToken token, OperationsRequest request)
      throws Exception {
    String response =
        mockMvc
            .perform(
                post("/sync/operations")
                    .header("Authorization", "Bearer " + token.accessToken())
                    .header(SyncContract.VERSION_HEADER, "1")
                    .contentType("application/json")
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readValue(response, OperationsResponse.class);
  }

  private static OperationSubmission operation(
      UUID operationId, UUID entityId, long expectedVersion, String entityType) {
    return new OperationSubmission(
        operationId,
        operationId.toString(),
        entityType,
        entityId,
        "UPSERT",
        expectedVersion,
        1,
        Map.of("recordId", entityId.toString(), "sample", true));
  }

  private record Enrollment(UUID establishmentId, String pairingCode, Instant expiresAt) {}

  private record Pairing(UUID establishmentId, UUID deviceId, String deviceCredential) {}

  private record AccessToken(String accessToken, Instant expiresAt) {}
}
