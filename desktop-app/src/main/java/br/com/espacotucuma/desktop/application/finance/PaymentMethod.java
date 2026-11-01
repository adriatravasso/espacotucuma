package br.com.espacotucuma.desktop.application.finance;

public enum PaymentMethod {
  DINHEIRO("Dinheiro"),
  PIX("Pix"),
  CARTAO("Cartão");

  private final String label;

  PaymentMethod(String label) {
    this.label = label;
  }

  @Override
  public String toString() {
    return label;
  }
}
