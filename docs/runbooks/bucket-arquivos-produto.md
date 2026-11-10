# Bucket dos Arquivos do produto no Supabase Storage

Os PDFs dos Produtos ficam num bucket **privado** do Supabase Storage (ADR-0002, #18). O bucket é criado à mão, uma vez por ambiente (homologação e produção). Não coloque a secret key nem nenhum outro segredo em comando, ticket, conversa ou arquivo do repositório.

## 1. Criar o bucket

No painel do Supabase do ambiente, em **Storage**:

1. Crie um bucket chamado `product-files` (o nome está no `application-prod.yml`).
2. Deixe-o **privado**: a opção de bucket público fica desligada. Nenhuma URL pública é usada; o download (#33) vai usar URL assinada curta.
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
4. Envie outro PDF: o objeto anterior some do bucket.
5. Envie um arquivo de exatamente 50 MB (aceito) e um um pouco maior (recusado), para conferir o limite do Supabase de verdade.
