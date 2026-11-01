package br.com.espacotucuma.desktop.application.attendance;

import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.commission.CommissionCalculator;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.port.attendance.AttendanceRepository;
import br.com.espacotucuma.desktop.application.port.audit.AuditRepository;
import br.com.espacotucuma.desktop.application.port.commission.CommissionRuleRepository;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.scheduling.AppointmentRepository;
import br.com.espacotucuma.desktop.application.port.service.ServiceRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import br.com.espacotucuma.desktop.application.scheduling.Appointment;
import br.com.espacotucuma.desktop.application.scheduling.AppointmentStatus;
import br.com.espacotucuma.desktop.application.service.BeautyService;
import br.com.espacotucuma.desktop.domain.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Local attendance changes, appointment state, audit and outbox share one SQLite transaction. */
public final class MaintainAttendanceUseCase {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Manaus");
  private final UnitOfWork unitOfWork;
  private final AttendanceRepository attendances;
  private final AppointmentRepository appointments;
  private final ServiceRepository services;
  private final CommissionRuleRepository commissionRules;
  private final OutboxRepository outbox;
  private final AuthorizationService authorization;
  private final AuditRepository audit;
  private final UUID stationId;
  private final Clock clock;

  public MaintainAttendanceUseCase(
      UnitOfWork unitOfWork,
      AttendanceRepository attendances,
      AppointmentRepository appointments,
      ServiceRepository services,
      CommissionRuleRepository commissionRules,
      OutboxRepository outbox,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID stationId) {
    this(
        unitOfWork,
        attendances,
        appointments,
        services,
        commissionRules,
        outbox,
        authorization,
        audit,
        stationId,
        Clock.system(BUSINESS_ZONE));
  }

  public MaintainAttendanceUseCase(
      UnitOfWork unitOfWork,
      AttendanceRepository attendances,
      AppointmentRepository appointments,
      ServiceRepository services,
      CommissionRuleRepository commissionRules,
      OutboxRepository outbox,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID stationId,
      Clock clock) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.attendances = Objects.requireNonNull(attendances, "attendances");
    this.appointments = Objects.requireNonNull(appointments, "appointments");
    this.services = Objects.requireNonNull(services, "services");
    this.commissionRules = Objects.requireNonNull(commissionRules, "commissionRules");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.stationId = Objects.requireNonNull(stationId, "stationId");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Attendance startFromAppointment(UUID appointmentId) {
    var actor = authorization.requireUser(Permission.ATTENDANCE_RECORD);
    Instant now = instantNow();
    return unitOfWork.execute(
        () -> {
          Appointment appointment =
              appointments
                  .findById(Objects.requireNonNull(appointmentId, "appointmentId"))
                  .orElseThrow(() -> new IllegalArgumentException("Agendamento não encontrado."));
          var existing = attendances.findByAppointment(appointmentId);
          if (existing.isPresent()) return existing.get();
          if (appointment.status() != AppointmentStatus.CONFIRMADO
              && appointment.status() != AppointmentStatus.EM_ATENDIMENTO) {
            throw new IllegalStateException(
                "Confirme o agendamento antes de iniciar o atendimento.");
          }
          Money zero = Money.of("0.00");
          Attendance attendance =
              new Attendance(
                  UUID.randomUUID(),
                  appointment.id(),
                  appointment.clientId(),
                  appointment.professionalId(),
                  AttendanceStatus.EM_ANDAMENTO,
                  zero,
                  zero,
                  zero,
                  null,
                  now,
                  null,
                  actor.id(),
                  now);
          attendances.insert(attendance);
          if (appointment.status() == AppointmentStatus.CONFIRMADO) {
            Appointment started =
                appointment.transitionTo(AppointmentStatus.EM_ATENDIMENTO, localNow());
            appointments.transition(
                appointment.id(),
                AppointmentStatus.CONFIRMADO,
                AppointmentStatus.EM_ATENDIMENTO,
                started.updatedAt());
          }
          record(
              "atendimento",
              attendance.id(),
              "ATENDIMENTO_INICIADO",
              attendancePayload(attendance),
              "{\"agendamentoId\":\""
                  + appointment.id()
                  + "\",\"profissionalId\":\""
                  + appointment.professionalId()
                  + "\"}",
              null,
              actor.id(),
              UUID.randomUUID(),
              now);
          if (appointment.status() == AppointmentStatus.CONFIRMADO) {
            record(
                "agendamento",
                appointment.id(),
                "AGENDAMENTO_EM_ATENDIMENTO",
                appointmentPayload(appointment, AppointmentStatus.EM_ATENDIMENTO),
                "{\"estadoAnterior\":\"CONFIRMADO\",\"estadoNovo\":\"EM_ATENDIMENTO\",\"atendimentoId\":\""
                    + attendance.id()
                    + "\"}",
                null,
                actor.id(),
                UUID.randomUUID(),
                now);
          }
          return attendance;
        });
  }

  public AttendanceServiceLine addService(UUID attendanceId, UUID serviceId, int quantity) {
    var actor = authorization.requireUser(Permission.ATTENDANCE_RECORD);
    Instant now = instantNow();
    return unitOfWork.execute(
        () -> {
          Attendance attendance = requireInProgress(attendanceId);
          var previous = attendances.findService(attendanceId, serviceId);
          if (previous.isPresent()) return previous.get();
          BeautyService service =
              services
                  .findById(serviceId)
                  .orElseThrow(() -> new IllegalArgumentException("Serviço não encontrado."));
          if (!service.active()) throw new IllegalStateException("O serviço está inativo.");
          var decision =
              CommissionCalculator.resolve(
                  service.commissionBasisPoints(),
                  commissionRules.findByProfessionalAndService(
                      attendance.professionalId(), service.id()));
          AttendanceServiceLine line =
              new AttendanceServiceLine(
                  UUID.randomUUID(),
                  attendance.id(),
                  service.id(),
                  service.name(),
                  quantity,
                  service.price(),
                  decision);
          attendances.insertService(line);
          Money subtotal =
              attendances.findServices(attendance.id()).stream()
                  .map(AttendanceServiceLine::total)
                  .reduce(Money.of("0.00"), Money::add);
          Attendance updated = attendance.withLines(subtotal, now);
          attendances.updateTotals(updated);
          UUID operationId = UUID.randomUUID();
          record(
              "atendimento_servico",
              line.id(),
              "SERVICO_ATENDIMENTO_ADICIONADO",
              servicePayload(line),
              "{\"atendimentoId\":\""
                  + attendance.id()
                  + "\",\"servicoId\":\""
                  + service.id()
                  + "\",\"precoCongeladoCentavos\":"
                  + cents(line.total())
                  + ",\"comissaoPontosBase\":"
                  + decision.percentageBasisPoints()
                  + "}",
              null,
              actor.id(),
              operationId,
              now);
          return line;
        });
  }

  public Attendance applyDiscount(UUID attendanceId, Money discount, String reason) {
    var actor = authorization.requireUser(Permission.DISCOUNT_MANAGE);
    String normalizedReason = normalizeReason(reason);
    Instant now = instantNow();
    UUID operationId = UUID.randomUUID();
    return unitOfWork.execute(
        () -> {
          Attendance attendance = requireInProgress(attendanceId);
          Attendance updated =
              attendance.withDiscount(Objects.requireNonNull(discount, "discount"), now);
          attendances.updateTotals(updated);
          record(
              "atendimento",
              attendance.id(),
              "DESCONTO_ATENDIMENTO_APLICADO",
              attendancePayload(updated),
              "{\"descontoCentavos\":"
                  + cents(updated.discount())
                  + ",\"motivo\":\""
                  + escape(normalizedReason)
                  + "\"}",
              normalizedReason,
              actor.id(),
              operationId,
              now);
          return updated;
        });
  }

  public Attendance updateNotes(UUID attendanceId, String notes) {
    var actor = authorization.requireUser(Permission.ATTENDANCE_RECORD);
    Instant now = instantNow();
    String normalized = notes == null || notes.isBlank() ? null : notes.strip();
    return unitOfWork.execute(
        () -> {
          Attendance attendance = requireInProgress(attendanceId);
          if (Objects.equals(attendance.notes(), normalized)) return attendance;
          Attendance updated = attendance.withNotes(normalized, now);
          attendances.updateNotes(updated);
          record(
              "atendimento",
              attendance.id(),
              "OBSERVACAO_ATENDIMENTO_ATUALIZADA",
              attendancePayload(updated),
              "{\"atendimentoId\":\"" + attendance.id() + "\"}",
              null,
              actor.id(),
              UUID.randomUUID(),
              now);
          return updated;
        });
  }

  public Attendance complete(UUID attendanceId) {
    var actor = authorization.requireUser(Permission.ATTENDANCE_RECORD);
    Instant now = instantNow();
    UUID operationId = UUID.randomUUID();
    return unitOfWork.execute(
        () -> {
          Attendance attendance = requireInProgress(attendanceId);
          List<AttendanceServiceLine> lines = attendances.findServices(attendanceId);
          if (lines.isEmpty()) {
            throw new IllegalStateException("Adicione ao menos um serviço antes de concluir.");
          }
          Appointment appointment =
              appointments
                  .findById(attendance.appointmentId())
                  .orElseThrow(() -> new IllegalArgumentException("Agendamento não encontrado."));
          Appointment concluded = appointment.transitionTo(AppointmentStatus.CONCLUIDO, localNow());
          Attendance completed = attendance.complete(now);
          attendances.complete(completed);
          appointments.transition(
              appointment.id(),
              AppointmentStatus.EM_ATENDIMENTO,
              AppointmentStatus.CONCLUIDO,
              concluded.updatedAt());
          record(
              "atendimento",
              completed.id(),
              "ATENDIMENTO_CONCLUIDO",
              attendancePayload(completed),
              "{\"servicos\":"
                  + lines.size()
                  + ",\"subtotalCentavos\":"
                  + cents(completed.subtotal())
                  + ",\"descontoCentavos\":"
                  + cents(completed.discount())
                  + ",\"totalCentavos\":"
                  + cents(completed.total())
                  + ",\"agendamentoId\":\""
                  + appointment.id()
                  + "\"}",
              null,
              actor.id(),
              operationId,
              now);
          record(
              "agendamento",
              appointment.id(),
              "AGENDAMENTO_CONCLUIDO",
              appointmentPayload(concluded),
              "{\"estadoAnterior\":\"EM_ATENDIMENTO\",\"estadoNovo\":\"CONCLUIDO\",\"atendimentoId\":\""
                  + completed.id()
                  + "\"}",
              null,
              actor.id(),
              UUID.randomUUID(),
              now);
          return completed;
        });
  }

  public Attendance findById(UUID attendanceId) {
    authorization.require(Permission.ATTENDANCE_RECORD);
    return attendances
        .findById(attendanceId)
        .orElseThrow(() -> new IllegalArgumentException("Atendimento não encontrado."));
  }

  public List<AttendanceServiceLine> findServices(UUID attendanceId) {
    authorization.require(Permission.ATTENDANCE_RECORD);
    return attendances.findServices(attendanceId);
  }

  public List<BeautyService> availableServices() {
    authorization.require(Permission.SERVICE_READ);
    return services
        .searchPage(
            new br.com.espacotucuma.desktop.application.service.ServicePageQuery(
                "",
                "ALL",
                false,
                br.com.espacotucuma.desktop.application.service.ServiceSortField.NAME,
                true,
                0,
                100))
        .items();
  }

  private Attendance requireInProgress(UUID id) {
    Attendance attendance =
        attendances
            .findById(Objects.requireNonNull(id, "attendanceId"))
            .orElseThrow(() -> new IllegalArgumentException("Atendimento não encontrado."));
    if (attendance.status() != AttendanceStatus.EM_ANDAMENTO) {
      throw new IllegalStateException("O atendimento já foi encerrado.");
    }
    return attendance;
  }

  private void record(
      String entity,
      UUID entityId,
      String action,
      String payload,
      String details,
      String reason,
      UUID actorId,
      UUID operationId,
      Instant at) {
    outbox.enqueue(
        new PendingOperation(
            operationId,
            entity,
            entityId,
            action,
            operationId.toString(),
            payload,
            1,
            outbox.expectedVersionForNext(entity, entityId),
            stationId,
            at,
            0));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actorId,
            operationId,
            action,
            entity,
            entityId,
            stationId,
            details,
            reason,
            at));
  }

  private String attendancePayload(Attendance item) {
    return "{\"id\":\""
        + item.id()
        + "\",\"agendamentoId\":\""
        + item.appointmentId()
        + "\",\"clienteId\":\""
        + item.clientId()
        + "\",\"profissionalId\":\""
        + item.professionalId()
        + "\",\"estado\":\""
        + item.status()
        + "\",\"subtotalCentavos\":"
        + cents(item.subtotal())
        + ",\"descontoCentavos\":"
        + cents(item.discount())
        + ",\"totalCentavos\":"
        + cents(item.total())
        + ",\"observacoes\":\""
        + escape(item.notes() == null ? "" : item.notes())
        + "\"}";
  }

  private String servicePayload(AttendanceServiceLine line) {
    return "{\"id\":\""
        + line.id()
        + "\",\"atendimentoId\":\""
        + line.attendanceId()
        + "\",\"servicoId\":\""
        + line.serviceId()
        + "\",\"nomeSnapshot\":\""
        + escape(line.nameSnapshot())
        + "\",\"quantidade\":"
        + line.quantity()
        + ",\"precoUnitarioCentavos\":"
        + cents(line.unitPrice())
        + ",\"comissaoPontosBase\":"
        + line.commission().percentageBasisPoints()
        + ",\"comissaoRegraId\":"
        + (line.commission().ruleId() == null ? "null" : "\"" + line.commission().ruleId() + "\"")
        + ",\"comissaoOrigem\":\""
        + line.commission().source()
        + "\"}";
  }

  private String appointmentPayload(Appointment item) {
    return appointmentPayload(item, item.status());
  }

  private String appointmentPayload(Appointment item, AppointmentStatus status) {
    return "{\"id\":\""
        + item.id()
        + "\",\"clienteId\":\""
        + item.clientId()
        + "\",\"profissionalId\":\""
        + item.professionalId()
        + "\",\"servicoId\":\""
        + item.serviceId()
        + "\",\"inicioUtc\":\""
        + utc(item.startsAt())
        + "\",\"fimUtc\":\""
        + utc(item.endsAt())
        + "\",\"estado\":\""
        + status
        + "\",\"observacao\":\""
        + escape(item.notes() == null ? "" : item.notes())
        + "\",\"excecaoConflito\":false}";
  }

  private Instant utc(java.time.LocalDateTime local) {
    return local.atZone(BUSINESS_ZONE).toInstant().truncatedTo(ChronoUnit.SECONDS);
  }

  private Instant instantNow() {
    return clock.instant().truncatedTo(ChronoUnit.SECONDS);
  }

  private java.time.LocalDateTime localNow() {
    return java.time.LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
  }

  private long cents(Money money) {
    return money.amount().movePointRight(2).longValueExact();
  }

  private String normalizeReason(String reason) {
    String normalized = Objects.requireNonNull(reason, "reason").strip();
    if (normalized.length() < 2 || normalized.length() > 240) {
      throw new IllegalArgumentException("Informe um motivo de 2 a 240 caracteres.");
    }
    return normalized;
  }

  private String escape(String text) {
    return text.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }
}
