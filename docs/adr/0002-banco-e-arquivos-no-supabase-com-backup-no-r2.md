---
status: accepted
---

# Banco e arquivos no Supabase, com backup noturno no Cloudflare R2

O PostgreSQL fica no Supabase Free (São Paulo) e os Arquivos do produto no Supabase Storage, em bucket privado. Toda noite um `pg_dump` é enviado ao Cloudflare R2. Escolhemos o menor custo possível até a primeira venda e aceitamos o RPO de até 24 horas e o RTO de até 1 dia.

## Consequences

- Pedidos e cadastros feitos depois do último backup podem se perder. A conciliação noturna com o Asaas (#39) corrige o pagamento e o estorno de Pedidos que existem no banco, sempre pelos caminhos únicos (`PaymentConfirmation` e `RefundConfirmation`), e **não recria Pedido**: uma cobrança paga sem Pedido no banco gera um alerta ao Administrador. O Pedido perdido é recriado à mão como pendente pelo runbook `docs/runbooks/conciliacao-asaas.md`, e a conciliação seguinte o leva a pago e concede o Acesso. Criar o Pedido a partir da cobrança faria a cobrança conferir a si mesma, e ela não diz qual é o Produto.
- Os arquivos ficam fora do banco, então perder o banco não apaga os PDFs.
- O gate de lançamento exige um teste de restauração feito de verdade, na homologação.
- Se as vendas crescerem, migrar para backup contínuo (plano pago do Supabase) é uma decisão de custo a reabrir.
- Os Arquivos do produto passam pelo adaptador `ProductFileStorage` (Supabase Storage em produção, disco no local, no CI e nos testes; o perfil `prod` não sobe com o de disco). O nome do objeto é gerado pelo servidor (`products/<id do Produto>/<UUID>.pdf`), o bucket `product-files` é privado e nenhuma URL pública é gerada nem guardada: a única URL que sai é a assinada de 60 segundos do Download (#33), emitida a cada clique e nunca guardada. Na troca do Arquivo, o objeto anterior não é apagado na hora: entra na fila de remoção (`pending_object_deletions`) na mesma transação da troca e a limpeza horária o apaga depois de 15 minutos, para um link já emitido não apontar para um objeto apagado; a limpeza nunca apaga o Arquivo atual do Produto nem a capa atual. Falhas entre o envio ao Storage e a gravação no banco podem deixar objetos órfãos, nunca um Produto apontando para um objeto que não existe (#18).
- As capas dos Produtos (#21) ficam em outro bucket privado do Supabase Storage, `product-covers`, pelo mesmo adaptador `ProductFileStorage` (o nome do objeto é `covers/<id do Produto>/<UUID>.<png|jpg|webp>`, gerado pelo servidor). Todo objeto que pode virar órfão (a capa nova antes de existir no Storage, a antiga e a removida) é registrado na tabela `pending_object_deletions` antes da operação, e uma tarefa em segundo plano apaga os registrados e limpa a linha. Assim uma queda do Storage no meio nunca deixa um objeto que ninguém conhece, e o banco nunca aponta para uma capa que não existe, e o commit que falha depois de já ter gravado também está tratado: antes de apagar o objeto novo, o servidor relê a capa atual, e se ela já aponta para ele o objeto é mantido; se a releitura também falha, a chave fica registrada para a tarefa em segundo plano decidir.
