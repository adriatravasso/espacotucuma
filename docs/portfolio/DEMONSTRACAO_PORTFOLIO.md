# Demonstração de portfólio

O Espaço Tucumã é um caso fictício de portfólio que simula uma encomenda freelancer. O estabelecimento, as pessoas, os pagamentos e o estoque usados nos cenários são sintéticos. Não há contrato, faturamento, depoimentos ou implantação real em cliente.

## Reproduzir os cenários

No Windows, com Java 21, Docker Desktop e Docker Compose v2 disponíveis, execute na raiz do repositório:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build.ps1
```

Esse comando limpa e valida os três módulos, executa os testes JavaFX e SQLite, os testes de sincronização com PostgreSQL 16 descartável e os cenários de serviço/storage da pipeline. Testcontainers usa containers temporários; nenhuma conta, banco ou dado de cliente real é necessário.

## Roteiro observado nos testes

1. `OfflineBusinessJourneyTest` abre a interface JavaFX e um SQLite temporário, bloqueia chamadas HTTP/HTTPS, cria registros sintéticos pelos casos de uso normais e percorre cadastro, agendamento, confirmação, atendimento, consumo de estoque, recebimento PIX, fechamento de caixa e relatório. Confirma as pendências de outbox e auditoria, reinicia o runtime, autentica novamente e verifica que os dados e o relatório continuam disponíveis.
2. `ManualSynchronizationEndToEndTest` usa o cliente de sincronização do desktop contra o serviço Spring e PostgreSQL 16 de teste. Simula resposta perdida após o commit remoto e falha de upload do snapshot. A nova chamada termina com backup validado sem duplicar pagamento ou movimentação de estoque.
3. `LocalBackupRestorerTest` restaura fixture sintética com checksum em outro diretório/SQLite temporário e valida checkpoint, histórico e saldos. Os casos negativos verificam que checksum incorreto, falta de confirmação ou preparação interrompida preservam a base existente. `BackupSnapshotIntegrationTest` também valida autorização e download de snapshot em estação de reposição.
4. Para exercitar a implantação remota descartável com RustFS privado, rode `./scripts/test-demo-environment.sh` no Git Bash. O script cria e remove projeto, containers e volumes próprios e testa pareamento, operação idempotente, snapshot validado e acesso privado ao objeto.

O roteiro desktop atual é automatizado e reprodutível por terceiros; para executá-lo, use a suíte acima. O app instalado não inicia com um salão já semeado: a primeira execução pede a criação local do administrador. Não reutilize a senha sintética dos testes em instalações reais. O perfil DEMO e o armazenamento local de demonstração são separados dos perfis de produção; o serviço remoto demo usa `.env.demo` ignorado pelo Git, com segredos aleatórios descartáveis. A sincronização do app continua exigindo ação explícita do usuário.

## Limite de recuperação

Os testes cobrem o transporte/autorização de restauração e a aplicação validada do snapshot em uma base SQLite isolada. O fluxo de restauração remota ainda não é iniciado por uma tela do desktop; quando necessário, é um procedimento assistido. Não apresente a restauração como operação autônoma disponível no app.
