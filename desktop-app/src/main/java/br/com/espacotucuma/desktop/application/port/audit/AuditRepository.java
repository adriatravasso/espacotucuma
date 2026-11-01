package br.com.espacotucuma.desktop.application.port.audit;

import br.com.espacotucuma.desktop.application.audit.AuditEvent;

public interface AuditRepository {
  void record(AuditEvent event);
}
