# Bucket dos Arquivos do produto no Supabase Storage

Os PDFs dos Produtos ficam num bucket **privado** do Supabase Storage (ADR-0002, #18). O bucket é criado à mão, uma vez por ambiente (homologação e produção). Não coloque a secret key nem nenhum outro segredo em comando, ticket, conversa ou arquivo do repositório.

## 1. Criar o bucket

No painel do Supabase do ambiente, em **Storage**:

1. Crie um bucket chamado `product-files` (o nome está no `application-prod.yml`).
2. Deixe-o **privado**: a opção de bucket público fica desligada. Nenhuma URL pública é usada; o download (#33) usa só a URL assinada de 60 segundos, emitida a cada clique.
3. Limite de tamanho por arquivo: **50 MB** (o mesmo limite da aplicação, 52.428.800 bytes).
4. Se o painel oferecer restrição de tipo, permita só `application/pdf` (é o `Content-Type` que a aplicação envia).

## 2. Configurar os segredos da aplicação

A aplicação lê duas variáveis de ambiente no perfil `prod` (os nomes estão no `.env.example`, sem valor):

| Variável | O que é | Onde achar |
|---|---|---|
| `APP_STORAGE_SUPABASE_URL` | endereço do projeto (`https://<projeto>.supabase.co`) | configurações de API do projeto |
| `APP_STORAGE_SUPABASE_SECRET_KEY` | a *secret key* (`sb_secret_...`), não a chave publicável | chaves de API do projeto |

Grave as duas nos secrets da plataforma (Fly.io, #7), digitando o valor só no seu terminal:

```powershell
fly secrets set APP_STORAGE_SUPABASE_URL=<endereço do projeto> --app <app do ambiente>
```

Grava o endereço do projeto como segredo da aplicação no Fly.io (repita para `APP_STORAGE_SUPABASE_SECRET_KEY`).

Sem a URL ou com a secret key vazia, a aplicação **não sobe** no perfil `prod`, e também não sobe se alguém configurar o adaptador de disco (`app.storage.provider=file`).

## 3. Conferir na homologação (gate #47)

O CI não tem Supabase: o adaptador é testado com respostas simuladas. Na homologação, confira à mão:

1. Envie o PDF de um Produto em rascunho pelo painel e veja a linha "Arquivo atual".
2. No painel do Supabase, o objeto aparece em `product-files/products/<id do Produto>/<UUID>.pdf`.
3. Abrir a URL pública desse objeto **falha** (bucket privado).
4. Envie outro PDF: o objeto anterior some do bucket em até cerca de 1 hora (a fila de remoção o apaga depois de 15 minutos, na limpeza horária), para que um link de Download já emitido continue valendo até vencer.
5. Envie um arquivo de exatamente 50 MB (aceito) e um um pouco maior (recusado), para conferir o limite do Supabase de verdade.

## 4. Bucket das Capas

As imagens de capa dos Produtos (#21) ficam em um segundo bucket **privado**, `product-covers`, separado do `product-files`: assim cada bucket tem a sua trava no próprio Supabase (o `product-files` só aceita PDF de até 50 MB; o das capas só aceita imagem de até 2 MB). A aplicação escolhe o bucket pelo prefixo da chave (`products/...` ou `covers/...`) e usa a mesma secret key. O nome está no `application-prod.yml` (`app.storage.supabase.cover-bucket`). Crie o bucket uma vez por ambiente (homologação e, depois, produção):

1. Entre no projeto do ambiente em supabase.com e clique em **Storage** no menu da esquerda.
2. Clique em **New bucket**.
3. Em **Name of bucket**, digite exatamente `product-covers`.
4. Deixe **Public bucket** **desligado**.
5. Ligue **Restrict file upload size for bucket** e informe **2 MB**. Se o "2 MB" do painel do Supabase significar 2.000.000 de bytes, informe um limite um pouco maior (por exemplo, 3 MB), para que o limite da própria aplicação seja o que recusa.
6. Ligue **Restrict MIME types** e informe `image/png, image/jpeg, image/webp`.
7. Clique em **Save** (ou **Create**).
8. Nenhum segredo novo: a mesma secret key do `product-files` serve.

Se o bucket não for criado, a aplicação sobe normalmente (não há checagem na subida) e o Catálogo continua funcionando, com o quadro neutro em todos os Produtos. Só o envio de capa falha: o painel mostra que o sistema está indisponível e o log registra, já redigido, `Storage could not store the file: HTTP 400 (statusCode=404, error=Bucket not found, ...)`. Com o tipo de arquivo errado no bucket o log mostra `statusCode=415`; com limite de tamanho menor que 2 MB, `statusCode=413` para imagens grandes. Se o bucket for criado **público** por engano, a aplicação não percebe (ela nunca usa URL pública): a conferência abaixo (abrir a URL pública tem de falhar) pega isso.

### Conferir as capas na homologação

1. Envie uma capa de um Produto pelo painel.
2. No painel do Supabase, o objeto aparece em `product-covers/covers/<id do Produto>/<UUID>.<png|jpg|webp>`.
3. Abrir a URL pública desse objeto **falha** (bucket privado).
4. Troque a capa: o objeto antigo some do bucket.
5. Envie uma imagem de 2.097.152 bytes (2 MiB, aceita) e uma de 2.097.153 bytes (recusada), para conferir o limite do Supabase de verdade.
