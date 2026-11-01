package br.com.espacotucuma.desktop.application.client;

/** Minimal versioned JSON payload shared by the local client mutations. */
final class ClientPayload {
  private ClientPayload() {}

  static String json(Client client) {
    return "{\"id\":\""
        + client.id()
        + "\",\"codigoInterno\":\""
        + escape(client.internalCode())
        + "\",\"nome\":\""
        + escape(client.name())
        + "\",\"telefone\":"
        + nullable(client.phone())
        + ",\"contatoOpcional\":"
        + nullable(client.optionalContact())
        + ",\"preferencias\":"
        + nullable(client.preferences())
        + ",\"ativo\":"
        + client.active()
        + ",\"criadoEmUtc\":\""
        + client.createdAt()
        + "\",\"atualizadoEmUtc\":\""
        + client.updatedAt()
        + "\"}";
  }

  private static String nullable(String value) {
    return value == null ? "null" : "\"" + escape(value) + "\"";
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
