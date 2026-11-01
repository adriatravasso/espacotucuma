# Ambiente remoto de demonstração

O Compose sobe o serviço Spring Boot, PostgreSQL 16 e RustFS como storage S3 privado, com volumes e uma rede interna isolada. Ele é destinado somente a dados sintéticos e testes locais; não é produção, não publica o storage nem o PostgreSQL, e não substitui um provedor S3 de produção.

## Executar

Pré-requisitos: JDK 21, Docker Engine e Docker Compose v2. No Windows, use Git Bash junto do Docker Desktop.

```bash
./scripts/configure-demo-environment.sh
./mvnw --batch-mode --no-transfer-progress -pl sync-service -am package
docker compose --env-file .env.demo up --detach --build
curl http://127.0.0.1:18081/actuator/health/readiness
```

O primeiro comando gera segredos aleatórios locais em `.env.demo`; o arquivo está ignorado pelo Git. Preserve-o enquanto os volumes demo existirem, pois contém as credenciais de administração do storage. A API fica acessível apenas em `127.0.0.1:18081`. PostgreSQL e RustFS não têm portas publicadas; os containers comunicam-se por uma rede Docker marcada como `internal`. O serviço de bootstrap cria o bucket privado e associa à aplicação uma identidade de storage limitada a esse bucket. O serviço envia os snapshots com SSE-S3 (`AES256`). A imagem [RustFS](https://docs.rustfs.com/en/installation/container/docker/) e a CLI [rc](https://github.com/rustfs/cli) são versões fixadas no Compose/Dockerfile.

Para parar sem remover os dados sintéticos:

```bash
docker compose --env-file .env.demo down
```

Para apagar somente os dados de demonstração:

```bash
docker compose --env-file .env.demo down --volumes --remove-orphans
```

## Jornada automatizada

```bash
./scripts/test-demo-environment.sh
```

O script usa um nome de projeto temporário e remove seus containers e volumes no encerramento. Ele valida readiness com PostgreSQL, recusa uma credencial administrativa inválida, pareia uma estação sintética, envia uma operação idempotente, solicita e lista um snapshot validado no RustFS, confirma que um pedido anônimo ao objeto recebe `403` e verifica que a credencial S3 da aplicação não acessa outro bucket. O mesmo script é executado no workflow de CI depois da suíte Maven.

## Separação da produção

- A configuração Compose usa o perfil Spring `demo`, credenciais aleatórias e nomes/buckets exclusivos; não reutilize `.env.demo`, volumes ou dados desta demonstração em produção.
- HTTP para object storage é recusado por padrão. Só o perfil `demo`, sem `prod`/`production`, permite `http://rustfs:9000`; qualquer outro endpoint precisa de HTTPS.
- O serviço remoto em produção também exige `SERVER_SSL_ENABLED=true` e `TUCUMA_SYNC_SECRET` com ao menos 32 caracteres. Configure valores por secret manager do provedor, nunca no repositório, em logs ou em imagem.
- Em produção, use PostgreSQL e object storage privados fornecidos pelo ambiente escolhido, TLS público para a API, criptografia SSE no storage, políticas de bucket restritas ao estabelecimento e retenção compatível com os backups. Não exponha o console do storage nem links públicos para objetos.
- O cliente desktop continua operando com SQLite local se esse ambiente estiver parado; somente o comando explícito **Sincronizar** comunica-se com o serviço.

Esta configuração reproduzível local não é uma implantação hospedada nem uma oferta de backup em produção. O cenário demonstra o fluxo remoto com payloads fictícios e storage privado.
