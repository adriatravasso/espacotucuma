package br.com.espacotucuma.desktop.application.client;

import java.util.Objects;

public record ClientPageQuery(
    String searchText,
    ClientContactFilter contactFilter,
    ClientSortField sortField,
    boolean ascending,
    int pageIndex,
    int pageSize,
    boolean includeInactive) {

  public ClientPageQuery(
      String searchText,
      ClientContactFilter contactFilter,
      ClientSortField sortField,
      boolean ascending,
      int pageIndex,
      int pageSize) {
    this(searchText, contactFilter, sortField, ascending, pageIndex, pageSize, false);
  }

  public ClientPageQuery {
    searchText = searchText == null ? "" : searchText.trim();
    Objects.requireNonNull(contactFilter, "contactFilter");
    Objects.requireNonNull(sortField, "sortField");
    if (pageIndex < 0) throw new IllegalArgumentException("A página não pode ser negativa.");
    if (pageSize < 1 || pageSize > 100) {
      throw new IllegalArgumentException("O tamanho da página deve estar entre 1 e 100.");
    }
  }
}
