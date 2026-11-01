package br.com.espacotucuma.sync.contract;

public final class UnsupportedContractVersionException extends RuntimeException {
  public UnsupportedContractVersionException() {
    super("Unsupported contract version");
  }
}
