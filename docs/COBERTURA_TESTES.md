# Cobertura de testes

## Execução e relatórios

Execute `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build.ps1` na raiz para rodar a suíte e gerar relatórios JaCoCo. Os relatórios HTML ficam em `desktop-app/target/site/jacoco/index.html` e `sync-service/target/site/jacoco/index.html`; são artefatos locais ignorados pelo Git.

Medição da suíte completa em 2026-11-01:

| Módulo | Testes | Linhas cobertas | Desvios cobertos |
| --- | ---: | ---: | ---: |
| Desktop | 155 | 8.758 / 10.727 (81,6%) | 1.940 / 3.328 (58,3%) |
| Serviço | 28 | 705 / 815 (86,5%) | 206 / 306 (67,3%) |

Cobertura de linhas nos pacotes desktop das regras críticas:

| Pacote | Linhas cobertas |
| --- | ---: |
| Agenda | 188 / 223 (84,3%) |
| Pacotes pré-pagos | 236 / 266 (88,7%) |
| Comissões | 279 / 293 (95,2%) |
| Financeiro | 358 / 383 (93,5%) |
| Estoque | 403 / 493 (81,7%) |

## Regras críticas verificadas

- Agenda: colisão, limites adjacentes, duração, reagendamento, concorrência de criação e exceção de sobreposição autorizada.
- Pacotes: snapshot da compra, consumo e repetição idempotente, expiração, reversão explícita e fronteira inclusiva do último dia válido com relógio controlado.
- Preços e comissões: preço preservado no atendimento, regras de precedência, desconto, arredondamento monetário e rateio determinístico de centavos.
- Pagamento e estorno: pagamentos parciais, repetição idempotente, atomicidade com movimento de caixa, autorização e redução do recebível/caixa uma única vez.
- Caixa: abertura, rejeição de turno duplicado, conciliação, auditoria e persistência após reinício do banco.
- Estoque: baixa no consumo, validação de saldo insuficiente e rollback junto da conclusão do atendimento.

Os testes de persistência usam SQLite temporário. A suíte do serviço usa PostgreSQL 16 em Testcontainers. O build completo desta medição passou: desktop 155/155 e serviço 28/28.

## Lacunas conhecidas

- JaCoCo mede execução de linhas e desvios, não a correção de todas as combinações de regras; percentuais não substituem asserts de negócio nem estabelecem uma meta global.
- Os testes isolam as regras e verificam atomicidade em casos centrais, mas não cobrem todas as combinações entre múltiplos pagamentos, estornos parciais repetidos, pacote pré-pago, comissão e baixa de estoque na mesma jornada de ponta a ponta.
- A cobertura não equivale a validação de uma instalação Windows em produção ou de todos os periféricos e integrações externas. Fluxos de UI têm testes TestFX existentes, mas não exercitam todas as combinações de teclado, acessibilidade e falhas de dispositivo.

Esses limites são explícitos para orientar novas jornadas de negócio quando os respectivos fluxos forem ampliados; não houve falha aberta na suíte desta entrega.
