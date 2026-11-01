# Organização técnica

O desktop é um monólito modular. Os pacotes ficam dentro de `desktop-app` e são organizados por camada; ao surgir um domínio de negócio, seu código fica agrupado por contexto dentro da camada.

| Camada | Responsabilidade | Dependências permitidas |
| --- | --- | --- |
| `ui` | JavaFX, navegação e apresentação | `application`, `domain`, JavaFX |
| `application` | casos de uso e portas de entrada/saída | `domain` e Java padrão |
| `domain` | regras e valores de negócio sem infraestrutura | Java padrão |
| `persistence` | SQLite, JDBC, migrações e repositórios locais | `application` e `domain` |
| `integration` | relógio do sistema e adaptadores remotos de sincronização | portas da aplicação e `domain` |

Regras de domínio não conhecem JavaFX, SQL, Spring ou clientes HTTP. A interface chama casos de uso; ela não abre conexão nem consulta repositórios. A persistência de operação diária permanece local. A porta de sincronização representa uma ação explícita e só deve ser chamada pelo fluxo iniciado pela pessoa usuária. O módulo `sync-service` trata exclusivamente sincronização, backup e restauração.

## Contratos compartilhados

- Entidades sincronizáveis recebem `EntityId`, que encapsula UUID gerado localmente e permanece estável após persistido.
- Valores monetários usam `Money` baseado em `BigDecimal`, normalizado para duas casas decimais. Valores com fração menor que um centavo são rejeitados; não se usa `float` ou `double` para dinheiro.
- O horário técnico é representado por `Instant` UTC e obtido por `TimeProvider`, para permitir testes determinísticos.
- `RepositoryPort`, `UnitOfWork`, `CurrentUserProvider` e `ManualSynchronizationPort` são portas da aplicação. Seus adaptadores concretos entram junto das tarefas de persistência, autenticação e sincronização correspondentes.
