package br.com.espacotucuma.desktop.application.scheduling;

import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.port.audit.AuditRepository;
import br.com.espacotucuma.desktop.application.port.client.ClientRepository;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.professional.ProfessionalRepository;
import br.com.espacotucuma.desktop.application.port.scheduling.AppointmentRepository;
import br.com.espacotucuma.desktop.application.port.service.ServiceRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import br.com.espacotucuma.desktop.application.professional.Professional;
import br.com.espacotucuma.desktop.application.service.BeautyService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Application rules for offline appointments. Every write, audit and outbox event shares a
 * transaction.
 */
public final class MaintainAppointmentUseCase {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Manaus");
  private final UnitOfWork unitOfWork;
  private final AppointmentRepository appointments;
  private final ClientRepository clients;
  private final ProfessionalRepository professionals;
  private final ServiceRepository services;
  private final OutboxRepository outbox;
  private final AuthorizationService authorization;
  private final AuditRepository audit;
  private final UUID stationId;
  private final Clock clock;

  public MaintainAppointmentUseCase(
      UnitOfWork unitOfWork,
      AppointmentRepository appointments,
      ClientRepository clients,
      ProfessionalRepository professionals,
      ServiceRepository services,
      OutboxRepository outbox,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID stationId) {
    this(
        unitOfWork,
        appointments,
        clients,
        professionals,
        services,
        outbox,
        authorization,
        audit,
        stationId,
        Clock.system(BUSINESS_ZONE));
  }

  public MaintainAppointmentUseCase(
      UnitOfWork unitOfWork,
      AppointmentRepository appointments,
      ClientRepository clients,
      ProfessionalRepository professionals,
      ServiceRepository services,
      OutboxRepository outbox,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID stationId,
      Clock clock) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.appointments = Objects.requireNonNull(appointments, "appointments");
    this.clients = Objects.requireNonNull(clients, "clients");
    this.professionals = Objects.requireNonNull(professionals, "professionals");
    this.services = Objects.requireNonNull(services, "services");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.stationId = Objects.requireNonNull(stationId, "stationId");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Appointment create(
      UUID clientId, UUID professionalId, UUID serviceId, LocalDateTime startsAt, String notes) {
    return create(clientId, professionalId, serviceId, startsAt, notes, false, null);
  }

  /** Administrative overlap exceptions are explicit and require a reason. */
  public Appointment create(
      UUID clientId,
      UUID professionalId,
      UUID serviceId,
      LocalDateTime startsAt,
      String notes,
      boolean overrideOverlap,
      String reason) {
    var actor = authorization.requireUser(Permission.SCHEDULE_MANAGE);
    LocalDateTime now = localNow();
    Instant eventAt = instantNow();
    UUID id = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    return unitOfWork.execute(
        () -> {
          var client =
              clients
                  .findById(Objects.requireNonNull(clientId, "clientId"))
                  .orElseThrow(() -> new IllegalArgumentException("Cliente não encontrado."));
          if (!client.active()) throw new IllegalStateException("O cliente está inativo.");
          BeautyService service = activeService(serviceId);
          Professional professional = activeProfessional(professionalId);
          LocalDateTime endsAt = startsAt.plusMinutes(service.durationMinutes());
          validateTime(startsAt, endsAt);
          validateProfessionalAvailability(professional, startsAt, endsAt);
          boolean conflict = appointments.hasConflict(professionalId, startsAt, endsAt, null);
          validateOverlapException(actor.role(), conflict, overrideOverlap, reason);
          Appointment appointment =
              new Appointment(
                  id,
                  clientId,
                  professionalId,
                  serviceId,
                  startsAt,
                  endsAt,
                  AppointmentStatus.AGENDADO,
                  notes,
                  actor.id(),
                  now,
                  now);
          appointments.insert(appointment);
          record(
              id,
              operationId,
              actor.id(),
              "AGENDAMENTO_CRIADO",
              payload(appointment, conflict),
              "{\"clienteId\":\""
                  + clientId
                  + "\",\"profissionalId\":\""
                  + professionalId
                  + "\",\"servicoId\":\""
                  + serviceId
                  + "\",\"inicioLocal\":\""
                  + startsAt
                  + "\",\"fimLocal\":\""
                  + endsAt
                  + "\",\"excecaoConflito\":"
                  + conflict
                  + "}",
              conflict ? normalizeReason(reason) : null,
              eventAt);
          return appointment;
        });
  }

  public Appointment reschedule(UUID id, UUID professionalId, LocalDateTime startsAt) {
    return reschedule(id, professionalId, startsAt, false, null);
  }

  public Appointment reschedule(
      UUID id,
      UUID professionalId,
      LocalDateTime startsAt,
      boolean overrideOverlap,
      String reason) {
    var actor = authorization.requireUser(Permission.SCHEDULE_MANAGE);
    LocalDateTime now = localNow();
    Instant eventAt = instantNow();
    UUID operationId = UUID.randomUUID();
    return unitOfWork.execute(
        () -> {
          Appointment previous = appointments.findById(id).orElseThrow(() -> missing(id));
          if (previous.status() != AppointmentStatus.AGENDADO
              && previous.status() != AppointmentStatus.CONFIRMADO) {
            throw new IllegalStateException("Somente agendamentos ativos podem ser remarcados.");
          }
          BeautyService service = activeService(previous.serviceId());
          Professional professional = activeProfessional(professionalId);
          LocalDateTime endsAt = startsAt.plusMinutes(service.durationMinutes());
          validateTime(startsAt, endsAt);
          validateProfessionalAvailability(professional, startsAt, endsAt);
          boolean conflict =
              appointments.hasConflict(professionalId, startsAt, endsAt, previous.id());
          validateOverlapException(actor.role(), conflict, overrideOverlap, reason);
          Appointment updated =
              new Appointment(
                  previous.id(),
                  previous.clientId(),
                  professionalId,
                  previous.serviceId(),
                  startsAt,
                  endsAt,
                  previous.status(),
                  previous.notes(),
                  previous.createdBy(),
                  previous.createdAt(),
                  now.isBefore(previous.updatedAt()) ? previous.updatedAt() : now);
          appointments.updateTime(updated);
          record(
              id,
              operationId,
              actor.id(),
              "AGENDAMENTO_REMARCADO",
              payload(updated, conflict),
              "{\"inicioAnteriorLocal\":\""
                  + previous.startsAt()
                  + "\",\"fimAnteriorLocal\":\""
                  + previous.endsAt()
                  + "\",\"inicioNovoLocal\":\""
                  + startsAt
                  + "\",\"fimNovoLocal\":\""
                  + endsAt
                  + "\",\"profissionalAnteriorId\":\""
                  + previous.professionalId()
                  + "\",\"profissionalNovoId\":\""
                  + professionalId
                  + "\",\"excecaoConflito\":"
                  + conflict
                  + "}",
              conflict ? normalizeReason(reason) : null,
              eventAt);
          return updated;
        });
  }

  public void cancel(UUID id, String reason) {
    transition(id, AppointmentStatus.CANCELADO, Permission.SCHEDULE_MANAGE, reason, null);
  }

  public Appointment confirm(UUID id) {
    return transition(id, AppointmentStatus.CONFIRMADO, Permission.SCHEDULE_MANAGE, null, null);
  }

  public Appointment startAttendance(UUID id) {
    return transition(
        id, AppointmentStatus.EM_ATENDIMENTO, Permission.ATTENDANCE_RECORD, null, null);
  }

  public Appointment markNoShow(UUID id, String reason) {
    return transition(
        id, AppointmentStatus.NAO_COMPARECEU, Permission.SCHEDULE_MANAGE, reason, null);
  }

  /** Finishing a visit requires the linked attendance to already be concluded. */
  public Appointment complete(UUID id, UUID attendanceId) {
    return transition(
        id,
        AppointmentStatus.CONCLUIDO,
        Permission.ATTENDANCE_RECORD,
        null,
        Objects.requireNonNull(attendanceId, "attendanceId"));
  }

  public List<Appointment> findForDay(LocalDate day, UUID professionalId) {
    authorization.require(Permission.SCHEDULE_READ);
    return appointments.findForDay(Objects.requireNonNull(day, "day"), professionalId);
  }

  public Appointment findById(UUID id) {
    authorization.require(Permission.SCHEDULE_READ);
    return appointments.findById(id).orElseThrow(() -> missing(id));
  }

  private Appointment transition(
      UUID id, AppointmentStatus target, Permission permission, String reason, UUID attendanceId) {
    var actor = authorization.requireUser(permission);
    String normalizedReason =
        target == AppointmentStatus.CANCELADO || target == AppointmentStatus.NAO_COMPARECEU
            ? normalizeReason(reason)
            : null;
    LocalDateTime now = localNow();
    Instant eventAt = instantNow();
    UUID operationId = UUID.randomUUID();
    return unitOfWork.execute(
        () -> {
          Appointment previous =
              appointments
                  .findById(Objects.requireNonNull(id, "id"))
                  .orElseThrow(() -> missing(id));
          if (target == AppointmentStatus.CONCLUIDO
              && !appointments.hasCompletedAttendance(id, attendanceId)) {
            throw new IllegalStateException(
                "Conclua o atendimento associado antes de concluir o agendamento.");
          }
          Appointment updated = previous.transitionTo(target, now);
          appointments.transition(id, previous.status(), target, updated.updatedAt());
          String action = "AGENDAMENTO_" + target.name();
          String details =
              "{\"estadoAnterior\":\""
                  + previous.status()
                  + "\",\"estadoNovo\":\""
                  + target
                  + "\""
                  + (attendanceId == null ? "" : ",\"atendimentoId\":\"" + attendanceId + "\"")
                  + (normalizedReason == null
                      ? ""
                      : ",\"motivo\":\"" + escape(normalizedReason) + "\"")
                  + "}";
          Appointment result = updated;
          record(
              id,
              operationId,
              actor.id(),
              action,
              payload(result, false),
              details,
              normalizedReason,
              eventAt);
          return result;
        });
  }

  private BeautyService activeService(UUID id) {
    BeautyService service =
        services
            .findById(Objects.requireNonNull(id, "serviceId"))
            .orElseThrow(() -> new IllegalArgumentException("Serviço não encontrado."));
    if (!service.active()) throw new IllegalStateException("O serviço está inativo.");
    return service;
  }

  private Professional activeProfessional(UUID id) {
    Professional professional =
        professionals
            .findById(Objects.requireNonNull(id, "professionalId"))
            .orElseThrow(() -> new IllegalArgumentException("Profissional não encontrado."));
    if (!professional.active()) throw new IllegalStateException("O profissional está inativo.");
    return professional;
  }

  private void validateProfessionalAvailability(
      Professional professional, LocalDateTime startsAt, LocalDateTime endsAt) {
    if (!professional.canReceive(startsAt, endsAt)) {
      throw new IllegalStateException(
          "O profissional está fora do expediente ou indisponível nesse horário.");
    }
  }

  private void validateTime(LocalDateTime startsAt, LocalDateTime endsAt) {
    Objects.requireNonNull(startsAt, "startsAt");
    Objects.requireNonNull(endsAt, "endsAt");
    if (!startsAt.toLocalDate().equals(endsAt.toLocalDate())) {
      throw new IllegalArgumentException("O agendamento não pode atravessar a virada do dia.");
    }
  }

  private void validateOverlapException(
      UserRole role, boolean conflict, boolean override, String reason) {
    if (!conflict) return;
    if (!override)
      throw new IllegalStateException("O profissional já possui agendamento nesse intervalo.");
    if (role != UserRole.ADMINISTRADOR) {
      throw new br.com.espacotucuma.desktop.application.identity.AuthorizationException(
          "Somente a administração pode autorizar encaixe sobreposto.");
    }
    normalizeReason(reason);
  }

  private String payload(Appointment appointment, boolean overlapException) {
    return "{\"id\":\""
        + appointment.id()
        + "\",\"clienteId\":\""
        + appointment.clientId()
        + "\",\"profissionalId\":\""
        + appointment.professionalId()
        + "\",\"servicoId\":\""
        + appointment.serviceId()
        + "\",\"inicioUtc\":\""
        + utc(appointment.startsAt())
        + "\",\"fimUtc\":\""
        + utc(appointment.endsAt())
        + "\",\"estado\":\""
        + appointment.status()
        + "\",\"observacao\":\""
        + escape(appointment.notes() == null ? "" : appointment.notes())
        + "\",\"excecaoConflito\":"
        + overlapException
        + "}";
  }

  private void record(
      UUID appointmentId,
      UUID operationId,
      UUID actorId,
      String action,
      String payload,
      String details,
      String reason,
      Instant at) {
    outbox.enqueue(
        new PendingOperation(
            operationId,
            "agendamento",
            appointmentId,
            action,
            operationId.toString(),
            payload,
            1,
            outbox.expectedVersionForNext("agendamento", appointmentId),
            stationId,
            at,
            0));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actorId,
            operationId,
            action,
            "agendamento",
            appointmentId,
            stationId,
            details,
            reason,
            at));
  }

  private LocalDateTime localNow() {
    return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
  }

  private Instant instantNow() {
    return clock.instant().truncatedTo(ChronoUnit.SECONDS);
  }

  private Instant utc(LocalDateTime local) {
    return local.atZone(BUSINESS_ZONE).toInstant().truncatedTo(ChronoUnit.SECONDS);
  }

  private String normalizeReason(String reason) {
    String normalized = Objects.requireNonNull(reason, "reason").strip();
    if (normalized.length() < 2 || normalized.length() > 240) {
      throw new IllegalArgumentException("Informe um motivo de 2 a 240 caracteres.");
    }
    return normalized;
  }

  private String escape(String text) {
    if (text == null) return "";
    StringBuilder escaped = new StringBuilder();
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      switch (character) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
          else escaped.append(character);
        }
      }
    }
    return escaped.toString();
  }

  private IllegalArgumentException missing(UUID id) {
    return new IllegalArgumentException("Agendamento não encontrado: " + id);
  }
}
