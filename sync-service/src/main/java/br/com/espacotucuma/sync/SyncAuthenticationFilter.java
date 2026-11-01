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
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
final class SyncAuthenticationFilter extends OncePerRequestFilter {
  static final String ESTABLISHMENT_ID =
      SyncAuthenticationFilter.class.getName() + ".establishmentId";
  static final String DEVICE_ID = SyncAuthenticationFilter.class.getName() + ".deviceId";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  SyncAuthenticationFilter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !(path.equals("/sync") || path.startsWith("/sync/"))
        || path.equals("/sync/devices/pair")
        || path.equals("/sync/devices/token")
        || path.equals("/sync/admin/enrollments")
        || (path.startsWith("/sync/admin/devices/") && path.endsWith("/revoke"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String accessToken =
        DeviceAuthenticationController.bearer(
            request.getHeader(HttpHeaders.AUTHORIZATION), "Bearer");
    if (accessToken == null) {
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
      writeError(response, SyncErrorCode.AUTHORIZATION_REQUIRED);
      return;
    }

    AuthenticatedDevice device = devices.getFirst();
    request.setAttribute(ESTABLISHMENT_ID, device.establishmentId());
    request.setAttribute(DEVICE_ID, device.deviceId());
    chain.doFilter(request, response);
  }

  private void writeError(HttpServletResponse response, SyncErrorCode code) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-store");
    objectMapper.writeValue(response.getWriter(), new ApiErrorResponse(code));
  }

  private record AuthenticatedDevice(UUID establishmentId, UUID deviceId) {}
}
