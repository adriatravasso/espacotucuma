package br.com.espacotucuma.desktop.application.professional;

import java.time.format.DateTimeFormatter;

public final class ProfessionalPayload {
  private ProfessionalPayload() {}

  public static String json(Professional professional) {
    StringBuilder json =
        new StringBuilder("{\"id\":\"")
            .append(professional.id())
            .append("\",\"nome\":\"")
            .append(escape(professional.name()))
            .append("\",\"telefone\":")
            .append(
                professional.phone() == null ? "null" : "\"" + escape(professional.phone()) + "\"")
            .append(",\"ativo\":")
            .append(professional.active())
            .append(",\"criadoEmUtc\":\"")
            .append(professional.createdAt())
            .append("\",\"atualizadoEmUtc\":\"")
            .append(professional.updatedAt())
            .append("\"")
            .append(",\"disponibilidades\":[");
    for (int i = 0; i < professional.schedule().weeklyIntervals().size(); i++) {
      WeeklyInterval interval = professional.schedule().weeklyIntervals().get(i);
      if (i > 0) json.append(',');
      json.append("{\"dia\":")
          .append(interval.day().getValue() % 7)
          .append(",\"inicio\":\"")
          .append(interval.startsAt())
          .append("\",\"fim\":\"")
          .append(interval.endsAt())
          .append("\"}");
    }
    json.append("],\"indisponibilidades\":[");
    DateTimeFormatter local = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    for (int i = 0; i < professional.schedule().unavailabilities().size(); i++) {
      OneOffUnavailability block = professional.schedule().unavailabilities().get(i);
      if (i > 0) json.append(',');
      json.append("{\"inicio\":\"")
          .append(local.format(block.startsAt()))
          .append("\",\"fim\":\"")
          .append(local.format(block.endsAt()))
          .append("\",\"motivo\":")
          .append(block.reason() == null ? "null" : "\"" + escape(block.reason()) + "\"")
          .append('}');
    }
    return json.append("]}").toString();
  }

  private static String escape(String value) {
    StringBuilder escaped = new StringBuilder();
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
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
}
