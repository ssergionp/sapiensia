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

## Comprar e pagar

O Cliente compra um Produto com Pix e a página do Pedido mostra o Pix fictício (não dá para pagá-lo de verdade). Para simular o pagamento:

1. Copie o id do Pedido do endereço da página, `/pedidos/<id>`.
2. Rode:

```powershell
just demo-pagar <id>
```

Em alguns segundos a página vira "Pagamento confirmado" e o Produto aparece na Biblioteca. O e-mail de confirmação de pagamento não é enviado de verdade: ele é gravado como um arquivo de texto em `.tmp/demo/outbox`.

Se a API for reiniciada, os Pedidos criados antes não podem mais ser pagos (o Asaas falso guarda as cobranças só em memória): crie outro Pedido.

## Parar

```powershell
just demo-parar
```

O comando encerra a API e o site. O banco continua ligado; para desligá-lo, use `just db-stop`.
