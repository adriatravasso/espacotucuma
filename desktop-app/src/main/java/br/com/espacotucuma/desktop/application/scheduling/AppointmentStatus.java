package br.com.espacotucuma.desktop.application.scheduling;

public enum AppointmentStatus {
  AGENDADO,
  CONFIRMADO,
  EM_ATENDIMENTO,
  CONCLUIDO,
  CANCELADO,
  NAO_COMPARECEU;

  public boolean allowsTransitionTo(AppointmentStatus next) {
    return switch (this) {
      case AGENDADO -> next == CONFIRMADO || next == CANCELADO || next == NAO_COMPARECEU;
      case CONFIRMADO -> next == EM_ATENDIMENTO || next == CANCELADO || next == NAO_COMPARECEU;
      case EM_ATENDIMENTO -> next == CONCLUIDO;
      case CONCLUIDO, CANCELADO, NAO_COMPARECEU -> false;
    };
  }
}
