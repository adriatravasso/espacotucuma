package br.com.espacotucuma.desktop.infrastructure.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.application.port.prepaid.PrepaidPackageRepository;
import br.com.espacotucuma.desktop.application.prepaid.MaintainPrepaidPackagesUseCase;
import br.com.espacotucuma.desktop.application.prepaid.PaymentForm;
import br.com.espacotucuma.desktop.domain.money.Money;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PrepaidPackagesSQLiteTest {
  private static final UUID STATION = UUID.fromString("9607c069-24c8-4f42-8692-67238ea673ec");
  private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

  @Test
  void purchaseSnapshotConsumeAndRepeatedUseAreAtomicAndTraceable(@TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("packages.sqlite"))) {
      var prepaidPackage = fixture.maintain.create("Cinco sessões", 5, 30, Money.of("250.00"));
      UUID attendance = fixture.insertAttendance();
      var balance =
          fixture.maintain.contract(prepaidPackage.id(), fixture.clientId, PaymentForm.PIX);
      fixture.maintain.update(prepaidPackage.id(), "Atualizado", 10, 90, Money.of("500.00"));

      var purchased = fixture.repository.findBalance(balance.id()).orElseThrow();
      assertEquals("Cinco sessões", purchased.packageName());
      assertEquals(5, purchased.sessionsContracted());
      assertEquals(Money.of("250.00"), purchased.paidPrice());
      assertEquals(PaymentForm.PIX, purchased.paymentForm());

      fixture.maintain.consume(balance.id(), attendance);
      assertEquals(
          4, fixture.repository.findBalance(balance.id()).orElseThrow().sessionsRemaining());
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM utilizacao_pacote WHERE atendimento_id='" + attendance + "'"));
      assertThrows(
          IllegalStateException.class, () -> fixture.maintain.consume(balance.id(), attendance));
      assertEquals(
          4, fixture.repository.findBalance(balance.id()).orElseThrow().sessionsRemaining());
      assertEquals(
          2,
          fixture.scalar(
              "SELECT COUNT(*) FROM evento_auditoria WHERE acao IN ('PACOTE_CONTRATADO','SESSAO_PACOTE_CONSUMIDA')"));
      assertEquals(
          2,
          fixture.scalar(
              "SELECT COUNT(*) FROM operacao_pendente WHERE entidade IN ('saldo_sessoes','utilizacao_pacote')"));
    }
  }

  @Test
  void expiredPackagesCannotBeConsumedAndNeverGoNegative(@TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("expired-packages.sqlite"))) {
      var prepaidPackage = fixture.maintain.create("Pacote vencível", 1, 30, Money.of("30.00"));
      UUID attendance = fixture.insertAttendance();
      var balance =
          fixture.maintain.contract(prepaidPackage.id(), fixture.clientId, PaymentForm.DINHEIRO);
      fixture.execute(
          "UPDATE saldo_sessoes SET valido_ate='2026-10-31' WHERE id='" + balance.id() + "'");

      assertThrows(
          IllegalStateException.class, () -> fixture.maintain.consume(balance.id(), attendance));
      assertEquals(
          1, fixture.repository.findBalance(balance.id()).orElseThrow().sessionsRemaining());
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM utilizacao_pacote"));
    }
  }

  @ParameterizedTest
  @CsvSource({
    "2026-10-31, 2026-11-01, false",
    "2026-11-01, 2026-11-01, true",
    "2026-11-01, 2026-11-02, false"
  })
  void packageIsUsableOnItsFinalValidBusinessDate(
      String expiresOn, String today, boolean usable, @TempDir Path temp) throws Exception {
    try (Fixture fixture =
        new Fixture(
            temp.resolve("expiry-boundary-" + expiresOn + ".sqlite"),
            Clock.fixed(Instant.parse(today + "T12:00:00Z"), ZoneId.of("America/Manaus")))) {
      var prepaidPackage = fixture.maintain.create("Pacote de validade", 1, 30, Money.of("30.00"));
      UUID attendance = fixture.insertAttendance();
      var balance =
          fixture.maintain.contract(prepaidPackage.id(), fixture.clientId, PaymentForm.DINHEIRO);
      fixture.execute(
          "UPDATE saldo_sessoes SET valido_ate='"
              + expiresOn
              + "' WHERE id='"
              + balance.id()
              + "'");

      if (usable) {
        fixture.maintain.consume(balance.id(), attendance);
        assertEquals(
            0, fixture.repository.findBalance(balance.id()).orElseThrow().sessionsRemaining());
        assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM utilizacao_pacote"));
      } else {
        assertThrows(
            IllegalStateException.class, () -> fixture.maintain.consume(balance.id(), attendance));
        assertEquals(
            1, fixture.repository.findBalance(balance.id()).orElseThrow().sessionsRemaining());
        assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM utilizacao_pacote"));
      }
    }
  }

  @Test
  void reversalIsExplicitAndOnlyRestoresCanceledAttendance(@TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("reversal-packages.sqlite"))) {
      var prepaidPackage = fixture.maintain.create("Pacote reversível", 1, 30, Money.of("30.00"));
      UUID attendance = fixture.insertAttendance();
      var balance =
          fixture.maintain.contract(prepaidPackage.id(), fixture.clientId, PaymentForm.CARTAO);
      fixture.maintain.consume(balance.id(), attendance);
      var usage = fixture.maintain.history(balance.id()).getFirst();
      assertThrows(
          IllegalStateException.class,
          () -> fixture.maintain.reverseUsage(usage.id(), "Tentativa"));
      fixture.execute("UPDATE atendimento SET estado='CANCELADO' WHERE id='" + attendance + "'");
      fixture.maintain.reverseUsage(usage.id(), "Atendimento cancelado");
      assertEquals(
          1, fixture.repository.findBalance(balance.id()).orElseThrow().sessionsRemaining());
      assertTrue(fixture.maintain.history(balance.id()).getFirst().reversed());
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM reversao_utilizacao_pacote"));
      assertThrows(
          IllegalStateException.class,
          () -> fixture.maintain.reverseUsage(usage.id(), "Repetição"));
    }
  }

  private static final class Fixture implements AutoCloseable {
    final LocalDatabaseSession database;
    final SqliteUnitOfWork unitOfWork;
    final SqliteAuditRepository audit;
    final SqliteOutboxRepository outbox;
    final PrepaidPackageRepository repository;
    final MaintainPrepaidPackagesUseCase maintain;
    final UUID adminId;
    final UUID clientId = UUID.randomUUID();

    Fixture(Path path) throws Exception {
      this(path, Clock.fixed(NOW, ZoneId.of("America/Manaus")));
    }

    Fixture(Path path, Clock clock) throws Exception {
      database = LocalDatabaseSession.open(path);
      unitOfWork = new SqliteUnitOfWork(database.connection());
      audit = new SqliteAuditRepository(database.connection(), unitOfWork);
      new CreateFirstAdministratorUseCase(
              unitOfWork,
              new SqliteInitialSetupRepository(database.connection(), unitOfWork),
              new Pbkdf2PasswordHasher(),
              audit,
              STATION)
          .create(
              new InitialAdministratorSetup(
                  "Espaço Tucumã", "Admin Teste", "admin", "SenhaTesteOffline2026!".toCharArray()));
      UUID professionalId;
      try (Statement statement = database.connection().createStatement();
          ResultSet result =
              statement.executeQuery(
                  "SELECT id,nome,login FROM usuario WHERE perfil='ADMINISTRADOR'")) {
        result.next();
        adminId = UUID.fromString(result.getString("id"));
        SessionContext sessions = new SessionContext();
        sessions.establish(
            new AuthenticatedUser(
                adminId,
                result.getString("nome"),
                result.getString("login"),
                UserRole.ADMINISTRADOR));
      }
      outbox = new SqliteOutboxRepository(database.connection(), unitOfWork);
      repository = new SqlitePrepaidPackageRepository(database.connection(), unitOfWork);
      maintain =
          new MaintainPrepaidPackagesUseCase(
              unitOfWork,
              repository,
              new SqliteClientRepository(database.connection(), unitOfWork),
              outbox,
              new AuthorizationService(sessionContext()),
              audit,
              STATION,
              clock);
      professionalId = insertProfessional();
      insertClient();
      insertService();
    }

    private SessionContext sessionContext() {
      SessionContext sessions = new SessionContext();
      try (Statement statement = database.connection().createStatement();
          ResultSet result =
              statement.executeQuery(
                  "SELECT id,nome,login FROM usuario WHERE perfil='ADMINISTRADOR'")) {
        result.next();
        sessions.establish(
            new AuthenticatedUser(
                adminId,
                result.getString("nome"),
                result.getString("login"),
                UserRole.ADMINISTRADOR));
        return sessions;
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    }

    private UUID insertProfessional() throws Exception {
      UUID id = UUID.randomUUID();
      try (PreparedStatement statement =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO profissional (id,nome,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'Profissional',1,?,?)")) {
        statement.setString(1, id.toString());
        statement.setString(2, NOW.toString());
        statement.setString(3, NOW.toString());
        statement.executeUpdate();
      }
      return id;
    }

    private void insertClient() throws Exception {
      try (PreparedStatement statement =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO cliente (id,codigo_interno,nome,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'CLI-991001','Cliente Pacotes',1,?,?)")) {
        statement.setString(1, clientId.toString());
        statement.setString(2, NOW.toString());
        statement.setString(3, NOW.toString());
        statement.executeUpdate();
      }
    }

    private void insertService() throws Exception {
      try (PreparedStatement statement =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO servico (id,nome,categoria,duracao_minutos,preco_centavos,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'Serviço teste','Geral',30,1000,1,?,?)")) {
        statement.setString(1, serviceId.toString());
        statement.setString(2, NOW.toString());
        statement.setString(3, NOW.toString());
        statement.executeUpdate();
      }
    }

    private final UUID serviceId = UUID.randomUUID();

    UUID insertAttendance() throws Exception {
      UUID professionalId;
      try (Statement statement = database.connection().createStatement();
          ResultSet result = statement.executeQuery("SELECT id FROM profissional LIMIT 1")) {
        result.next();
        professionalId = UUID.fromString(result.getString(1));
      }
      UUID attendanceId = UUID.randomUUID();
      try (PreparedStatement statement =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO atendimento (id,cliente_id,profissional_id,estado,subtotal_centavos,desconto_centavos,total_centavos,iniciado_em_utc,criado_por_usuario_id,criado_em_utc) VALUES (?,?,?,'EM_ANDAMENTO',1000,0,1000,?,?,?)")) {
        statement.setString(1, attendanceId.toString());
        statement.setString(2, clientId.toString());
        statement.setString(3, professionalId.toString());
        statement.setString(4, NOW.toString());
        statement.setString(5, adminId.toString());
        statement.setString(6, NOW.toString());
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO atendimento_servico (id,atendimento_id,servico_id,nome_snapshot,quantidade,preco_unitario_centavos,comissao_pontos_base) VALUES (?,?,?,'Serviço teste',1,1000,0)")) {
        statement.setString(1, UUID.randomUUID().toString());
        statement.setString(2, attendanceId.toString());
        statement.setString(3, serviceId.toString());
        statement.executeUpdate();
      }
      return attendanceId;
    }

    int scalar(String sql) throws Exception {
      try (Statement statement = database.connection().createStatement();
          ResultSet result = statement.executeQuery(sql)) {
        return result.next() ? result.getInt(1) : -1;
      }
    }

    void execute(String sql) throws Exception {
      try (Statement statement = database.connection().createStatement()) {
        statement.execute(sql);
      }
    }

    @Override
    public void close() throws Exception {
      database.close();
    }
  }
}
