---
status: accepted
---

# Banco e arquivos no Supabase, com backup noturno no Cloudflare R2

O PostgreSQL fica no Supabase Free (São Paulo) e os Arquivos do produto no Supabase Storage, em bucket privado. Toda noite um `pg_dump` é enviado ao Cloudflare R2. Escolhemos o menor custo possível até a primeira venda e aceitamos o RPO de até 24 horas e o RTO de até 1 dia.

## Consequences

- Pedidos e cadastros feitos depois do último backup podem se perder. A conciliação noturna com o Asaas recria Pedidos pagos e Acessos que faltarem.
- Os arquivos ficam fora do banco, então perder o banco não apaga os PDFs.
- O gate de lançamento exige um teste de restauração feito de verdade, na homologação.
- Se as vendas crescerem, migrar para backup contínuo (plano pago do Supabase) é uma decisão de custo a reabrir.
- Os Arquivos do produto passam pelo adaptador `ProductFileStorage` (Supabase Storage em produção, disco no local, no CI e nos testes; o perfil `prod` não sobe com o de disco). O nome do objeto é gerado pelo servidor (`products/<id do Produto>/<UUID>.pdf`), o bucket `product-files` é privado e nenhuma URL pública é gerada nem guardada. Falhas entre o envio ao Storage e a gravação no banco, ou ao apagar o arquivo anterior, podem deixar objetos órfãos, nunca um Produto apontando para um objeto que não existe (#18).
