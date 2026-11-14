# Chave de API do Asaas na homologação

Passo a passo para gerar a chave de API do sandbox do Asaas, gravá-la no Fly.io como `APP_ASAAS_API_KEY` e provar de verdade que o Pedido com Pix funciona na homologação. A decisão está no `docs/adr/0003-pagamento-so-por-pix-via-asaas.md`; o deploy segue o `docs/runbooks/homologacao-fly.md`; o webhook tem o seu próprio roteiro, em `docs/runbooks/webhook-asaas.md`.

Legenda: **[AGENTE]** roda na sessão do Claude Code. **[RESPONSÁVEL]** você faz, digitando numa aba comum do PowerShell, na pasta do projeto, nunca no chat, ou no navegador.

Regras que valem para tudo aqui:

- **Nenhum valor de segredo** (a chave da API, em especial) em chat, arquivo, commit, PR, issue ou log. Este documento só tem nomes.
- A chave **não** é o token do webhook: são dois segredos diferentes, com nomes diferentes (`APP_ASAAS_API_KEY` e `APP_ASAAS_WEBHOOK_TOKEN`).
- Localmente e no CI não há nada a fazer: o perfil local usa um Asaas falso em memória e o CI usa um servidor de mentira (`.github/asaas-stub.mjs`) com uma chave fictícia.
- Este segredo é necessário **antes do deploy desta versão na homologação**, não antes do merge: sem ele o perfil `prod` não sobe.

## Passo zero: antes de copiar qualquer segredo

O mesmo do `docs/runbooks/homologacao-fly.md`.

1. **[RESPONSÁVEL]** **Desligue o histórico da área de transferência do Windows** (Configurações > Sistema > Área de Transferência) antes de copiar a chave; depois, religue-o se quiser. Limpar o histórico uma vez antes **não** protege o que for copiado depois.
2. **[RESPONSÁVEL]** No Bitwarden, ligue a limpeza automática da área de transferência (Configurações > Opções > "Limpar área de transferência"), por exemplo 30 segundos.

## 1. Gerar a chave no sandbox

3. **[RESPONSÁVEL]** Entre em <https://sandbox.asaas.com> com a conta do sandbox (a mesma em que o webhook foi criado). É uma conta separada da produção; nada ali movimenta dinheiro de verdade.
4. **[RESPONSÁVEL]** Clique no seu nome ou foto, no canto superior direito, e abra `Integrações`. Entre na aba `Chaves de API`.
5. **[RESPONSÁVEL]** Peça uma chave nova (o rótulo do botão pode variar: "Gerar chave de API" ou "Nova chave"). Dê o nome `sapiensia-homolog`. Sem data de expiração.
6. **[RESPONSÁVEL]** A chave aparece **uma única vez**. Copie e cole no Bitwarden com o nome `APP_ASAAS_API_KEY (homolog)`, salve, e só então feche a janela do Asaas. Ela começa com `$aact_hmlg_`; se começar com `$aact_prod_`, você está na conta errada: pare. Como ela começa com `$aact_`, no PowerShell grave-a sempre entre aspas simples (`$env:APP_ASAAS_API_KEY = '...'`): com aspas duplas o `$aact_` é expandido e a chave fica vazia.

## 2. Gravar no Fly.io

7. **[RESPONSÁVEL]** Copie a chave do Bitwarden. Na pasta do projeto, no PowerShell, grave o segredo.

   ```powershell
   fly secrets set APP_ASAAS_API_KEY="$(Get-Clipboard)" --stage -a sapiensia-homolog
   ```

   Grava o que está na área de transferência como segredo do app, sem reiniciar.

   ```powershell
   Set-Clipboard -Value $null
   ```

   Esvazia a área de transferência.

8. **[AGENTE]** Confira os nomes.

   ```powershell
   fly secrets list -a sapiensia-homolog
   ```

   Mostra só nomes, digest e data. Devem aparecer 10 nomes, incluindo `APP_ASAAS_API_KEY`.

9. **[RESPONSÁVEL]** Ainda no sandbox, em `Pix > Minhas chaves` (o nome do menu pode variar), cadastre uma chave Pix aleatória: o Asaas recomenda e, sem ela, o QR Code depende de uma saída provisória.

O endereço da API (`APP_ASAAS_API_BASE_URL = "https://api-sandbox.asaas.com"`) já está no `[env]` do `fly.homolog.toml`; não é segredo.

## 3. Prova real na homologação

Depois do deploy (seção 5 do `docs/runbooks/homologacao-fly.md`). Isto é o que o CI **não** consegue provar: ele fala com um servidor de mentira, não com o Asaas.

10. **[RESPONSÁVEL]** Acorde a máquina: abra `https://sapiensia-homolog.fly.dev/api/system/health` e espere `{"status":"UP"}` (pode levar mais de um minuto; a máquina desliga sozinha sem tráfego).
11. **[RESPONSÁVEL]** Entre como Cliente, abra um Produto publicado, clique em "Comprar com Pix", informe um CPF de teste que **não** seja real (gere um válido numa ferramenta de CPF fictício) e confira que aparecem o QR Code e o código "Pix copia e cola".
12. **[RESPONSÁVEL]** No painel do sandbox, em Cobranças, confira que a cobrança existe, com o valor do Produto, e que o cliente tem o seu e-mail como nome e nenhuma notificação enviada. O botão "Confirmar pagamento" do sandbox só simula; o Pedido virar pago é o ticket #28.
13. **[RESPONSÁVEL]** Deixe o Pedido expirar (30 minutos) e confira, no painel do sandbox, que a cobrança foi cancelada. A tarefa de expiração roda a cada minuto.
14. **[AGENTE]** Leia o log e procure falhas do Asaas.

    ```powershell
    fly logs -a sapiensia-homolog --no-tail
    ```

    Mostra o log recente. Procure `Asaas call failed` ou `Asaas refused the API key`: o log traz a etapa e o status HTTP, nunca a chave, o CPF nem o código Pix. `HTTP 401` ou `403` significa chave errada ou da conta errada.

Se algo falhar aqui (chave, `User-Agent`, valor mínimo, nome do código de erro do CPF), o ajuste é no adaptador e volta como ticket.

## Se der problema

- **O app não sobe e o log cita `APP_ASAAS_API_KEY is required`**: o segredo não foi gravado ou está em branco. Repita os passos 7 e 8.
- **O app não sobe e o log cita `app.asaas.api.base-url`**: o `APP_ASAAS_API_BASE_URL` está fora da lista (`https://api-sandbox.asaas.com`, `https://api.asaas.com` ou localhost). Confira o `fly.homolog.toml`.
- **O Cliente vê "Não foi possível gerar o Pix agora"**: veja o log do passo 14.

## Rotação da chave

Gere uma chave nova (passos 3 a 6), grave com `fly secrets set ... --stage` (passo 7) e faça `fly deploy` (ou `fly secrets deploy`). Depois apague a chave antiga na aba `Chaves de API` do Asaas. A produção terá outra chave, de outra conta, e nunca a da homologação.

## O que não fazer

- Colar a chave em chat, arquivo, commit, issue ou comando com o valor digitado.
- Usar o token do webhook como chave da API, ou o contrário.
- Usar a chave de produção (`$aact_prod_...`) na homologação.
- Apontar `APP_ASAAS_API_BASE_URL` para um endereço que não seja do Asaas: o adaptador recusa subir, para a chave nunca ir a outro lugar.
