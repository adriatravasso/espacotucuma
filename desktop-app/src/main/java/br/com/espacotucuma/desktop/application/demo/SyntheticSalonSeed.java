package br.com.espacotucuma.desktop.application.demo;

import br.com.espacotucuma.desktop.application.port.clock.TimeProvider;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SyntheticSalonSeed {
    public static final ZoneId ZONE = ZoneId.of("America/Manaus");
    private static final String SEED_NAMESPACE = "espaco-tucuma-demo-v1:";

    private SyntheticSalonSeed() {
    }

    public static List<DemoRecord> create(TimeProvider timeProvider) {
        Instant now = timeProvider.now();
        LocalDate today = now.atZone(ZONE).toLocalDate();
        List<DemoRecord> records = new ArrayList<>();

        add(records, "professional-01", DemoRecord.Kind.PROFESSIONAL, "Profissional Demonstração 01", now,
                Map.of("role", "manicure", "schedule", "08:00-16:00"));
        add(records, "professional-02", DemoRecord.Kind.PROFESSIONAL, "Profissional Demonstração 02", now,
                Map.of("role", "design de sobrancelhas", "schedule", "10:00-18:00"));

        add(records, "client-01", DemoRecord.Kind.CLIENT, "Cliente Demonstração 01", now,
                Map.of("note", "Preferência fictícia: atendimento pela manhã"));
        add(records, "client-02", DemoRecord.Kind.CLIENT, "Cliente Demonstração 02", now,
                Map.of("note", "Cadastro sintético para demonstração"));
        add(records, "client-03", DemoRecord.Kind.CLIENT, "Cliente Demonstração 03", now,
                Map.of("note", "Cadastro sintético para demonstração"));

        add(records, "service-01", DemoRecord.Kind.SERVICE, "Manicure", now,
                Map.of("durationMinutes", "45", "price", "45.00", "commissionPercent", "30"));
        add(records, "service-02", DemoRecord.Kind.SERVICE, "Pedicure", now,
                Map.of("durationMinutes", "60", "price", "60.00", "commissionPercent", "30"));
        add(records, "service-03", DemoRecord.Kind.SERVICE, "Design de sobrancelhas", now,
                Map.of("durationMinutes", "40", "price", "55.00", "commissionPercent", "35"));
        add(records, "service-04", DemoRecord.Kind.SERVICE, "Limpeza de pele", now,
                Map.of("durationMinutes", "75", "price", "130.00", "commissionPercent", "35"));

        Instant morningAppointment = today.atTime(LocalTime.of(9, 0)).atZone(ZONE).toInstant();
        Instant afternoonAppointment = today.atTime(LocalTime.of(14, 0)).atZone(ZONE).toInstant();
        add(records, "appointment-01", DemoRecord.Kind.APPOINTMENT, "Agendamento Demonstração 01",
                morningAppointment, Map.of("localTime", today + "T09:00", "status", "confirmado",
                        "clientId", id("client-01").toString(), "serviceId", id("service-01").toString(),
                        "professionalId", id("professional-01").toString()));
        add(records, "appointment-02", DemoRecord.Kind.APPOINTMENT, "Agendamento Demonstração 02",
                afternoonAppointment, Map.of("localTime", today + "T14:00", "status", "agendado",
                        "clientId", id("client-02").toString(), "serviceId", id("service-03").toString(),
                        "professionalId", id("professional-02").toString()));

        add(records, "product-01", DemoRecord.Kind.PRODUCT, "Esmalte demonstração", now,
                Map.of("unit", "unidade", "quantity", "24", "minimum", "8"));
        add(records, "product-02", DemoRecord.Kind.PRODUCT, "Algodão demonstração", now,
                Map.of("unit", "pacote", "quantity", "12", "minimum", "4"));
        add(records, "product-03", DemoRecord.Kind.PRODUCT, "Loção demonstração", now,
                Map.of("unit", "frasco", "quantity", "6", "minimum", "2"));

        add(records, "movement-01", DemoRecord.Kind.STOCK_MOVEMENT, "Entrada de esmaltes", now,
                Map.of("movement", "entrada", "productId", id("product-01").toString(), "quantity", "10"));
        add(records, "movement-02", DemoRecord.Kind.STOCK_MOVEMENT, "Consumo de algodão", now,
                Map.of("movement", "consumo", "productId", id("product-02").toString(), "quantity", "2"));
        add(records, "movement-03", DemoRecord.Kind.STOCK_MOVEMENT, "Ajuste de loção", now,
                Map.of("movement", "ajuste", "productId", id("product-03").toString(), "quantity", "-1",
                        "reason", "ajuste fictício de demonstração"));

        return List.copyOf(records);
    }

    private static void add(List<DemoRecord> records, String key, DemoRecord.Kind kind, String name,
                            Instant createdAt, Map<String, String> attributes) {
        records.add(new DemoRecord(id(key), kind, name, attributes, createdAt));
    }

    private static UUID id(String key) {
        return UUID.nameUUIDFromBytes((SEED_NAMESPACE + key).getBytes(StandardCharsets.UTF_8));
    }
}
