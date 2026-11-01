# Instalação Windows

O instalador MSI é criado por `scripts/package-windows.ps1` com JDK 21, Maven e WiX Toolset 3.x. O comando recebe uma versão sem sufixo, por exemplo:

```powershell
.\scripts\package-windows.ps1 -AppVersion 1.0.0
```

O resultado fica em `desktop-app/target/windows-installer/`. O jpackage cria um runtime Java reduzido junto ao aplicativo, por isso a máquina da recepção não precisa de Java, Maven ou conexão com a internet. O identificador estável de upgrade permite que uma versão nova substitua a instalada.

Por padrão, os dados ficam em `%USERPROFILE%\.espacotucuma\development`; o SQLite, as preferências e os logs permanecem fora da pasta de instalação. `TUCUMA_DATA_ROOT` permite configurar outra raiz de dados por usuário. As rotinas de migração executam no início do aplicativo e preservam o banco existente; o MSI não inclui um banco.

Valide o ciclo em Windows com:

```powershell
.\scripts\test-windows-installer.ps1
```

O smoke instala as versões 1.0.0 e 1.0.1, abre o aplicativo em perfil sintético sem serviços remotos, grava um cliente de teste, reinicia após upgrade, verifica o SQLite e as migrações e desinstala mantendo os dados externos. A senha e o cliente criados nesse fluxo existem somente no diretório temporário de teste.

## Assinatura

O jpackage não assina o executável nem o MSI sem um certificado Authenticode e uma chave privada fornecidos pelo distribuidor. Este projeto não contém nem embute credenciais de assinatura. Os artefatos atuais são não assinados; o Windows pode exibir avisos do SmartScreen ou do editor desconhecido. Uma distribuição comercial deve assinar MSI e executáveis com certificado válido e carimbo de tempo antes de publicar.
