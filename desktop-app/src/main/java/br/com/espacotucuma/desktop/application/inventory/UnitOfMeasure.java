package br.com.espacotucuma.desktop.application.inventory;

public enum UnitOfMeasure {
  UNIDADE("Unidade", true),
  MILILITRO("Mililitro", false),
  GRAMA("Grama", false),
  PACOTE("Pacote", true);

  private final String label;
  private final boolean integral;

  UnitOfMeasure(String label, boolean integral) {
    this.label = label;
    this.integral = integral;
  }

  public String label() {
    return label;
  }

  public boolean integral() {
    return integral;
  }
}
