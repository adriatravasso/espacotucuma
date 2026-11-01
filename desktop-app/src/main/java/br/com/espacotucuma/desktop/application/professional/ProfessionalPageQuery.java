package br.com.espacotucuma.desktop.application.professional;

public record ProfessionalPageQuery(
    String searchText, boolean includeInactive, int pageIndex, int pageSize) {
  public ProfessionalPageQuery {
    searchText = searchText == null ? "" : searchText.strip();
    if (pageIndex < 0 || pageSize < 1 || pageSize > 250) {
      throw new IllegalArgumentException("A paginação de profissionais é inválida.");
    }
  }
}
