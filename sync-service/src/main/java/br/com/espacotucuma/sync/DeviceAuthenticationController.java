package br.com.espacotucuma.sync;

import br.com.espacotucuma.sync.contract.ApiErrorResponse;
import br.com.espacotucuma.sync.contract.SyncErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sync")
class DeviceAuthenticationController {
  private static final Duration PAIRING_LIFETIME = Duration.ofMinutes(30);
  private static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(15);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcTemplate jdbc;
  private final byte[] provisioningSecret;

  DeviceAuthenticationController(
      JdbcTemplate jdbc, @Value("${tucuma.security.sync-secret:}") String provisioningSecret) {
    this.jdbc = jdbc;
    this.provisioningSecret = provisioningSecret.getBytes(StandardCharsets.UTF_8);
  }

  @PostMapping("/admin/enrollments")
  @Transactional
  ResponseEntity<?> createEnrollment(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody EnrollmentRequest request) {
    if (!isProvisioningAdmin(authorization)) {
      return unauthorized();
    }
    if (request == null
        || (request.establishmentId() == null
            && (request.establishmentName() == null
                || request.establishmentName().trim().length() < 2
                || request.establishmentName().trim().length() > 160))) {
      return ResponseEntity.badRequest()
          .body(new ApiErrorResponse(SyncErrorCode.VALIDATION_FAILED));
    }

    UUID establishmentId = request.establishmentId();
    if (establishmentId == null) {
      establishmentId = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO estabelecimento_remoto (id, nome) VALUES (?, ?)",
          establishmentId,
          request.establishmentName().trim());
    } else if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM estabelecimento_remoto WHERE id = ?)",
            Boolean.class,
            establishmentId))) {
      return ResponseEntity.notFound().build();
    }

    jdbc.update(
        "DELETE FROM codigo_pareamento WHERE consumido_em IS NOT NULL OR expira_em <= now()");
    String pairingCode = newSecret();
    Instant expiresAt = Instant.now().plus(PAIRING_LIFETIME);
    jdbc.update(
        "INSERT INTO codigo_pareamento (token_hash, estabelecimento_id, expira_em) VALUES (?, ?, ?)",
        hash(pairingCode),
        establishmentId,
        Timestamp.from(expiresAt));
    return noStore(new EnrollmentResponse(establishmentId, pairingCode, expiresAt));
  }

  @PostMapping("/admin/station-replacements")
  @Transactional
  ResponseEntity<?> replaceStation(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody StationReplacementRequest request) {
    if (!isProvisioningAdmin(authorization)) return unauthorized();
    if (request == null
        || request.establishmentId() == null
        || request.deviceName() == null
        || request.deviceName().trim().length() < 2
        || request.deviceName().trim().length() > 120) {
      return ResponseEntity.badRequest()
          .body(new ApiErrorResponse(SyncErrorCode.VALIDATION_FAILED));
    }

    var establishments =
        jdbc.query(
            "SELECT id FROM estabelecimento_remoto WHERE id = ? FOR UPDATE",
            (rs, row) -> rs.getObject(1, UUID.class),
            request.establishmentId());
    if (establishments.isEmpty()) return ResponseEntity.notFound().build();

    jdbc.update(
        "UPDATE dispositivo_autorizado SET estado = 'REVOGADO', revogado_em = now(), "
            + "credencial_hash = NULL WHERE estabelecimento_id = ? AND estado = 'ATIVO'",
        request.establishmentId());
    jdbc.update(
        "DELETE FROM codigo_pareamento WHERE consumido_em IS NOT NULL OR expira_em <= now()");
    String pairingCode = newSecret();
    Instant expiresAt = Instant.now().plus(PAIRING_LIFETIME);
    jdbc.update(
        "INSERT INTO codigo_pareamento (token_hash, estabelecimento_id, expira_em) VALUES (?, ?, ?)",
        hash(pairingCode),
        request.establishmentId(),
        Timestamp.from(expiresAt));
    return noStore(new EnrollmentResponse(request.establishmentId(), pairingCode, expiresAt));
  }

  @PostMapping("/devices/pair")
  @Transactional
  ResponseEntity<?> pairDevice(@RequestBody PairingRequest request) {
    if (request == null
        || request.pairingCode() == null
        || request.deviceName() == null
        || request.deviceName().trim().length() < 2
        || request.deviceName().trim().length() > 120) {
      return ResponseEntity.badRequest()
          .body(new ApiErrorResponse(SyncErrorCode.VALIDATION_FAILED));
    }

    var enrollment =
        jdbc.query(
            "SELECT estabelecimento_id FROM codigo_pareamento "
                + "WHERE token_hash = ? AND consumido_em IS NULL AND expira_em > now() FOR UPDATE",
            (rs, rowNum) -> UUID.fromString(rs.getString(1)),
            hash(request.pairingCode()));
    if (enrollment.isEmpty()) {
      return unauthorized();
    }

    UUID establishmentId = enrollment.getFirst();
    jdbc.queryForObject(
        "SELECT id FROM estabelecimento_remoto WHERE id = ? FOR UPDATE",
        String.class,
        establishmentId);
    Integer activeDevices =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM dispositivo_autorizado "
                + "WHERE estabelecimento_id = ? AND estado = 'ATIVO'",
            Integer.class,
            establishmentId);
    if (activeDevices != null && activeDevices > 0) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(new ApiErrorResponse(SyncErrorCode.CONFLICT));
    }

    UUID deviceId = UUID.randomUUID();
    String deviceCredential = newSecret();
    jdbc.update(
        "INSERT INTO dispositivo_autorizado "
            + "(id, estabelecimento_id, nome, estado, credencial_hash) "
            + "VALUES (?, ?, ?, 'ATIVO', ?)",
        deviceId,
        establishmentId,
        request.deviceName().trim(),
        hash(deviceCredential));
    jdbc.update(
        "UPDATE codigo_pareamento SET consumido_em = now() WHERE token_hash = ?",
        hash(request.pairingCode()));
    return noStore(new PairingResponse(establishmentId, deviceId, deviceCredential));
  }

  @PostMapping("/devices/token")
  ResponseEntity<?> issueAccessToken(
      @RequestHeader(name = "X-Tucuma-Device-Id", required = false) UUID deviceId,
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    String credential = bearer(authorization, "Device");
    if (deviceId == null || credential == null) {
      return unauthorized();
    }

    Integer activeDevice =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM dispositivo_autorizado "
                + "WHERE id = ? AND credencial_hash = ? AND estado = 'ATIVO'",
            Integer.class,
            deviceId,
            hash(credential));
    if (activeDevice == null || activeDevice != 1) {
      return unauthorized();
    }

    String accessToken = newSecret();
    Instant expiresAt = Instant.now().plus(ACCESS_TOKEN_LIFETIME);
    jdbc.update("DELETE FROM token_acesso_dispositivo WHERE expira_em <= now()");
    jdbc.update(
        "INSERT INTO token_acesso_dispositivo (token_hash, dispositivo_id, expira_em) "
            + "VALUES (?, ?, ?)",
        hash(accessToken),
        deviceId,
        Timestamp.from(expiresAt));
    return noStore(new AccessTokenResponse(accessToken, expiresAt));
  }

  @PostMapping("/admin/devices/{deviceId}/revoke")
  ResponseEntity<?> revokeDevice(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable("deviceId") UUID deviceId) {
    if (!isProvisioningAdmin(authorization)) {
      return unauthorized();
    }
    int updated =
        jdbc.update(
            "UPDATE dispositivo_autorizado SET estado = 'REVOGADO', revogado_em = now(), "
                + "credencial_hash = NULL WHERE id = ? AND estado = 'ATIVO'",
            deviceId);
    if (updated == 0) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.noContent().build();
  }

  boolean isProvisioningAdmin(String authorization) {
    String secret = bearer(authorization, "Bearer");
    return secret != null
        && provisioningSecret.length >= 32
        && MessageDigest.isEqual(provisioningSecret, secret.getBytes(StandardCharsets.UTF_8));
  }

  static ResponseEntity<ApiErrorResponse> unauthorized() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .cacheControl(CacheControl.noStore())
        .body(new ApiErrorResponse(SyncErrorCode.AUTHORIZATION_REQUIRED));
  }

  private static <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }

  static String bearer(String authorization, String scheme) {
    if (authorization == null || !authorization.startsWith(scheme + " ")) {
      return null;
    }
    String value = authorization.substring(scheme.length() + 1).trim();
    return value.isEmpty() ? null : value;
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Não foi possível processar a credencial.");
    }
  }

  private static String newSecret() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  record EnrollmentRequest(UUID establishmentId, String establishmentName) {}

  record EnrollmentResponse(UUID establishmentId, String pairingCode, Instant expiresAt) {}

  record PairingRequest(String pairingCode, String deviceName) {}

  record StationReplacementRequest(UUID establishmentId, String deviceName) {}

  record PairingResponse(UUID establishmentId, UUID deviceId, String deviceCredential) {}

  record AccessTokenResponse(String accessToken, Instant expiresAt) {}
}
