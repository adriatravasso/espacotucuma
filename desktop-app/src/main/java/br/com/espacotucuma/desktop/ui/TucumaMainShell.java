package br.com.espacotucuma.desktop.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

/** Main application shell; module views are filled in by their individual delivery tasks. */
final class TucumaMainShell {

  private static final String UNAVAILABLE =
      "Disponível quando a área selecionada oferecer essa operação.";

  private final BorderPane root = new BorderPane();
  private final BorderPane workArea = new BorderPane();
  private final Label currentUser = new Label();
  private final Label pageTitle = new Label();
  private final Map<String, Button> moduleButtons = new LinkedHashMap<>();
  private Runnable logoutAction = () -> {};

  TucumaMainShell(String userName) {
    root.setTop(new VBox(createMenuBar(), createToolBar()));
    root.setLeft(createNavigation());
    root.setCenter(workArea);
    currentUser.setId("currentUser");
    currentUser.setPadding(new Insets(4, 8, 4, 8));
    root.setBottom(currentUser);
    showModule("Início");
    setUserName(userName);
    TucumaTheme.apply(root);
  }

  Parent root() {
    return root;
  }

  void setLogoutAction(Runnable action) {
    logoutAction = action;
  }

  void setUserName(String userName) {
    currentUser.setText("Usuário: " + userName);
  }

  private MenuBar createMenuBar() {
    MenuBar menuBar = new MenuBar();
    menuBar.setId("mainMenuBar");
    menuBar
        .getMenus()
        .addAll(
            menu(
                "Arquivo",
                item("Encerrar sessão", "menu-logout", () -> logoutAction.run()),
                new SeparatorMenuItem(),
                item("Sair", "menu-exit", () -> root.getScene().getWindow().hide())),
            menu(
                "Cadastros",
                moduleItem("Clientes", "menu-clients"),
                moduleItem("Profissionais", "menu-professionals"),
                moduleItem("Serviços", "menu-services"),
                moduleItem("Pacotes", "menu-packages")),
            menu(
                "Agenda",
                moduleItem("Agenda", "menu-agenda"),
                moduleItem("Atendimento", "menu-appointments")),
            menu(
                "Movimentações",
                moduleItem("Caixa", "menu-cash"),
                moduleItem("Estoque", "menu-inventory")),
            menu("Relatórios", moduleItem("Relatórios", "menu-reports")),
            menu("Ferramentas", disabledItem("Sincronizar (indisponível)", "menu-sync")),
            menu("Ajuda", item("Sobre", "menu-about", () -> showModule("Sobre"))));
    return menuBar;
  }

  private ToolBar createToolBar() {
    Button quickAgenda = new Button("Agenda");
    quickAgenda.setId("toolbar-agenda");
    quickAgenda.setOnAction(event -> showModule("Agenda"));

    Button logout = new Button("Encerrar sessão");
    logout.setId("toolbar-logout");
    logout.setOnAction(event -> logoutAction.run());

    ToolBar toolBar =
        new ToolBar(
            disabledButton("Novo", "command-new"),
            disabledButton("Editar", "command-edit"),
            disabledButton("Salvar", "command-save"),
            disabledButton("Cancelar", "command-cancel"),
            disabledButton("Imprimir", "command-print"),
            disabledButton(
                "Sincronizar",
                "command-sync",
                "A sincronização manual ainda não foi implementada."),
            quickAgenda,
            logout);
    toolBar.setId("commandToolbar");
    return toolBar;
  }

  private VBox createNavigation() {
    VBox navigation = new VBox(2);
    navigation.setId("moduleNavigation");
    navigation.setPadding(new Insets(8));
    navigation.setPrefWidth(174);
    navigation.getChildren().add(new Label("MÓDULOS"));
    for (String module :
        new String[] {
          "Início",
          "Clientes",
          "Profissionais",
          "Serviços",
          "Pacotes",
          "Agenda",
          "Atendimento",
          "Caixa",
          "Estoque",
          "Relatórios",
          "Administração"
        }) {
      String id = "module-" + moduleId(module);
      Button button = new Button(module);
      button.setId(id);
      button.setMaxWidth(Double.MAX_VALUE);
      button.setOnAction(event -> showModule(module));
      moduleButtons.put(module, button);
      navigation.getChildren().add(button);
    }
    return navigation;
  }

  private void showModule(String module) {
    pageTitle.setId("moduleTitle");
    pageTitle.setText(module);
    Label placeholder =
        new Label(
            module.equals("Início")
                ? "Selecione uma área de trabalho no menu ou na navegação lateral."
                : "Esta área será habilitada conforme a entrega do módulo.");
    placeholder.setId("moduleDescription");
    workArea.setTop(pageTitle);
    BorderPane.setMargin(pageTitle, new Insets(12, 14, 8, 14));
    workArea.setCenter(placeholder);
    BorderPane.setMargin(placeholder, new Insets(8, 14, 14, 14));
    moduleButtons.forEach((name, button) -> button.setDisable(name.equals(module)));
  }

  private MenuItem moduleItem(String module, String id) {
    return item(module, id, () -> showModule(module));
  }

  private static Menu menu(String text, MenuItem... items) {
    Menu menu = new Menu(text);
    menu.getItems().addAll(items);
    return menu;
  }

  private static MenuItem item(String text, String id, Runnable action) {
    MenuItem item = new MenuItem(text);
    item.setId(id);
    item.setOnAction(event -> action.run());
    return item;
  }

  private static MenuItem disabledItem(String text, String id) {
    MenuItem item = new MenuItem(text);
    item.setId(id);
    item.setDisable(true);
    return item;
  }

  private static Button disabledButton(String text, String id) {
    return disabledButton(text, id, UNAVAILABLE);
  }

  private static Button disabledButton(String text, String id, String reason) {
    Button button = new Button(text);
    button.setId(id);
    button.setDisable(true);
    button.setTooltip(new javafx.scene.control.Tooltip(reason));
    button.setAccessibleHelp(reason);
    return button;
  }

  private static String moduleId(String module) {
    return switch (module) {
      case "Início" -> "home";
      case "Clientes" -> "clients";
      case "Profissionais" -> "professionals";
      case "Serviços" -> "services";
      case "Pacotes" -> "packages";
      case "Agenda" -> "agenda";
      case "Atendimento" -> "appointments";
      case "Caixa" -> "cash";
      case "Estoque" -> "inventory";
      case "Relatórios" -> "reports";
      case "Administração" -> "administration";
      default -> module.toLowerCase(java.util.Locale.ROOT);
    };
  }
}
