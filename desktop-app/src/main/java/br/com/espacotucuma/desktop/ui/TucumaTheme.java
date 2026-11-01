package br.com.espacotucuma.desktop.ui;

import java.net.URL;
import java.util.Objects;
import javafx.scene.Parent;

/** Applies the shared JavaFX stylesheet to a screen or dialog root. */
public final class TucumaTheme {
  private TucumaTheme() {}

  public static void apply(Parent root) {
    Objects.requireNonNull(root, "root");
    URL stylesheet =
        Objects.requireNonNull(
            TucumaTheme.class.getResource("tucuma-classic.css"),
            "A folha visual do Tucumã não foi empacotada.");
    String location = stylesheet.toExternalForm();
    if (!root.getStylesheets().contains(location)) {
      root.getStylesheets().add(location);
    }
  }
}
