package br.com.espacotucuma.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
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
    mockMvc
        .perform(get("/sync/status").header("Authorization", "Bearer " + token.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(true));

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
            .andExpect(jsonPath("$.code").value("SYNC_UNAUTHORIZED"))
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
        .andExpect(jsonPath("$.code").value("ACTIVE_STATION_ALREADY_EXISTS"));
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

  private record Enrollment(UUID establishmentId, String pairingCode, Instant expiresAt) {}

  private record Pairing(UUID establishmentId, UUID deviceId, String deviceCredential) {}

  private record AccessToken(String accessToken, Instant expiresAt) {}
}
