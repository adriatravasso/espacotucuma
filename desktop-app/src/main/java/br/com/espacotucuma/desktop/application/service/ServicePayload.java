package br.com.espacotucuma.desktop.application.service;

public final class ServicePayload {
  private ServicePayload() {}

  public static String json(BeautyService service) {
    return "{\"id\":\""
        + service.id()
        + "\",\"nome\":\""
        + escape(service.name())
        + "\",\"categoria\":\""
        + escape(service.category())
        + "\",\"duracaoMinutos\":"
        + service.durationMinutes()
        + ",\"precoCentavos\":"
        + service.price().amount().movePointRight(2).longValueExact()
        + ",\"comissaoPontosBase\":"
        + service.commissionBasisPoints()
        + ",\"ativo\":"
        + service.active()
        + ",\"criadoEmUtc\":\""
        + service.createdAt()
        + "\",\"atualizadoEmUtc\":\""
        + service.updatedAt()
        + "\"}";
  }

  static String escape(String value) {
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
