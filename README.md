# Espaço Tucumã

Aplicação de gestão para beleza e estética, com operação desktop local e sincronização remota iniciada manualmente.

## Estrutura

- `desktop-app`: aplicação JavaFX; inicia sem exigir o serviço remoto.
- `sync-contract`: tipos compartilhados entre o desktop e o serviço.
- `sync-service`: serviço Spring Boot isolado, com estado de saúde em `/actuator/health`.

O contrato e o serviço remoto não executam a operação diária do estabelecimento. A aplicação principal continua sendo o desktop local.

## Requisitos e versões

- Java 21 é a versão-alvo do produto e é validada pelo Maven Enforcer.
- JavaFX 21.0.13 atende à linha JavaFX 21 definida para o desktop.
- Spring Boot 3.5.16 é a linha 3.x escolhida para o serviço e é compatível com Java 21.
- Maven 3.9.16 fica fixado pelo Maven Wrapper. As versões de bibliotecas e plugins ficam no `pom.xml` raiz.

O BOM do Spring Boot centraliza as dependências do serviço. A versão do JUnit Jupiter é definida no POM raiz e reutilizada nos módulos.

## Build e testes

No Windows:

```powershell
./scripts/build.ps1
```

No macOS/Linux:

```sh
./scripts/build.sh
```

Os scripts chamam `clean verify` pelo Maven Wrapper. O teste do desktop abre uma janela JavaFX em modo smoke e encerra; o teste do serviço verifica o endpoint Actuator sem iniciar outro processo nem depender de banco ou credenciais.

Para iniciar a janela desktop, execute `../mvnw javafx:run` dentro de `desktop-app`. Para iniciar o serviço isoladamente, execute `../mvnw spring-boot:run` dentro de `sync-service`.
