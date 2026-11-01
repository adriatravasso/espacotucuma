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

O GitHub Actions executa o mesmo build em Java 21, valida a formatação, a convergência de dependências, todos os testes Maven e faz uma varredura Gitleaks no histórico. Não há etapa de publicação ou deploy automático. O desktop aplica as migrações versionadas do Flyway no banco local antes de abrir a janela; valores financeiros são armazenados em centavos inteiros e as chaves estrangeiras do SQLite ficam habilitadas em cada conexão.

Para iniciar a janela desktop, execute `../mvnw javafx:run` dentro de `desktop-app`. Para iniciar o serviço isoladamente, execute `../mvnw spring-boot:run` dentro de `sync-service`.

## Ambientes locais

O desktop usa `-Dtucuma.profile=development`, `test`, `demo` ou `homologation`; por padrão, os dados ficam fora do checkout em `~/.espacotucuma/<perfil>`. O perfil `test` usa o diretório temporário do sistema. `-Dtucuma.data-root=<caminho>` define outra raiz, mantendo uma subpasta por perfil. A inicialização cria `preferences.properties`, o banco `tucuma.sqlite` e a pasta `logs`; a preferência `database.file` pode apontar para um banco absoluto em outro local. Um arquivo de lock impede duas instâncias de abrir o mesmo banco. Se o acesso ou a verificação de integridade falhar, a tela informa o problema sem substituir o arquivo existente. Bancos SQLite e arquivos de lock (`*.db`, `*.sqlite`, `*.sqlite3`, `*.sqlite.lock`) são ignorados pelo Git.
