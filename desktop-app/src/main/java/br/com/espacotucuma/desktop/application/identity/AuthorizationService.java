package br.com.espacotucuma.desktop.application.identity;

import java.util.Objects;

public final class AuthorizationService {
  private final SessionContext sessions;

  public AuthorizationService(SessionContext sessions) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
  }

  public boolean isAllowed(Permission permission) {
    Objects.requireNonNull(permission, "permission");
    return sessions
        .current()
        .map(session -> allowed(session.user().role(), permission))
        .orElse(false);
  }

  public void require(Permission permission) {
    if (!isAllowed(permission)) {
      throw new AuthorizationException("A sessão atual não tem permissão para esta operação.");
    }
  }

  private boolean allowed(UserRole role, Permission permission) {
    if (role == UserRole.ADMINISTRADOR) {
      return true;
    }
    if (role == UserRole.RECEPCAO) {
      return switch (permission) {
        case CLIENT_READ,
            CLIENT_REGISTER,
            SCHEDULE_READ,
            SCHEDULE_MANAGE,
            ATTENDANCE_RECORD,
            PAYMENT_RECORD,
            CASH_MANAGE,
            REPORTS_VIEW ->
            true;
        default -> false;
      };
    }
    return switch (permission) {
      case OWN_SCHEDULE_READ, OWN_ATTENDANCE_COMPLETE, OWN_COMMISSION_READ -> true;
      default -> false;
    };
  }
}
