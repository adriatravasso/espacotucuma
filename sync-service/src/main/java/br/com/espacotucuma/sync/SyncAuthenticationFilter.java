package br.com.espacotucuma.sync;

import br.com.espacotucuma.sync.contract.ApiErrorResponse;
import br.com.espacotucuma.sync.contract.SyncErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
final class SyncAuthenticationFilter extends OncePerRequestFilter {
  private static final Logger LOGGER = LoggerFactory.getLogger(SyncAuthenticationFilter.class);
  static final String ESTABLISHMENT_ID =
      SyncAuthenticationFilter.class.getName() + ".establishmentId";
  static final String DEVICE_ID = SyncAuthenticationFilter.class.getName() + ".deviceId";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final DeviceRequestRateLimiter rateLimiter;

  SyncAuthenticationFilter(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      @Value("${tucuma.security.requests-per-minute:120}") int requestsPerMinute) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.rateLimiter =
        new DeviceRequestRateLimiter(requestsPerMinute, java.time.Duration.ofMinutes(1));
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !(path.equals("/sync") || path.startsWith("/sync/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    applySecurityHeaders(response);
    String accessToken =
        DeviceAuthenticationController.bearer(
            request.getHeader(HttpHeaders.AUTHORIZATION), "Bearer");
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (isProvisioningOrPairingEndpoint(path)) {
      if (!rateLimiter.allow("ip:" + request.getRemoteAddr(), java.time.Instant.now())) {
        writeRateLimitError(response);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    if (accessToken == null) {
      if (!rateLimiter.allow("ip:" + request.getRemoteAddr(), java.time.Instant.now())) {
        writeRateLimitError(response);
        return;
      }
      LOGGER.warn("Sync access denied path={}", path);
      writeError(response, SyncErrorCode.AUTHORIZATION_REQUIRED);
      return;
    }

    List<AuthenticatedDevice> devices =
        jdbc.query(
            "SELECT d.estabelecimento_id, d.id FROM token_acesso_dispositivo t "
                + "JOIN dispositivo_autorizado d ON d.id = t.dispositivo_id "
                + "WHERE t.token_hash = ? AND t.expira_em > now() AND d.estado = 'ATIVO'",
            (rs, rowNum) ->
                new AuthenticatedDevice(
                    UUID.fromString(rs.getString("estabelecimento_id")),
                    UUID.fromString(rs.getString("id"))),
            DeviceAuthenticationController.hash(accessToken));
    if (devices.isEmpty()) {
      if (!rateLimiter.allow("ip:" + request.getRemoteAddr(), java.time.Instant.now())) {
        writeRateLimitError(response);
        return;
      }
      LOGGER.warn("Sync access denied path={}", path);
      writeError(response, SyncErrorCode.AUTHORIZATION_REQUIRED);
      return;
    }

    AuthenticatedDevice device = devices.getFirst();
    if (!rateLimiter.allow("device:" + device.deviceId(), java.time.Instant.now())) {
      writeRateLimitError(response);
      return;
    }
    request.setAttribute(ESTABLISHMENT_ID, device.establishmentId());
    request.setAttribute(DEVICE_ID, device.deviceId());
    LOGGER.info(
        "Sync access accepted path={} establishmentId={} deviceId={}",
        request.getRequestURI(),
        device.establishmentId(),
        device.deviceId());
    chain.doFilter(request, response);
  }

  private static boolean isProvisioningOrPairingEndpoint(String path) {
    return path.equals("/sync/devices/pair")
        || path.equals("/sync/devices/token")
        || path.equals("/sync/admin/enrollments")
        || path.equals("/sync/admin/station-replacements")
        || (path.startsWith("/sync/admin/devices/") && path.endsWith("/revoke"));
  }

  private void writeRateLimitError(HttpServletResponse response) throws IOException {
    response.setHeader("Retry-After", "60");
    writeError(response, SyncErrorCode.RETRY_LATER, 429);
  }

  private static void applySecurityHeaders(HttpServletResponse response) {
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
  }

  private void writeError(HttpServletResponse response, SyncErrorCode code) throws IOException {
    writeError(response, code, HttpServletResponse.SC_UNAUTHORIZED);
  }

  private void writeError(HttpServletResponse response, SyncErrorCode code, int status)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Pragma", "no-cache");
    objectMapper.writeValue(response.getWriter(), new ApiErrorResponse(code));
  }

  private record AuthenticatedDevice(UUID establishmentId, UUID deviceId) {}
}
