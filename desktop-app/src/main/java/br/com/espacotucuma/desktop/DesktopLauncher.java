package br.com.espacotucuma.desktop;

import br.com.espacotucuma.desktop.ui.TucumaDesktopApplication;

/** Starts JavaFX through a regular main class so the packaged launcher can load its runtime. */
public final class DesktopLauncher {

  private DesktopLauncher() {}

  public static void main(String[] args) {
    TucumaDesktopApplication.main(args);
  }
}
