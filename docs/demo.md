# Demonstração local

Um jeito de ver o sistema funcionando no seu computador, com dados fictícios, sem mexer no banco de desenvolvimento.

## Antes de começar

- Docker Desktop aberto.
- Java, Node/pnpm e just instalados (o `just setup` já cuida disso).
- Pelo menos 2 GB de memória livre.

## Ligar

```powershell
just demo
```

O comando sobe o banco, a API e o site em segundo plano (a primeira vez demora alguns minutos) e imprime os endereços e as contas. Depois, abra http://localhost:5173 no navegador.

## Contas

- **Cliente:** `cliente@example.com` / `Demo-Cliente-2026!` <!-- gitleaks:allow -->
- **Administrador:** entre em http://localhost:5173/painel/entrar com `admin@example.com` / `Demo-Admin-2026!` <!-- gitleaks:allow -->

O Administrador pede um código de verificação (MFA). Para ver o código atual, rode:

```powershell
just demo-codigo
```

O código vale cerca de 30 segundos. Se preferir, cadastre a chave `JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP` em um aplicativo autenticador. <!-- gitleaks:allow -->

## O que dá para ver

O Cliente cria conta, entra e vê o Catálogo e os Produtos. O Administrador entra no painel, cria, edita, envia PDF e capa, publica e despublica.

Ainda não dá para comprar, pagar nem baixar os conteúdos: essa parte do sistema está em construção.

## Parar

```powershell
just demo-parar
```

O comando encerra a API e o site. O banco continua ligado; para desligá-lo, use `just db-stop`.
