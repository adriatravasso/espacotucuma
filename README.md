# Espaço Tucumã

Aplicação de gestão Windows-first e offline-first para beleza e estética. A operação diária fica no desktop local; a sincronização com o serviço remoto é iniciada manualmente.

**Este é um projeto fictício de portfólio que simula uma encomenda freelancer.** O Espaço Tucumã e todos os registros das demonstrações são sintéticos. Não representam contrato, faturamento, depoimentos ou implantação real em cliente.

## Módulos

- `desktop-app`: aplicação JavaFX e banco operacional SQLite local.
- `sync-contract`: contrato REST e DTOs compartilhados.
- `sync-service`: serviço Spring Boot para sincronização e snapshots remotos.

## Documentação

- [Especificação do produto](docs/ESPECIFICACAO_ESPACO_TUCUMA.md) — fonte normativa.
- [Arquitetura e decisões técnicas](docs/ARQUITETURA.md) — diagramas C4, ER, estados e fluxos.
- [Manual operacional](docs/MANUAL_OPERACIONAL.md) e [backup e recuperação](docs/RECUPERACAO_E_BACKUP.md).
- [Instalação Windows](docs/INSTALACAO_WINDOWS.md).
- [Segurança do serviço remoto](docs/SEGURANCA_SERVICO_REMOTO.md).
- [Ambiente de demonstração](docs/AMBIENTE_DEMONSTRACAO.md) e [validação local](docs/VALIDACAO_LOCAL.md).
- [Demonstração de portfólio](docs/DEMONSTRACAO_PORTFOLIO.md) — roteiro reproduzível e escopo real dos cenários.
- [Auditoria final de conformidade](docs/ACEITE_FINAL.md) — evidências e pendências frente à especificação.

## Build e execução

Requer Java 21; o Maven Wrapper fixa a versão do Maven. Para compilar e executar a suíte completa, use `scripts/build.ps1` no Windows ou `scripts/build.sh` no macOS/Linux. Não há publicação nem deploy automático pelo build.

Para executar localmente, use `../mvnw javafx:run` em `desktop-app` ou `../mvnw spring-boot:run` em `sync-service`. A aplicação desktop inicia sem serviço remoto e mantém dados locais fora do checkout.
