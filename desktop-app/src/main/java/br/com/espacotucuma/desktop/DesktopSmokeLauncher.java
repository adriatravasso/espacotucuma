package br.com.espacotucuma.desktop;

import javafx.application.Application;
import br.com.espacotucuma.desktop.ui.TucumaDesktopApplication;

public final class DesktopSmokeLauncher {

    private DesktopSmokeLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(TucumaDesktopApplication.class, args);
    }
}
