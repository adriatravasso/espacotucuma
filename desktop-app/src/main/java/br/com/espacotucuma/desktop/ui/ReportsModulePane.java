package br.com.espacotucuma.desktop.ui;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;

/** Groups commercial reports and the existing commission statement under the reports module. */
public final class ReportsModulePane extends BorderPane {
  private final CommercialReportsPane commercialReports;
  private final CommissionStatementPane commissionStatement;

  public ReportsModulePane(
      CommercialReportsPane commercialReports, CommissionStatementPane commissionStatement) {
    this.commercialReports = commercialReports;
    this.commissionStatement = commissionStatement;
    TabPane tabs = new TabPane();
    tabs.getTabs()
        .setAll(
            new Tab("Relatórios comerciais", commercialReports),
            new Tab("Comissões", commissionStatement));
    tabs.getTabs().forEach(tab -> tab.setClosable(false));
    setCenter(tabs);
    TucumaTheme.apply(this);
  }

  public void refresh() {
    commercialReports.refresh();
    commissionStatement.refresh();
  }

  public void close() {
    commercialReports.close();
  }
}
