package br.com.espacotucuma.desktop.application.service;

public record ServicePageQuery(
    String searchText,
    String category,
    boolean includeInactive,
    ServiceSortField sortField,
    boolean ascending,
    int pageIndex,
    int pageSize) {
  public ServicePageQuery {
    searchText = searchText == null ? "" : searchText.strip();
    category = category == null ? "ALL" : category.strip();
    if (category.isEmpty()) category = "ALL";
    if (pageIndex < 0 || pageSize < 1 || pageSize > 250) {
      throw new IllegalArgumentException("A paginação do catálogo é inválida.");
    }
    if (sortField == null) sortField = ServiceSortField.NAME;
  }
}
