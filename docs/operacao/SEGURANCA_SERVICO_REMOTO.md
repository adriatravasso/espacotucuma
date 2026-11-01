# Operação segura do serviço remoto

O serviço remoto atende apenas à sincronização manual, aos backups e à restauração. A operação local do desktop não depende deste serviço.

## Segredos e rotação

- Armazene `TUCUMA_SYNC_SECRET` no gerenciador de segredos do ambiente de hospedagem. Use pelo menos 32 caracteres aleatórios e não o coloque em arquivos versionados, imagens de contêiner, tickets ou logs.
- Para rotacionar o segredo administrativo, gere outro valor aleatório, atualize a variável no ambiente de produção e reinicie o serviço. Faça uma chamada de provisionamento autorizada de verificação e confirme que o valor anterior recebe `401`; nunca registre o segredo durante a validação.
- Tokens de acesso de dispositivo expiram em 15 minutos; credenciais de dispositivo permanecem válidas até a revogação. Para substituir a credencial, revogue o dispositivo e pareie a estação substituta usando um novo código de curta duração. Não reutilize código de pareamento.
- Mantenha HTTPS/TLS no ponto público de entrada. O perfil `prod`/`production` não inicia se TLS não estiver habilitado e o segredo administrativo estiver ausente ou for curto.

## Perda ou suspeita de comprometimento da estação

1. Usando o segredo administrativo pelo canal seguro, revogue imediatamente o identificador do dispositivo em `POST /sync/admin/devices/{deviceId}/revoke`. A credencial e os tokens de acesso existentes deixam de autorizar operações.
2. Registre o horário, o identificador do dispositivo e a pessoa que executou a revogação no registro operacional do incidente. Não copie tokens, payloads ou dados de clientes para esse registro.
3. Para a estação substituta, emita um novo código de pareamento para o estabelecimento (`POST /sync/admin/station-replacements`) e conclua o pareamento presencialmente. Confirme o novo identificador e a leitura do status remoto.
4. Se houver suspeita de exposição do segredo administrativo, rotacione-o conforme a seção anterior e revise os registros de acesso desde o primeiro horário suspeito. Preserve os logs necessários à apuração segundo a política de retenção do ambiente.

## Observabilidade e acesso

Os logs de acesso do serviço registram caminho, identificadores do estabelecimento/dispositivo, emissão de pareamento/token, revogação e recusas de autenticação. Não registram cabeçalhos de autorização, credenciais, códigos de pareamento, tokens, endereços IP, corpos de requisição nem payloads sincronizados. Mantenha os logs acessíveis somente a operadores autorizados. O serviço não habilita CORS; clientes de navegador não são uma superfície suportada.

As chamadas de sincronização têm limite de 120 requisições por minuto por origem e por dispositivo. A recusa retorna `429` com `Retry-After`; o limite é aplicado antes do controlador e não inicia gravação. Configure a plataforma para manter uma instância do serviço ou aplique limites equivalentes no proxy de entrada caso ela seja escalada horizontalmente.
