package br.com.espacotucuma.desktop.application.prepaid;

import java.util.Objects;

public record PackagePageQuery(
    String searchText, boolean includeInactive, int pageIndex, int pageSize) {
  public PackagePageQuery {
    searchText = Objects.requireNonNull(searchText, "searchText").strip();
    if (pageIndex < 0 || pageSize < 1 || pageSize > 200) {
      throw new IllegalArgumentException("A página de pacotes é inválida.");
    }
  }
}
