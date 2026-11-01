package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.application.commission.CommissionQuote;
import br.com.espacotucuma.desktop.application.commission.CommissionService;
import br.com.espacotucuma.desktop.application.commission.MaintainCommissionRuleUseCase;
import br.com.espacotucuma.desktop.application.commission.ServiceCommissionRule;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.port.commission.CommissionRuleRepository;
import br.com.espacotucuma.desktop.application.port.professional.ProfessionalRepository;
import br.com.espacotucuma.desktop.application.service.BeautyService;
import br.com.espacotucuma.desktop.domain.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

final class CommissionPreviewDialog {
  private final BeautyService service;
  private final CommissionService commissions;
  private final MaintainCommissionRuleUseCase maintain;
  private final CommissionRuleRepository rules;
  private final ProfessionalRepository professionals;
  private final AuthorizationService authorization;
  private final Dialog<Void> dialog = new Dialog<>();
  private final ComboBox<br.com.espacotucuma.desktop.application.professional.Professional>
      professional = new ComboBox<>();
  private final TextField discount = new TextField("0,00");
  private final TextField overridePercent = new TextField();
  private final Label estimate = new Label();
  private final Button setRule = new Button("Salvar regra específica");
  private final Button clearRule = new Button("Usar padrão do serviço");

  CommissionPreviewDialog(
      Window owner,
      BeautyService service,
      CommissionService commissions,
      MaintainCommissionRuleUseCase maintain,
      CommissionRuleRepository rules,
      ProfessionalRepository professionals,
      AuthorizationService authorization) {
    this.service = service;
    this.commissions = commissions;
    this.maintain = maintain;
    this.rules = rules;
    this.professionals = professionals;
    this.authorization = authorization;
    build(owner);
  }

  void showAndWait() {
    dialog.showAndWait();
  }

  private void build(Window owner) {
    dialog.setTitle("Comissão prevista — " + service.name());
    if (owner != null) dialog.initOwner(owner);
    dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
    dialog
        .getDialogPane()
        .lookupButton(javafx.scene.control.ButtonType.CLOSE)
        .setId("commissionPreviewClose");
    professional.setId("commissionProfessional");
    professional.getItems().setAll(professionals.findActiveForScheduling());
    professional.setConverter(
        new javafx.util.StringConverter<>() {
          @Override
          public String toString(
              br.com.espacotucuma.desktop.application.professional.Professional value) {
            return value == null ? "" : value.name();
          }

          @Override
          public br.com.espacotucuma.desktop.application.professional.Professional fromString(
              String value) {
            return null;
          }
        });
    if (!professional.getItems().isEmpty()) professional.getSelectionModel().selectFirst();
    discount.setId("commissionDiscount");
    overridePercent.setId("commissionOverridePercent");
    estimate.setId("commissionEstimate");
    estimate.setWrapText(true);
    setRule.setId("commissionSetRule");
    clearRule.setId("commissionResetRule");
    boolean canManage = authorization.isAllowed(Permission.COMMISSION_RULE_MANAGE);
    overridePercent.setEditable(canManage);
    overridePercent.setPromptText("Ex.: 25,00");
    setRule.setVisible(canManage);
    setRule.setManaged(canManage);
    clearRule.setVisible(canManage);
    clearRule.setManaged(canManage);

    GridPane content = new GridPane();
    content.setHgap(8);
    content.setVgap(8);
    content.setPadding(new Insets(10));
    content.addRow(0, new Label("Serviço"), new Label(service.name()));
    content.addRow(1, new Label("Valor atual"), new Label(format(service.price())));
    content.addRow(2, new Label("Profissional"), professional);
    content.addRow(3, new Label("Desconto aplicado (R$)"), discount);
    content.addRow(4, new Label("Comissão específica (%)"), overridePercent);
    content.addRow(5, setRule, clearRule);
    content.add(estimate, 0, 6, 2, 1);
    dialog.getDialogPane().setContent(content);
    dialog.getDialogPane().setPrefWidth(480);
    TucumaTheme.apply(dialog.getDialogPane());

    professional
        .valueProperty()
        .addListener((observable, oldValue, newValue) -> loadRuleAndPreview());
    discount.textProperty().addListener((observable, oldValue, newValue) -> renderPreview());
    setRule.setOnAction(event -> saveRule());
    clearRule.setOnAction(event -> removeRule());
    loadRuleAndPreview();
  }

  private void loadRuleAndPreview() {
    var selected = professional.getValue();
    ServiceCommissionRule rule =
        selected == null
            ? null
            : rules.findByProfessionalAndService(selected.id(), service.id()).orElse(null);
    overridePercent.setText(
        rule == null
            ? ""
            : BigDecimal.valueOf(rule.percentageBasisPoints(), 2)
                .toPlainString()
                .replace('.', ','));
    clearRule.setDisable(rule == null);
    renderPreview();
  }

  private void saveRule() {
    var selected = professional.getValue();
    if (selected == null) return;
    try {
      int basisPoints = parsePercent(overridePercent.getText());
      maintain.setRule(selected.id(), service.id(), basisPoints);
      loadRuleAndPreview();
    } catch (RuntimeException invalid) {
      showError("Não foi possível salvar a regra de comissão.", invalid.getMessage());
    }
  }

  private void removeRule() {
    var selected = professional.getValue();
    if (selected == null) return;
    rules
        .findByProfessionalAndService(selected.id(), service.id())
        .ifPresent(
            rule -> {
              try {
                maintain.deactivate(rule.id());
                loadRuleAndPreview();
              } catch (RuntimeException failure) {
                showError("Não foi possível remover a regra específica.", failure.getMessage());
              }
            });
  }

  private void renderPreview() {
    var selected = professional.getValue();
    if (selected == null) {
      estimate.setText("Cadastre um profissional ativo para calcular a comissão prevista.");
      setRule.setDisable(true);
      clearRule.setDisable(true);
      return;
    }
    try {
      Money discountAmount = parseMoney(discount.getText());
      CommissionQuote quote =
          commissions.preview(
              selected.id(),
              service.id(),
              service.commissionBasisPoints(),
              service.price(),
              discountAmount);
      String source =
          quote.decision().source()
                  == br.com.espacotucuma.desktop.application.commission.CommissionDecision.Source
                      .SERVICE
              ? "padrão do serviço"
              : "regra específica do profissional e serviço";
      estimate.setText(
          "Comissão prevista: "
              + format(quote.commission())
              + " ("
              + BigDecimal.valueOf(quote.decision().percentageBasisPoints(), 2)
                  .stripTrailingZeros()
                  .toPlainString()
              + "%, "
              + source
              + "). O desconto reduz a base de cálculo.");
      setRule.setDisable(!authorization.isAllowed(Permission.COMMISSION_RULE_MANAGE));
      clearRule.setDisable(
          !authorization.isAllowed(Permission.COMMISSION_RULE_MANAGE)
              || rules.findByProfessionalAndService(selected.id(), service.id()).isEmpty());
    } catch (RuntimeException invalid) {
      estimate.setText(invalid.getMessage());
      setRule.setDisable(true);
    }
  }

  private Money parseMoney(String value) {
    try {
      return new Money(
          new BigDecimal(value.strip().replace(',', '.')).setScale(2, RoundingMode.UNNECESSARY));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Informe o desconto com até duas casas decimais.");
    }
  }

  private int parsePercent(String value) {
    try {
      return new BigDecimal(value.strip().replace(',', '.'))
          .setScale(2, RoundingMode.UNNECESSARY)
          .movePointRight(2)
          .intValueExact();
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Informe um percentual entre 0 e 100%.");
    }
  }

  private String format(Money money) {
    return NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(money.amount());
  }

  private void showError(String header, String detail) {
    Alert alert = new Alert(Alert.AlertType.ERROR);
    alert.setTitle("Tucumã — Gestão");
    alert.setHeaderText(header);
    alert.setContentText(detail);
    TucumaTheme.apply(alert.getDialogPane());
    alert.showAndWait();
  }
}
