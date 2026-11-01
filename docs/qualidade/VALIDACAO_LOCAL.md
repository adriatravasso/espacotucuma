# Validação local, recuperação e desempenho

Medições executadas no host Windows em 2026-11-01 com Java 21, SQLite temporário e dados sintéticos. São referências desta máquina, não garantias para todo computador.

## Desempenho e interface

| Cenário | Resultado | Limite automatizado |
| --- | ---: | ---: |
| Inicialização de `DesktopRuntime` com banco novo e migrações | 167 ms | 10 s |
| Registro local de cliente com auditoria e outbox | 9 ms | 2 s |
| Pesquisa TestFX de um cliente entre 3.000 registros, tela 1366×768 | 56 ms | 5 s |
| Consulta SQLite dos 10 agendamentos do dia em banco com 1.000 registros distribuídos por dias úteis | 1 ms | 2,5 s |

O teste de cadastro usa paginação e busca no SQLite real, confirma controles acessíveis e mantém a janela na resolução mínima especificada. Os testes já existentes verificam atalhos `Ctrl+N` e `F5`, navegação por teclado, tabela paginada, resposta da janela enquanto a consulta está ocupada e prévia de impressão.

## Recuperação e falhas de armazenamento

- Um processo filho grava cliente, auditoria e outbox em uma operação confirmada, inicia outra transação SQLite e é encerrado com `Runtime.halt(0)`. Na reabertura, o cadastro confirmado, a auditoria e o outbox permanecem; a inserção sem commit não aparece; `PRAGMA quick_check` retorna `ok`.
- O teste de bloqueio abre uma transação `BEGIN EXCLUSIVE`, verifica que a inicialização falha sem substituir o arquivo e confirma que a base volta a abrir com o registro confirmado depois de liberar o lock.
- A condição `SQLITE_FULL` é reproduzida com `PRAGMA max_page_count`, preservando linhas confirmadas e a integridade. O painel orienta liberar espaço e informa que a operação não foi confirmada, sem exibir detalhes do driver.
- Testes existentes rejeitam e preservam banco corrompido e caminho inacessível; também verificam lock de segunda instância e reinício normal após uma jornada offline.

## Limites da validação

- `SQLITE_FULL` foi provocado pelo limite de páginas do próprio SQLite, não por esgotamento físico de uma partição Windows.
- A impressão foi validada pela prévia JavaFX e geração local de artefatos; não havia impressora física conectada para validar o driver de impressão.
- A medição de inicialização cobre `DesktopRuntime.open` e migrações locais; não mede inicialização completa da JVM nem o primeiro frame da janela JavaFX.

Execute `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build.ps1` para repetir a suíte completa, incluindo TestFX e integração PostgreSQL/Testcontainers.
