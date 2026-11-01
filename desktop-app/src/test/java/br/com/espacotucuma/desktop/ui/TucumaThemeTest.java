package br.com.espacotucuma.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TucumaThemeTest {

  @Test
  void packagedStylesheetDefinesClassicControlsAndVisibleKeyboardFocus() throws IOException {
    var stylesheet = TucumaTheme.class.getResource("tucuma-classic.css");
    assertNotNull(stylesheet, "A folha visual deve estar disponível no classpath.");
    String css;
    try (var input = stylesheet.openStream()) {
      css = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    for (String invariant :
        new String[] {
          ".root {",
          ".dialog-pane",
          ".button:focused",
          ".text-field:focused",
          ".tool-bar",
          ".tab-pane",
          ".titled-pane",
          ".table-view",
          "#2f6f9f",
          "#ffffff"
        }) {
      assertTrue(css.contains(invariant), "CSS deve conter " + invariant);
    }
  }
}
