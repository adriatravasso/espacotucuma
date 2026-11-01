package br.com.espacotucuma.desktop.application.inventory;

public record ProductPageQuery(
    String searchText,
    String category,
    boolean includeInactive,
    boolean belowMinimumOnly,
    ProductSortField sortField,
    boolean ascending,
    int pageIndex,
    int pageSize) {
  public ProductPageQuery {
    searchText = searchText == null ? "" : searchText.strip();
    category = category == null ? "ALL" : category.strip();
    if (category.isEmpty()) category = "ALL";
    if (pageIndex < 0 || pageSize < 1 || pageSize > 250)
      throw new IllegalArgumentException("A paginação do estoque é inválida.");
    if (sortField == null) sortField = ProductSortField.NAME;
  }
}
