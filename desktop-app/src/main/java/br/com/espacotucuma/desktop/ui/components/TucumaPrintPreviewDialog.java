package br.com.espacotucuma.desktop.ui.components;

import br.com.espacotucuma.desktop.ui.TucumaTheme;
import java.util.Objects;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.print.PrinterJob;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Branded, printer-independent preview for a local report or table result. */
public final class TucumaPrintPreviewDialog extends Dialog<Void> {

  private final VBox printableContent = new VBox(8);
  private final Label feedback = new Label();

  public TucumaPrintPreviewDialog(Window owner, String documentTitle, String documentText) {
    Objects.requireNonNull(documentTitle, "documentTitle");
    Objects.requireNonNull(documentText, "documentText");
    setTitle("Tucumã — Gestão — Prévia de impressão");
    setHeaderText("Prévia de impressão");
    if (owner != null) initOwner(owner);
    ButtonType print = new ButtonType("Imprimir", ButtonBar.ButtonData.OTHER);
    getDialogPane().getButtonTypes().addAll(print, ButtonType.CLOSE);

    Label identity = new Label("ESPAÇO TUCUMÃ — SISTEMA DE GESTÃO");
    identity.setId("printIdentity");
    identity.getStyleClass().add("print-identity");
    Label title = new Label(documentTitle);
    title.setId("printPreviewTitle");
    title.getStyleClass().add("print-document-title");
    TextArea text = new TextArea(documentText);
    text.setId("printPreviewContent");
    text.setEditable(false);
    text.setWrapText(false);
    text.setPrefRowCount(20);
    text.setPrefColumnCount(72);
    text.getStyleClass().add("print-document-content");
    feedback.setId("printPreviewMessage");
    feedback.setWrapText(true);
    printableContent.setPadding(new Insets(18));
    printableContent.getStyleClass().add("print-paper");
    printableContent.getChildren().addAll(identity, title, text);
    ScrollPane page = new ScrollPane(printableContent);
    page.setFitToWidth(true);
    page.setPrefViewportWidth(670);
    page.setPrefViewportHeight(430);
    VBox content = new VBox(6, page, feedback);
    getDialogPane().setContent(content);
    getDialogPane().setPrefWidth(720);
    getDialogPane().setPrefHeight(520);
    getDialogPane()
        .lookupButton(print)
        .setAccessibleHelp("Abre a impressão do documento exibido na prévia.");
    Node printButton = getDialogPane().lookupButton(print);
    printButton.addEventFilter(
        ActionEvent.ACTION,
        event -> {
          event.consume();
          print(owner);
        });
    TucumaTheme.apply(getDialogPane());
    setResultConverter(button -> null);
  }

  private void print(Window owner) {
    PrinterJob job;
    try {
      job = PrinterJob.createPrinterJob();
    } catch (RuntimeException printerFailure) {
      job = null;
    }
    if (job == null) {
      feedback.setText("Nenhuma impressora está disponível. A prévia continua aberta.");
      return;
    }
    if (!job.showPrintDialog(owner)) return;
    if (job.printPage(printableContent)) {
      job.endJob();
      feedback.setText("Documento enviado para impressão.");
    } else {
      feedback.setText("Não foi possível imprimir. A prévia continua aberta.");
      job.cancelJob();
    }
  }
}
