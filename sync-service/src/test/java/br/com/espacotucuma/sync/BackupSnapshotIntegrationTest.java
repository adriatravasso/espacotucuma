package br.com.espacotucuma.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.sync.contract.BackupRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    properties = "tucuma.security.sync-secret=backup-test-secret-with-more-than-32-chars")
@Import(BackupSnapshotIntegrationTest.SimulatedStorageConfiguration.class)
@Testcontainers
class BackupSnapshotIntegrationTest {
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
  @Autowired private BackupSnapshotService service;
  @Autowired private SimulatedBackupObjectStorage storage;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void clearDatabaseAndStorage() {
    jdbc.update("DELETE FROM entidade_remota_sincronizada");
    jdbc.update("DELETE FROM estado_reconciliado");
    jdbc.update("DELETE FROM operacao_recebida");
    jdbc.update("DELETE FROM checkpoint_sincronizacao");
    jdbc.update("DELETE FROM backup_remoto");
    jdbc.update("DELETE FROM dispositivo_autorizado");
    jdbc.update("DELETE FROM estabelecimento_remoto");
    storage.clear();
  }

  @Test
  void storesSnapshotWithExactCheckpointManifestAndVerifiableChecksum() throws Exception {
    Fixture fixture = seedRemoteState();

    var backup =
        service.create(fixture.establishmentId(), new BackupRequest(1, fixture.checkpoint()));

    assertEquals(fixture.checkpoint(), backup.checkpoint());
    assertEquals("VALIDADO", backup.status());
    assertEquals(
        "VALIDADO",
        jdbc.queryForObject(
            "SELECT estado FROM backup_remoto WHERE id = ?", String.class, backup.backupId()));
    String key =
        jdbc.queryForObject(
            "SELECT chave_armazenamento FROM backup_remoto WHERE id = ?",
            String.class,
            backup.backupId());
    byte[] bytes = storage.get(key);
    JsonNode snapshot = objectMapper.readTree(bytes);
    assertEquals(1, snapshot.get("formatVersion").asInt());
    assertEquals(4, snapshot.get("schemaVersion").asInt());
    assertEquals(fixture.checkpoint(), snapshot.get("checkpoint").asLong());
    assertEquals(1, snapshot.get("manifest").get("entityCount").asInt());
    assertEquals(1, snapshot.get("manifest").get("reconciliationCount").asInt());
    assertEquals(1, snapshot.get("entities").size());
    assertEquals(1, snapshot.get("reconciliations").size());
    assertTrue(snapshot.get("manifest").get("entitiesSha256").asText().matches("[0-9a-f]{64}"));
    assertTrue(
        snapshot.get("manifest").get("reconciliationsSha256").asText().matches("[0-9a-f]{64}"));

    String storedChecksum =
        jdbc.queryForObject(
            "SELECT checksum_sha256 FROM backup_remoto WHERE id = ?",
            String.class,
            backup.backupId());
    assertEquals(
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        storedChecksum.trim());
    assertTrue(service.verify(fixture.establishmentId(), backup.backupId()));

    storage.replace(key, "tampered".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertFalse(service.verify(fixture.establishmentId(), backup.backupId()));
  }

  @Test
  void leavesBackupPendingWhenUploadIsIncomplete() {
    Fixture fixture = seedRemoteState();
    storage.failNextUploadPartially();

    assertThrows(
        IllegalStateException.class,
        () ->
            service.create(fixture.establishmentId(), new BackupRequest(1, fixture.checkpoint())));

    assertEquals("PENDENTE", jdbc.queryForObject("SELECT estado FROM backup_remoto", String.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM backup_remoto WHERE estado = 'VALIDADO'", Integer.class));
  }

  @Test
  void refusesCheckpointThatDoesNotRepresentCurrentRemoteState() {
    Fixture fixture = seedRemoteState();

    assertThrows(
        IllegalArgumentException.class,
        () -> service.create(fixture.establishmentId(), new BackupRequest(1, 0)));

    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM backup_remoto", Integer.class));
  }

  private Fixture seedRemoteState() {
    UUID establishmentId = UUID.randomUUID();
    UUID deviceId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID entityId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO estabelecimento_remoto (id, nome) VALUES (?, 'Salão sintético')",
        establishmentId);
    jdbc.update(
        "INSERT INTO dispositivo_autorizado (id, estabelecimento_id, nome, estado, credencial_hash) "
            + "VALUES (?, ?, 'Estação de teste', 'ATIVO', ?)",
        deviceId,
        establishmentId,
        "a".repeat(64));
    Long checkpoint =
        jdbc.queryForObject(
            "INSERT INTO operacao_recebida (id, estabelecimento_id, dispositivo_id, chave_idempotencia, entidade, entidade_id, "
                + "tipo_operacao, payload, payload_versao, versao_esperada, resultado_estado, resultado) "
                + "VALUES (?, ?, ?, ?, 'CLIENTE', ?, 'UPSERT', '{\"nome\":\"Exemplo\"}'::jsonb, 1, 0, 'ACEITA', '{\"remoteVersion\":1}'::jsonb) "
                + "RETURNING sequencia",
            Long.class,
            operationId,
            establishmentId,
            deviceId,
            operationId.toString(),
            entityId);
    jdbc.update(
        "INSERT INTO entidade_remota_sincronizada (estabelecimento_id, entidade, entidade_id, versao, payload, ultima_operacao_id) "
            + "VALUES (?, 'CLIENTE', ?, 1, '{\"nome\":\"Exemplo\"}'::jsonb, ?)",
        establishmentId,
        entityId,
        operationId);
    jdbc.update(
        "INSERT INTO estado_reconciliado (id, estabelecimento_id, dispositivo_id, operacao_id, codigo, estado, detalhes) "
            + "VALUES (?, ?, ?, ?, 'CONFLITO_TESTE', 'ABERTO', '{\"origem\":\"teste\"}'::jsonb)",
        UUID.randomUUID(),
        establishmentId,
        deviceId,
        operationId);
    return new Fixture(establishmentId, checkpoint);
  }

  private record Fixture(UUID establishmentId, long checkpoint) {}

  @TestConfiguration
  static class SimulatedStorageConfiguration {
    @Bean
    @Primary
    SimulatedBackupObjectStorage simulatedBackupObjectStorage() {
      return new SimulatedBackupObjectStorage();
    }
  }

  static class SimulatedBackupObjectStorage implements BackupObjectStorage {
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private boolean failNextUpload;

    @Override
    public synchronized void putPrivate(String key, byte[] content) {
      if (failNextUpload) {
        failNextUpload = false;
        objects.put(key, Arrays.copyOf(content, Math.max(1, content.length / 2)));
        throw new IllegalStateException("Falha simulada durante upload parcial.");
      }
      objects.put(key, content.clone());
    }

    @Override
    public byte[] get(String key) {
      byte[] content = objects.get(key);
      if (content == null) throw new IllegalStateException("Objeto de backup ausente.");
      return content.clone();
    }

    synchronized void failNextUploadPartially() {
      failNextUpload = true;
    }

    void replace(String key, byte[] content) {
      objects.put(key, content.clone());
    }

    void clear() {
      objects.clear();
    }
  }
}
