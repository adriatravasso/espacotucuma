# AGENTS.md — Espaço Tucumã

Este arquivo estabelece as regras permanentes para agentes de desenvolvimento neste repositório. O objetivo é entregar um **software comercial simples, confiável e utilizável**, não demonstrar complexidade arquitetural.

## 1. Ordem de leitura e autoridade

1. Leia `docs/ESPECIFICACAO_ESPACO_TUCUMA.md`: **fonte de verdade do produto** (requisitos, limites, arquitetura e interface).
2. Se estiver executando o pipeline, leia `.prompts/queue.md`, `.prompts/POLITICAS.md` e `.prompts/progress.md`; em seguida, consulte **somente o arquivo da tarefa atual** em `.prompts/tarefas/`.
3. Inspecione o código e os testes existentes antes de propor mudanças.
4. Em caso de conflito entre uma tarefa e a especificação, **não altere a especificação por conta própria**; registre a divergência e interrompa somente a parte bloqueada.
5. Este arquivo define princípios transversais; tarefas definem entregas e a especificação define o produto. **Não transforme a especificação em backlog.**

## 2. Princípio central: evitar overengineering

Aplique **KISS, YAGNI, DRY com bom senso** e a **menor solução correta** para os requisitos reais.

- Implemente **somente o escopo solicitado** e suas dependências estritamente necessárias. Não antecipe funcionalidades de tarefas futuras.
- Prefira **código explícito, legível e fácil de depurar** a abstrações sofisticadas.
- **Não crie interfaces, camadas, factories, builders, adapters, services genéricos ou helpers** sem uma necessidade concreta e verificável.
- Evite abstração prematura: uma pequena duplicação pode ser melhor que uma infraestrutura genérica para um único caso de uso. Extraia algo compartilhado quando houver benefício real de manutenção.
- Use bibliotecas já presentes. Uma nova dependência precisa resolver um problema objetivo e ser compatível com o empacotamento offline.
- Não introduza microsserviços, CQRS, event sourcing geral, filas externas, barramentos de eventos, plugin systems, motores de regras ou frameworks próprios por preferência arquitetural.
- Não faça refatorações amplas, renomeações em massa ou mudanças cosméticas fora do escopo da tarefa.
- Não adicione configurações, flags ou pontos de extensão apenas para possíveis necessidades futuras.
- Reutilize padrões existentes quando corretos; não copie padrões ruins apenas por consistência.
- **Segurança, integridade dos dados, testes e recuperação de falhas não são overengineering.** Simplificar nunca significa remover garantias necessárias.

**Pergunta obrigatória antes de implementar:** “Consigo atender a este requisito com menos componentes, menos estados e menos código, mantendo testes, clareza e segurança?” Se sim, prefira esse caminho.

## 3. Limites arquiteturais inegociáveis

- Produto principal: **aplicação desktop Windows-first**, com **Java 21 + JavaFX**, em **monólito modular**.
- Persistência operacional: **SQLite local**; login, agenda, caixa, cadastros e relatórios devem funcionar **sem internet**.
- Serviço remoto restrito: **Spring Boot + PostgreSQL** para sincronização, backups e restauração; não deslocar regras de operação cotidiana para a rede.
- **Uma estação principal** por estabelecimento na primeira versão; nada de SaaS, multitenancy ou edição concorrente por vários computadores.
- O botão **Sincronizar** inicia a comunicação remota **apenas por ação explícita do usuário**. Não criar sincronização automática, silenciosa ou periódica.
- Salvar localmente é independente de sincronizar. Persistir dados e entradas de outbox relevantes **na mesma transação**.
- Sincronização deve tolerar falhas e repetições: identificadores estáveis, idempotência, checkpoints e confirmação antes de marcar alterações como sincronizadas.
- **Dados sincronizados não equivalem a backup concluído**: a interface só indica backup realizado quando existir ponto de recuperação remoto validado.
- Nunca sobrescrever silenciosamente dados conflitantes, duplicar pagamentos/movimentações nem descartar pendências após falha de rede.
- Preservar integridade, rastreabilidade, permissões e privacidade dos dados, usando dados sintéticos em testes e demonstrações.

## 4. Interface e experiência

- Seguir o padrão **Classic Enterprise GUI (2005–2015)**: menus, toolbar, formulários compactos, abas, tabelas densas, diálogos e barra de status.
- Priorizar legibilidade, teclado e mouse, feedback de erros, navegação consistente e produtividade na recepção.
- Evitar estética de dashboard SaaS, glassmorphism, cards gigantes, animações supérfluas e reinvenções visuais não solicitadas.
- Nunca bloquear uma operação local por indisponibilidade da rede. Exibir claramente pendências e último backup validado.

## 5. Qualidade: feature e teste na mesma entrega

**Toda feature e correção comportamental exige testes automatizados adequados no mesmo PR.** Não deixar para uma fase futura.

- **JUnit 5/Mockito:** domínio, validações, cálculos e regras.
- **SQLite real temporário:** migrações, repositórios, transações e outbox.
- **TestFX:** comportamento de telas JavaFX, quando viável em ambiente gráfico de teste.
- **Spring Boot/Testcontainers/PostgreSQL e simuladores HTTP:** integração remota, idempotência, timeouts e falhas de conexão.
- **E2E desktop ↔ serviço remoto:** jornadas críticas de sincronização, backup e restauração.
- Para cada comportamento novo, cobrir ao menos caminho feliz e erro/caso-limite relevante. Bugs devem, quando possível, ganhar teste de regressão que falhe antes da correção.
- Prefira testes determinísticos, isolados e com dados fictícios. Não usar produção nem exigir internet externa para a suíte habitual.
- Rode testes novos, afetados e regressão adequada. Corrija falhas em vez de ocultá-las com `skipTests`, `@Disabled` ou mocks que não verificam o comportamento real.
- Para alterações apenas documentais ou de infraestrutura, aplique validações proporcionais; **não crie testes artificiais ou PR vazio**.
- Não declare testes ou builds aprovados se não foram executados. Se o ambiente impedir testes obrigatórios, registre o bloqueio e **não considere a entrega concluída**.

## 6. Fluxo Git / PR / merge — responsabilidade do agente

Para tarefas com alterações versionáveis e quando houver acesso remoto autorizado:

1. **Antes de começar:** confira `git status`, branch base/remoto e PRs abertos. Inspecione e resolva primeiro os PRs anteriores **pertinentes ao pipeline e sob sua responsabilidade**.
2. Não mescle, altere nem feche PRs de terceiros ou sem relação com a tarefa; não faça merge cego de alterações sem validação.
3. Use **uma branch por tarefa** (`feat/tuc-####-descricao`, `fix/tuc-####-descricao` ou equivalente), partindo da base atualizada.
4. Implemente o escopo, inclua os testes no mesmo conjunto de mudanças, execute as validações e faça commits claros.
5. Publique a branch, abra **você mesmo** o Pull Request, verifique CI, conflitos, revisões e políticas de proteção.
6. Corrija problemas e **realize você mesmo o merge** quando todos os requisitos do repositório estiverem satisfeitos; confirme o estado `MERGED` e o commit na branch base.
7. **Nunca** use `--force`, `--admin`, bypass de proteção ou aprovação fictícia para concluir um merge. Se faltar permissão, aprovação, credenciais ou CI, pare e documente o impedimento; não transfira o merge ao usuário como etapa rotineira.
8. Registre o resultado em `.prompts/progress.md` quando estiver executando a fila. Somente avance para a próxima tarefa após merge confirmado ou validação documentada de um caso sem mudanças.

**Atenção:** Git/PR é o fluxo de código-fonte; o botão **Sincronizar** do produto trata apenas dos dados do estabelecimento. Nunca confundir os dois.

## 7. Modo de execução e comunicação

- Trabalhe em **uma tarefa por vez**, na ordem de `.prompts/queue.md` quando a fila estiver em execução.
- Valide dependências e o estado atual antes de codificar. Se algo já existir, teste e reaproveite; não duplique.
- Não faça perguntas sobre decisões já resolvidas na especificação. Na ausência de detalhes não críticos, escolha a alternativa **mais simples e reversível**, registrando a decisão quando necessário.
- Documente decisões técnicas realmente relevantes, não cada linha de código. Não crie documentação redundante.
- Em cada entrega, informe objetivamente: **o que mudou, testes executados e resultados reais, PR/merge (URL e hash quando houver), pendências e bloqueios**.
- Se houver falha não resolvida, **não avance na fila nem declare sucesso**.

## 8. Definição de pronto

Uma tarefa com mudanças só está concluída quando o comportamento solicitado funciona, mantém o produto offline-first, possui testes proporcionais **versionados junto do código**, passa pelas verificações aplicáveis e tem o **PR efetivamente mesclado**, respeitando as regras do repositório.

> **Regra de ouro:** faça o necessário, faça bem-feito, teste de verdade e não construa complexidade que o Espaço Tucumã não precisa.
