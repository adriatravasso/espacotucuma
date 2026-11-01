package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.identity.AuthenticatedSession;
import br.com.espacotucuma.desktop.application.identity.LoginUseCase;
import java.util.Optional;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

final class LocalLoginDialog {
  private LocalLoginDialog() {}

  static Optional<AuthenticatedSession> show(Window owner, LoginUseCase loginUseCase) {
    Dialog<AuthenticatedSession> dialog = new Dialog<>();
    dialog.setTitle("Login local — Tucumã Gestão");
    dialog.setHeaderText("Entre com sua conta local.");
    if (owner != null && owner.getScene() != null) dialog.initOwner(owner);
    ButtonType loginButton = new ButtonType("Entrar", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(loginButton, ButtonType.CANCEL);
    TucumaTheme.apply(dialog.getDialogPane());
    TextField login = new TextField();
    PasswordField password = new PasswordField();
    Label message = new Label("A autenticação funciona sem internet.");
    login.setId("login");
    password.setId("password");
    message.setId("loginMessage");
    GridPane form = new GridPane();
    form.setAlignment(Pos.CENTER_LEFT);
    form.setHgap(10);
    form.setVgap(8);
    form.setPadding(new Insets(8));
    form.addRow(0, new Label("Login:"), login);
    form.addRow(1, new Label("Senha:"), password);
    form.add(message, 0, 2, 2, 1);
    dialog.getDialogPane().setContent(form);
    final AuthenticatedSession[] authenticated = new AuthenticatedSession[1];
    Node submit = dialog.getDialogPane().lookupButton(loginButton);
    submit.addEventFilter(
        ActionEvent.ACTION,
        event -> {
          char[] secret = password.getText().toCharArray();
          try {
            Optional<AuthenticatedSession> result = loginUseCase.login(login.getText(), secret);
            if (result.isEmpty()) {
              message.setText("Login ou senha inválidos.");
              event.consume();
            } else {
              authenticated[0] = result.get();
            }
          } catch (RuntimeException failure) {
            message.setText("Não foi possível autenticar no banco local.");
            event.consume();
          } finally {
            password.clear();
          }
        });
    dialog.setResultConverter(button -> button == loginButton ? authenticated[0] : null);
    return dialog.showAndWait();
  }
}
