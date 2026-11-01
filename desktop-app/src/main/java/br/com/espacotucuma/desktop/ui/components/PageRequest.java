package br.com.espacotucuma.desktop.ui.components;

public record PageRequest(
    String searchText,
    String filter,
    String sortBy,
    boolean ascending,
    int pageIndex,
    int pageSize) {}
