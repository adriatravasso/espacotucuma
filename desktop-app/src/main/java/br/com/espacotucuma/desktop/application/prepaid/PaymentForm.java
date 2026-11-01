package br.com.espacotucuma.desktop.application.prepaid;

public enum PaymentForm {
  DINHEIRO("Dinheiro"),
  PIX("Pix"),
  CARTAO("Cartão");

  private final String label;

  PaymentForm(String label) {
    this.label = label;
  }

  @Override
  public String toString() {
    return label;
  }
}
