package br.com.espacotucuma.desktop.ui.components;

import br.com.espacotucuma.desktop.ui.TucumaTheme;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

/** Compact two-column entry dialog with required fields and keyboard save/cancel behavior. */
public final class TucumaEntryDialog extends Dialog<Map<String, String>> {

  private final Map<String, TextField> fields = new LinkedHashMap<>();
  private final Map<String, String> labels = new LinkedHashMap<>();
  private final Map<String, String> initialValues = new LinkedHashMap<>();
  private final Map<String, Boolean> required = new LinkedHashMap<>();
  private final Label message = new Label();
  private boolean saving;
  private boolean discardConfirmed;

  public TucumaEntryDialog(
      Window owner,
      String title,
      Map<String, Boolean> fieldDefinitions,
      Map<String, String> initialValues) {
    Objects.requireNonNull(fieldDefinitions, "fieldDefinitions");
    Objects.requireNonNull(initialValues, "initialValues");
    this.initialValues.putAll(initialValues);
    setTitle("Tucumã — Gestão — " + title);
    setHeaderText(title);
    if (owner != null) initOwner(owner);

    ButtonType save = new ButtonType("Salvar", ButtonBar.ButtonData.OK_DONE);
    getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
    GridPane form = new GridPane();
    form.setAlignment(Pos.CENTER_LEFT);
    form.setHgap(10);
    form.setVgap(7);
    form.setPadding(new Insets(8));
    int row = 0;
    for (Map.Entry<String, Boolean> definition : fieldDefinitions.entrySet()) {
      String id = definition.getKey();
      boolean isRequired = Boolean.TRUE.equals(definition.getValue());
      String labelText = fieldLabel(id);
      TextField field = new TextField();
      field.setId("form-" + id);
      field.setAccessibleText(labelText + (isRequired ? ", campo obrigatório" : ""));
      if (isRequired) field.setAccessibleHelp("Campo obrigatório.");
      field.setText(initialValues.getOrDefault(id, ""));
      fields.put(id, field);
      labels.put(id, labelText);
      required.put(id, isRequired);
      form.addRow(row++, new Label(labelText + (isRequired ? " *" : "")), field);
    }
    message.setId("formMessage");
    message.setWrapText(true);
    message.setAccessibleHelp("Mensagens de validação e estado do formulário.");
    form.add(message, 0, row, 2, 1);
    getDialogPane().setContent(form);
    TucumaTheme.apply(getDialogPane());

    Node saveButton = getDialogPane().lookupButton(save);
    saveButton.addEventFilter(
        ActionEvent.ACTION,
        event -> {
          String invalid = firstMissingRequiredField();
          if (invalid != null) {
            setMessage("Informe o campo obrigatório: " + invalid + ".");
            fields.entrySet().stream()
                .filter(entry -> labels.get(entry.getKey()).equals(invalid))
                .findFirst()
                .ifPresent(entry -> entry.getValue().requestFocus());
            event.consume();
          } else {
            setMessage("");
            saving = true;
          }
        });
    Node cancelButton = getDialogPane().lookupButton(ButtonType.CANCEL);
    cancelButton.addEventFilter(
        ActionEvent.ACTION,
        event -> {
          if (!confirmDiscardIfChanged()) event.consume();
        });
    setOnCloseRequest(
        event -> {
          if (saving) {
            saving = false;
          } else if (discardConfirmed) {
            discardConfirmed = false;
          } else if (!confirmDiscardIfChanged()) {
            event.consume();
          }
        });
    getDialogPane()
        .addEventFilter(
            KeyEvent.KEY_PRESSED,
            event -> {
              if (event.isControlDown() && event.getCode() == KeyCode.S) {
                ((javafx.scene.control.Button) saveButton).fire();
                event.consume();
              } else if (event.getCode() == KeyCode.ENTER) {
                ((javafx.scene.control.Button) saveButton).fire();
                event.consume();
              } else if (event.getCode() == KeyCode.ESCAPE) {
                ((javafx.scene.control.Button) cancelButton).fire();
                event.consume();
              }
            });
    setResultConverter(button -> button == save ? values() : null);
  }

  private String firstMissingRequiredField() {
    return fields.entrySet().stream()
        .filter(entry -> required.get(entry.getKey()))
        .filter(entry -> entry.getValue().getText().isBlank())
        .map(entry -> labels.get(entry.getKey()))
        .findFirst()
        .orElse(null);
  }

  private void setMessage(String text) {
    message.setText(text);
    message.setAccessibleText(text);
  }

  private boolean confirmDiscardIfChanged() {
    boolean changed =
        fields.entrySet().stream()
            .anyMatch(
                entry -> !Objects.equals(entry.getValue().getText(), initialText(entry.getKey())));
    if (!changed) return true;

    ButtonType discard = new ButtonType("Descartar", ButtonBar.ButtonData.YES);
    ButtonType keepEditing = new ButtonType("Continuar editando", ButtonBar.ButtonData.NO);
    Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
    confirmation.setTitle("Tucumã — Gestão");
    confirmation.setHeaderText("Descartar alterações não salvas?");
    confirmation.setContentText("O que foi digitado neste formulário será perdido.");
    confirmation.getButtonTypes().setAll(keepEditing, discard);
    if (getOwner() != null) confirmation.initOwner(getOwner());
    TucumaTheme.apply(confirmation.getDialogPane());
    confirmation
        .getDialogPane()
        .lookupButton(keepEditing)
        .setAccessibleHelp("Fecha este aviso e mantém o formulário aberto.");
    confirmation
        .getDialogPane()
        .lookupButton(discard)
        .setAccessibleHelp("Fecha o formulário sem salvar.");
    boolean confirmed = confirmation.showAndWait().filter(discard::equals).isPresent();
    discardConfirmed = confirmed;
    return confirmed;
  }

  private String initialText(String id) {
    return initialValues.getOrDefault(id, "");
  }

  private Map<String, String> values() {
    Map<String, String> values = new LinkedHashMap<>();
    fields.forEach((key, field) -> values.put(key, field.getText().trim()));
    return Map.copyOf(values);
  }

  private String fieldLabel(String id) {
    String label = id.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
    return label.isEmpty() ? id : Character.toUpperCase(label.charAt(0)) + label.substring(1);
  }
}
