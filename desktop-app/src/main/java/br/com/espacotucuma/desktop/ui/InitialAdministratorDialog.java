package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import java.util.Arrays;
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

final class InitialAdministratorDialog {

  private InitialAdministratorDialog() {}

  static boolean showAndCreate(Window owner, CreateFirstAdministratorUseCase setupUseCase) {
    Dialog<Boolean> dialog = new Dialog<>();
    dialog.setTitle("Configuração inicial — Tucumã Gestão");
    dialog.setHeaderText("Defina o estabelecimento e a conta do primeiro administrador.");
    if (owner != null && owner.getScene() != null) {
      dialog.initOwner(owner);
    }

    ButtonType createButton = new ButtonType("Criar administrador", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(createButton, ButtonType.CANCEL);
    TucumaTheme.apply(dialog.getDialogPane());

    TextField establishmentName = new TextField();
    TextField administratorName = new TextField();
    TextField login = new TextField();
    PasswordField password = new PasswordField();
    PasswordField confirmation = new PasswordField();
    Label message = new Label("Use uma senha com pelo menos 12 caracteres.");
    establishmentName.setId("establishmentName");
    administratorName.setId("administratorName");
    login.setId("administratorLogin");
    password.setId("administratorPassword");
    confirmation.setId("passwordConfirmation");
    message.setId("setupMessage");
    message.setWrapText(true);

    GridPane form = new GridPane();
    form.setAlignment(Pos.CENTER_LEFT);
    form.setHgap(10);
    form.setVgap(8);
    form.setPadding(new Insets(8));
    form.addRow(0, new Label("Estabelecimento:"), establishmentName);
    form.addRow(1, new Label("Nome do administrador:"), administratorName);
    form.addRow(2, new Label("Login:"), login);
    form.addRow(3, new Label("Senha:"), password);
    form.addRow(4, new Label("Confirme a senha:"), confirmation);
    form.add(message, 0, 5, 2, 1);
    dialog.getDialogPane().setContent(form);

    Node create = dialog.getDialogPane().lookupButton(createButton);
    create.addEventFilter(
        ActionEvent.ACTION,
        event -> {
          char[] firstPassword = password.getText().toCharArray();
          char[] confirmedPassword = confirmation.getText().toCharArray();
          if (!Arrays.equals(firstPassword, confirmedPassword)) {
            message.setText("As senhas não correspondem.");
            event.consume();
          } else {
            try {
              setupUseCase.create(
                  new InitialAdministratorSetup(
                      establishmentName.getText(),
                      administratorName.getText(),
                      login.getText(),
                      firstPassword));
            } catch (IllegalArgumentException | IllegalStateException failure) {
              message.setText(failure.getMessage());
              event.consume();
            }
          }
          Arrays.fill(firstPassword, '\0');
          Arrays.fill(confirmedPassword, '\0');
          password.clear();
          confirmation.clear();
        });
    dialog.setResultConverter(button -> button == createButton);
    return dialog.showAndWait().orElse(false);
  }
}
