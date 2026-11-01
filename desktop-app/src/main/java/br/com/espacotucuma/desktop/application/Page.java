package br.com.espacotucuma.desktop.application;

import java.util.List;

/** Immutable result page returned by local repositories. */
public record Page<T>(List<T> items, long totalItems, int pageIndex, int pageSize) {
  public Page {
    items = List.copyOf(items);
    if (totalItems < 0 || pageIndex < 0 || pageSize < 1) {
      throw new IllegalArgumentException("Os dados da página são inválidos.");
    }
  }

  public int pageCount() {
    return totalItems == 0 ? 0 : (int) ((totalItems + pageSize - 1) / pageSize);
  }
}
