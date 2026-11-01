# Auditoria final de conformidade — Espaço Tucumã

Data da verificação: 2026-11-01. Fonte normativa: [`ESPECIFICACAO_ESPACO_TUCUMA.md`](../ESPECIFICACAO_ESPACO_TUCUMA.md), preservada sem alterações.

## Resultado

O repositório contém uma implementação desktop substancial, executável e testada dos fluxos locais e da sincronização manual. **O produto ainda não atende integralmente à especificação e não deve ser apresentado como plenamente aceito para operação comercial.** A tabela abaixo separa os recursos demonstrados das lacunas existentes. “Implementado” significa que o escopo indicado tem evidência no código/teste; não implica homologação em produção.

## Matriz requisito → evidência

| Seção | Status | Evidência e limite constatado |
| --- | --- | --- |
| 1. Identidade e princípios | Implementado | README e demonstração descrevem caso fictício, desktop local e sincronização acionada pelo usuário. Não há alegação de contrato ou implantação real. |
| 2. Público e ambiente | Implementado | `desktop-app` é Java 21/JavaFX com SQLite local; o serviço separado é Spring Boot/PostgreSQL. A suíte `OfflineBusinessJourneyTest` bloqueia HTTP/HTTPS durante a operação local e verifica persistência após reiniciar o runtime. |
| 3.1 Acesso, perfis e configurações | Parcial | `InitialAdministratorSetupTest`, `LocalAuthenticationTest`, `WindowsCredentialStoreTest` e testes dos diálogos cobrem criação inicial, login, proteção de credenciais e sessão. A especificação também pede gestão completa de usuários/perfis, troca/bloqueio, configurações do estabelecimento e da estação; não há evidência de todas essas operações disponíveis na interface. |
| 3.2 Clientes | Implementado | `ClientManagementTest`, `ClientRegistryPaneTest` e `OfflineBusinessJourneyTest` cobrem cadastro/consulta local e uso na jornada. O escopo permanece administrativo; informações clínicas sensíveis não fazem parte do modelo. |
| 3.3 Profissionais, serviços e preços | Parcial | `ProfessionalManagementTest`, `ServiceCatalogTest`, `PrepaidPackagesSQLiteTest` e `CommissionRulesSQLiteTest` cobrem regras e persistência; telas próprias têm testes. A especificação completa também requer manutenção integrada de horários/situação, snapshots e referências históricas em todas as alterações; a cobertura não demonstra cada combinação de ponta a ponta. |
| 3.4 Agenda | Parcial | `AppointmentSchedulingSQLiteTest`, `AppointmentStateMachineTest`, `AppointmentRegistryPaneTest` e a jornada offline cobrem persistência, estados e conflitos. Não foi demonstrada toda a matriz de visões dia/semana/profissional, intervalos bloqueados e exceção administrativa de encaixe. |
| 3.5 Atendimento, cobrança e comissão | Parcial | `OfflineBusinessJourneyTest`, `CommissionCalculatorTest`, `CommissionPreviewDialogTest` e `CommissionRulesSQLiteTest` evidenciam atendimento, recebimento sintético e comissão. `ManualSynchronizationEndToEndTest` prova que retry após resposta perdida não duplica pagamento. A evidência de interface não cobre integralmente todas as formas de pagamento dividido, comprovante imprimível/PDF, estornos e autorizações previstas. |
| 3.6 Caixa e financeiro | Parcial | A jornada offline testa abertura/fechamento de caixa e recebimento local; testes de relatórios e E2E verificam integridade financeira e ausência de duplicação. A matriz completa de sangrias, suprimentos, despesas, reabertura e conciliação por operador/turno não está demonstrada integralmente. |
| 3.7 Estoque | Parcial | `InventorySQLiteTest`, `InventoryPaneTest` e a jornada offline cobrem cadastro/movimentação e consumo sintético, e o retry remoto não duplica a baixa. Fornecedor, todos os tipos de ajuste e configuração de consumo por produto/serviço não têm evidência completa nesta validação. |
| 3.8 Relatórios e exportações | Parcial | `CommercialReportsSQLiteTest`, `CommercialReportsPaneTest` e `LocalArtifactWriterTest` demonstram consultas locais e geração de artefato. Não há evidência de cobertura funcional de todos os relatórios, filtros e formatos CSV/PDF enumerados na especificação. |
| 3.9 Auditoria | Parcial | `ImmutableAuditTest`, `TransactionalOutboxTest` e a jornada verificam trilha imutável e gravação local. A especificação enumera eventos e referências; não foi demonstrado que todas as alterações de permissões, ajustes e sincronização estejam cobertas pela interface e testes. |
| 4. Interface e interação | Parcial | `TucumaMainShellTest`, `TucumaThemeTest`, testes TestFX de diálogos/tabelas e `scripts/test-windows-installer.ps1` evidenciam shell JavaFX, interface clássica e abertura instalada. A validação automatizada não cobre todos os atalhos, foco/contraste, impressão e dimensões/combinações de teclado previstas. |
| 5. Offline e persistência | Implementado | `OfflineBusinessJourneyTest`, `AbruptShutdownRecoveryTest`, `SQLiteMigrationTest` e `TransactionalOutboxTest` cobrem operação sem rede, reinício, migrações e persistência transacional/outbox. A jornada confirma que registros locais permanecem acessíveis após recriar o runtime. |
| 6.1–6.3 Sincronização manual, falha e retry | Implementado | `HttpManualSynchronizationClientTest` e `ManualSynchronizationEndToEndTest` cobrem chamada explícita, serviço/PostgreSQL real via Testcontainers, resposta perdida após commit, falha no snapshot e repetição sem duplicar pagamento/estoque. Não há sincronização periódica automática. |
| 6.4 Backup, retenção e restauração | Parcial | `BackupSnapshotIntegrationTest` valida autorização/download de snapshot; `LocalBackupRestorerTest` testa checksum, confirmação, preparação isolada e preservação da base atual. Retenção configurada é exercitada nos testes do serviço. **A restauração remota ainda não é iniciada por uma tela do desktop**: a recuperação remota permanece assistida, sem jornada autônoma para uma instalação nova. |
| 7. Arquitetura e tecnologia | Implementado | `pom.xml`, `docs/arquitetura/ARQUITETURA.md`, `ArchitectureRulesTest` e a suíte confirmam monólito desktop modular Java 21/JavaFX/SQLite e serviço restrito Spring Boot/PostgreSQL com storage de objetos. O serviço é exercitado localmente/CI, sem afirmar implantação comercial. |
| 8. Modelo de dados | Parcial | Migrações SQLite/PostgreSQL, entidades de sincronização, chaves estáveis, valores monetários inteiros e outbox são exercitados pela suíte. A implementação cobre os fluxos existentes, mas não todos os cadastros/configurações e combinações do modelo conceitual completo. |
| 9. Contrato de sincronização | Parcial | Contrato REST compartilhado, autenticação de estação, idempotência, snapshots e OpenAPI têm cobertura em testes de integração. O limite relevante é a ausência da experiência desktop de recuperação remota; contrato implementado não equivale a fluxo completo de recuperação do produto. |
| 10. Segurança e privacidade | Parcial | Testes cobrem hash de senha, Credential Manager do Windows, autenticação, limites de chamadas, configuração de produção e storage privado. Não há evidência de criptografia própria do arquivo SQLite ou de um ciclo completo de administração/eliminação de dados pessoais; a proteção do dispositivo e revisão operacional continuam necessárias. |
| 11. Requisitos não funcionais e aceite | Parcial | `scripts/build.ps1` passou com suíte completa; o ciclo MSI local passou (instalar, abrir offline, gravar/confirmar dado sintético, atualizar preservando banco e desinstalar). A jornada crítica de offline e retry também passou. Ainda não houve homologação em clientes, hardware/periféricos ou ambiente produtivo real. |
| 12. Limites explícitos de escopo | Implementado | A arquitetura e o código não introduzem web/PWA/Electron, multitenancy, multiestação concorrente, sync silenciosa ou processamento fiscal/adquirente. O serviço remoto está restrito a sincronização e recuperação. |
| 13. Documentação e demonstração | Parcial | README, `docs/portfolio/DEMONSTRACAO_PORTFOLIO.md`, manuais e `test-demo-environment.sh` descrevem caso e dados sintéticos com limites honestos. O perfil DEMO não inicia com cadastros comerciais pré-semeados; a jornada demonstrável de negócio está automatizada em testes, não em uma experiência de demonstração pronta ao abrir o aplicativo. |
| 14. Governança da especificação | Implementado | A especificação não foi editada nesta tarefa. Divergências são registradas aqui como pendências, sem redefinir o produto. |

## Validações executadas

- `scripts/build.ps1` — `BUILD SUCCESS`; desktop **164/164** e serviço **30/30**, incluindo TestFX, SQLite e integrações com PostgreSQL 16/Testcontainers.
- `scripts/test-windows-installer.ps1` via PowerShell 7 e WiX 3.14 extraído em `%TEMP%` — passou: instalação MSI, abertura offline, persistência de registro sintético após upgrade 1.0.0 → 1.0.1 e desinstalação preservando os dados de usuário. A extração temporária evitou instalar WiX ou alterar recursos do Windows.
- Jornada offline: `OfflineBusinessJourneyTest` — criação e leitura local com HTTP/HTTPS bloqueado, fechamento/reabertura, novo login e relatórios disponíveis.
- Falha e retry: `ManualSynchronizationEndToEndTest` — resposta perdida após commit e falha no snapshot, seguida de retry sem duplicar pagamento nem movimento de estoque.
- Checksum/recuperação: `LocalBackupRestorerTest` (3 casos) — checksum inválido, ausência de confirmação e falha de preparação não substituem o banco atual; restore válido em diretório isolado.
- Especificação: `git diff origin/main -- docs/ESPECIFICACAO_ESPACO_TUCUMA.md` sem diferenças antes desta alteração documental.

## Pendências que impedem aceite integral

1. Disponibilizar na interface o fluxo autorizado de restauração de backup remoto para instalação substituta e demonstrá-lo de ponta a ponta, preservando e validando a base anterior.
2. Completar a superfície administrativa (usuários/perfis/configurações) e comprovar as regras financeiras, fiscais não incluídas, agenda, caixa, estoque e relatórios ainda sem cobertura demonstrada, seguindo a especificação vigente.
3. Preparar um modo de demonstração utilizável com dados sintéticos reproduzíveis no aplicativo, sem confundi-lo com produção.
4. Completar a validação de usabilidade/acessibilidade e de requisitos operacionais em ambiente representativo antes de declarar prontidão comercial.

Esta auditoria fecha a fila de tarefas de implementação registrada no repositório; **não converte as lacunas acima em aceite funcional nem afirma prontidão para produção**. A especificação segue sendo a referência para decisões futuras.
