package br.com.espacotucuma.sync;

import br.com.espacotucuma.sync.contract.ApiErrorResponse;
import br.com.espacotucuma.sync.contract.SyncContract;
import br.com.espacotucuma.sync.contract.SyncErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class SyncContractVersionConfiguration implements WebMvcConfigurer {
  private final ObjectMapper objectMapper;

  SyncContractVersionConfiguration(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new VersionInterceptor(objectMapper));
  }

  private static final class VersionInterceptor implements HandlerInterceptor {
    private static final Set<String> CONTRACT_PATHS =
        Set.of(
            "/sync/status",
            "/sync/operations",
            "/sync/operations/status",
            "/sync/changes",
            "/sync/backup",
            "/sync/backups",
            "/sync/restore-request");

    private final ObjectMapper objectMapper;

    private VersionInterceptor(ObjectMapper objectMapper) {
      this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(
        HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
      if (!CONTRACT_PATHS.contains(
          request.getRequestURI().substring(request.getContextPath().length()))) {
        return true;
      }
      String version = request.getHeader(SyncContract.VERSION_HEADER);
      if (version == null || version.isBlank()) {
        writeError(response, SyncErrorCode.VALIDATION_FAILED);
        return false;
      }
      int parsedVersion;
      try {
        parsedVersion = Integer.parseInt(version);
      } catch (NumberFormatException exception) {
        writeError(response, SyncErrorCode.MALFORMED_REQUEST);
        return false;
      }
      if (parsedVersion != SyncContract.VERSION) {
        writeError(response, SyncErrorCode.CONTRACT_VERSION_UNSUPPORTED);
        return false;
      }
      response.setHeader(SyncContract.VERSION_HEADER, Integer.toString(SyncContract.VERSION));
      return true;
    }

    private void writeError(HttpServletResponse response, SyncErrorCode code) throws Exception {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding("UTF-8");
      objectMapper.writeValue(response.getWriter(), new ApiErrorResponse(code));
    }
  }
}
