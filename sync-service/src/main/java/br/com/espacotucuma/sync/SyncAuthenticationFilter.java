package br.com.espacotucuma.sync;

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

  SyncAuthenticationFilter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
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
      writeUnauthorized(response);
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
      writeUnauthorized(response);
      return;
    }

    AuthenticatedDevice device = devices.getFirst();
    request.setAttribute(ESTABLISHMENT_ID, device.establishmentId());
    request.setAttribute(DEVICE_ID, device.deviceId());
    chain.doFilter(request, response);
  }

  private static void writeUnauthorized(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"code\":\"SYNC_UNAUTHORIZED\"}");
  }

  private record AuthenticatedDevice(UUID establishmentId, UUID deviceId) {}
}
