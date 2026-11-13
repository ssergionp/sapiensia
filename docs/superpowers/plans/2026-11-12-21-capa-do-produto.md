# Capa do Produto servida pela API (#21) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** AGUARDANDO APROVAÇÃO. As decisões que dependem do Sérgio estão na seção 9 (D1–D8) e os textos da tela na seção 7.

**Goal:** O Administrador envia, troca e remove a Capa de um Produto no painel; o Catálogo e a página do Produto a mostram, servida por um endpoint da API a partir de um bucket privado, sem mudar a CSP.

**Architecture:** A imagem é conferida **pelos bytes** (PNG, JPEG ou WebP; nunca decodificada no servidor), tem os metadados removidos sem recompressão e vai para o bucket privado novo `product-covers` com chave gerada pelo servidor. Uma tabela `product_covers` aponta para o objeto atual (formato, dimensões, tamanho e um hash curto que serve de ETag e de versão na URL). A troca segue o padrão do #18 (envio ao Storage fora da trava, troca do ponteiro numa transação curta, apagar o antigo depois do commit), com uma diferença pedida no ticket: todo objeto que pode ficar órfão é **registrado antes** numa tabela `pending_object_deletions`, e uma tarefa recorrente do db-scheduler (ADR-0005) termina a limpeza. `GET /api/products/{id}/cover` é público (só `GET`) e responde o mesmo `404` vazio do Catálogo para rascunho, despublicado, inexistente ou sem capa.

**Tech Stack:** Spring Boot 4.1 / Spring Security 7.1, JdbcClient, Flyway, db-scheduler (já no projeto), React + TanStack Query, Vitest, Playwright. **Nenhuma dependência nova** (sem TwelveMonkeys, sem biblioteca de imagem: o Java não lê WebP sem ela, e por isso a conferência é feita lendo o cabeçalho de cada formato à mão).

**Spec:** issue #21 (pai #5); `docs/spec/sapiensia-spec.md` (FR-016, SEC-017; também SEC-010, SEC-043, SEC-045, SEC-047, SEC-050, SEC-065); ADR-0002, ADR-0005, ADR-0007; planos do #18 (`2026-11-09-18-envio-pdf-produto.md`), #22 e #20; `GLOSSARY.md`.

## IDs cobertos

| ID | Como | Onde |
|---|---|---|
| FR-016 | Catálogo e página do Produto mostram a Capa (ou um quadro neutro) | Tasks 4, 6, 7 |
| SEC-017 | Capa servida pela própria API (`img-src 'self'` já cobre), CSP inalterada, `X-Content-Type-Options: nosniff`, `Content-Type` do formato conferido | Task 4 |
| SEC-010 | Rotas novas negadas por padrão; rascunho/despublicado/inexistente = mesmo `404` vazio; teste negativo de Visitante e Cliente | Task 4 |
| SEC-043, SEC-047 | Formato pelos bytes, tamanho em bytes, dimensões em pixels, sem decodificar (sem "bomba de pixels" no servidor), chave gerada pelo servidor | Tasks 1, 2 |
| SEC-050 | Temporários apagados sempre; objetos órfãos registrados e limpos | Task 3 |
| SEC-065 | Envio e remoção da Capa na auditoria (SUCCESS / FAILURE) | Task 3 |

## Global Constraints

- Nenhuma dependência nova. Uma migration (`V18__create_product_covers.sql`, via skill `nova-migration`).
- Todo endpoint começa com `/api`. Rotas de painel com `panel(Capability.PRODUCTS_EDIT)` (D4). A única rota pública nova é `GET /api/products/*/cover`, que entra no `PUBLIC_CATALOG` da `SecurityConfig` (mesmo `permitAll` e sem cookie XSRF) e vem com a atualização do ADR-0007 (regra do projeto: `permitAll` só com ADR).
- `CONTENT_SECURITY_POLICY` **não muda** (um teste já a fixa; ele continua igual). Nenhum bucket público, nenhuma URL do Storage sai do servidor.
- Limites (D2): `MAX_BYTES = 2_097_152` (2 MB), `MAX_SIDE = 4000` px em largura **e** altura, mínimo 1 px. Formatos: `image/png`, `image/jpeg`, `image/webp`. Animação (APNG `acTL`, WebP `ANIM`/`ANMF`/bit de animação) recusada.
- Nunca em log: secret key, URL do Storage, nome do bucket com caminho, chave do objeto, nome do arquivo enviado. Pode: id do Produto, nome da classe da exceção, status HTTP, contagens (mesma redação do #7/#100).
- Textos da tela: **só os da seção 7**. Código, commits e comentários em inglês.
- Teste que prova requisito leva o ID no `@DisplayName` / título. Integração contra PostgreSQL do Testcontainers; falhas com o dublê trocável `SwitchableProductFileStorage`, sem `@MockitoBean`.
- Toda nova propriedade `app.*` vai também para `backend/src/test/resources/application.properties`.
- Um comando pesado por vez; antes de `just check-resumo` e `just e2e`, conferir 2 GB livres (se não houver, parar e avisar).

## Review Focus

1. **JPEG com miniatura EXIF que contém outro JPEG** (marcadores `FFD8`/`FFD9` dentro do APP1): o percurso por segmentos usa o comprimento declarado e nunca procura marcador dentro dos dados; a miniatura some junto com o APP1. Teste na Task 1.
2. **Comprimento de chunk/segmento mentiroso** (maior que o resto do arquivo, ou negativo como `uint32`): recusa `COVER_INVALID`, nunca `ArrayIndexOutOfBounds` virando 500. Teste na Task 1 para os três formatos.
3. **Polyglot**: PNG/JPEG válido com HTML ou ZIP colado depois do `IEND`/`EOI` sai sem o rabo; servido com o tipo exato e `nosniff`. Teste na Task 1 (bytes depois do fim somem).
4. **Capa trocada enquanto o Produto é despublicado** e **cache do navegador**: `If-None-Match` igual em Produto despublicado responde `404`, não `304`. Teste na Task 4.
5. **Storage cai entre o envio e o commit**: o objeto novo fica registrado em `pending_object_deletions` e a tarefa o apaga; o objeto atual nunca é registrado. Teste na Task 3.

---

## 1. Onde a capa fica guardada (item 1 do pedido)

**Decisão (D1): bucket privado novo, `product-covers`.**

- O `product-files` hoje só aceita `application/pdf` e 50 MB. Para usar o mesmo, seria preciso **afrouxar** essa trava do painel para imagens, e ela deixaria de proteger os PDFs; o limite por arquivo também ficaria em 50 MB em vez de 2 MB. Com um bucket só para capas, cada um tem a sua trava no próprio Supabase como segunda camada (a primeira é a aplicação).
- Custo: um passo manual a mais por ambiente e uma propriedade. No código, **o prefixo da chave escolhe o bucket** dentro do `SupabaseProductFileStorage` (`products/…` → `product-files`, `covers/…` → `product-covers`): uma classe, um cliente HTTP, a mesma redação de log.
- Descartado: guardar a imagem no PostgreSQL (`bytea`). Seria transacional e sem órfãos, mas o Supabase Free tem 500 MB de banco e o ADR-0002 já diz que arquivos ficam no Storage.

**O que o Sérgio cria no painel do Supabase (homologação e, depois, produção)** — entra no runbook `docs/runbooks/bucket-arquivos-produto.md`, seção nova "Bucket das Capas":

1. Entre no projeto do ambiente em supabase.com e clique em **Storage** no menu da esquerda.
2. Clique em **New bucket**.
3. Em **Name of bucket**, digite exatamente `product-covers`.
4. Deixe **Public bucket** **desligado**.
5. Ligue **Restrict file upload size for bucket** e informe **2 MB**.
6. Ligue **Restrict MIME types** e informe `image/png, image/jpeg, image/webp`.
7. Clique em **Save** (ou **Create**).
8. Nenhum segredo novo: a mesma secret key do `product-files` serve.

**Se isso não for feito:** a aplicação sobe normalmente (não há checagem na subida) e o Catálogo segue funcionando, com o quadro neutro em todos os Produtos. Só o envio de capa falha: o painel mostra "O sistema está indisponível no momento e a capa não foi alterada…" (503) e o log registra, já redigido, `Storage could not store the file: HTTP 400 (statusCode=404, error=Bucket not found, …)`. Com o MIME errado no bucket, o log diz `statusCode=415`; com limite de tamanho menor que 2 MB, `statusCode=413` para imagens grandes. Se o bucket for criado **público** por engano, a aplicação não percebe (ela nunca usa URL pública); a conferência do runbook (abrir a URL pública tem de falhar) pega isso.

## 2. Validação (item 2)

Classe `CoverImageValidator` (sem Spring além de `@Component`), entrada `Path` do temporário, saída `CoverCheck`:

- **Formato só pelos bytes.** Nome do arquivo e `Content-Type` enviados são **ignorados** (diferente do `PdfFileValidator`, que também confere a extensão: aqui o ticket pede bytes, e a extensão não protege nada que a assinatura não proteja). Assinaturas: PNG `89 50 4E 47 0D 0A 1A 0A`; JPEG `FF D8 FF`; WebP `RIFF????WEBP`. Qualquer outra coisa (SVG, que é texto; GIF `GIF87a/GIF89a`; HEIC; BMP; PDF) → `COVER_NOT_IMAGE`.
- **Tamanho:** `Files.size > 2_097_152` → `COVER_TOO_LARGE` (413), antes de ler os bytes. Por que 2 MB: uma capa 1200×1800 em JPEG ou WebP qualidade 85 fica entre 200 e 700 KB; 2 MB ainda aceita PNG exportado do Canva em tamanho razoável e evita que uma página do Catálogo com 20 capas pese 100 MB no celular do Visitante. Como o arquivo cabe com folga na memória, o validador trabalha num `byte[]`.
- **Dimensões:** lidas do cabeçalho (PNG `IHDR`; JPEG segmento `SOFn`; WebP `VP8X` / `VP8 ` / `VP8L`), sem decodificar a imagem. Largura e altura entre 1 e 4000 px, senão `COVER_TOO_MANY_PIXELS`. Por que 4000: cobre com sobra a capa recomendada (1200×1800) e impressão em tela retina; o pior caso decodificado no navegador do Visitante é 4000×4000×4 = 64 MB, aceitável, enquanto um PNG de 30.000×30.000 (poucos KB comprimidos, 3,6 GB decodificado) é recusado. **No servidor não existe bomba de pixels**, porque nada é decodificado; por isso também não copiamos o semáforo e o timeout do `PdfFileValidator`: a leitura é linear num array de no máximo 2 MB.
- **Estrutura:** o validador percorre todos os chunks/segmentos até o fim (`IEND`, `EOI`, fim do RIFF). Comprimento que passa do arquivo, chunk crítico desconhecido no PNG, JPEG sem `SOFn` antes do `SOS`, WebP sem `VP8`/`VP8L` → `COVER_INVALID`. Animação → `COVER_ANIMATED`.
- **Metadados (EXIF, XMP, IPTC, comentários):** **removidos** (D3), sem recompressão, copiando só o que é preciso para exibir:
  - PNG: mantém `IHDR PLTE IDAT IEND tRNS gAMA cHRM sRGB iCCP sBIT bKGD pHYs cICP`; descarta os demais auxiliares (`tEXt zTXt iTXt eXIf tIME`…); descarta tudo depois do `IEND`.
  - JPEG: descarta `APP1` (EXIF/XMP), `APP3`–`APP13` (IPTC no `APP13`), `APP15` e `COM`; mantém `APP0` (JFIF), `APP2` só se começar com `ICC_PROFILE`, `APP14` (Adobe, muda as cores se sumir) e todo o resto; do primeiro `SOS` até o último `FFD9` copia como está; descarta o que vier depois do `EOI`.
  - WebP: descarta `EXIF`, `XMP ` e chunks desconhecidos; zera os bits EXIF (0x08) e XMP (0x04) do `VP8X`; recalcula o tamanho do `RIFF`.
  - Efeito colateral aceito: a orientação EXIF some, então uma **foto de celular** girada pode aparecer deitada. Capas costumam ser artes exportadas, não fotos; a ajuda da tela recomenda exportar a arte.
  - Por que remover e não guardar como veio: EXIF de foto de celular leva GPS e modelo do aparelho do Administrador ao público. Por que não recodificar: o Java não grava WebP sem dependência nova, e recodificar perde qualidade.
- **Saída:** `CoverCheck.Valid(CoverFormat format, int width, int height, byte[] cleaned)` ou `CoverCheck.Rejected(ProductCoverRejection reason)`. `CoverFormat` = `PNG("image/png","png")`, `JPEG("image/jpeg","jpg")`, `WEBP("image/webp","webp")`.

## 3. Entrega (item 3)

`GET /api/products/{id}/cover` (público, só `GET`):

- Busca numa só consulta a capa **de Produto publicado** (`join products … status = 'PUBLISHED'`). Rascunho, Despublicado, inexistente, sem capa ou id inválido → `404` **sem corpo**, igual ao Catálogo (`ProductNotFoundException`). Id que não é UUID → `400` como no Catálogo.
- `If-None-Match` igual ao ETag → `304` (sem ir ao Storage). A conferência de "publicado" vem **antes**, então um Produto despublicado nunca responde `304`.
- `200` com: `Content-Type` = `media_type` da tabela (veio do formato conferido, nunca do envio); `Content-Length`; `ETag: "<version>"` (16 hex do SHA-256 dos bytes limpos; não revela a chave do objeto); `Cache-Control: private, no-cache`; `X-Content-Type-Options: nosniff` (Spring Security já põe; o teste fixa).
- Por que `private, no-cache`: o navegador guarda a imagem e revalida a cada uso (resposta `304` barata, só banco); despublicar tira a capa na hora, como o ADR-0007 pede para o Catálogo; `private` impede cache compartilhado (proxy/CDN) de guardar capa de um Produto que depois sai do ar. A URL leva `?v=<version>` (ignorado pelo servidor) para o navegador trocar de imagem logo depois de uma troca de capa.
- Objeto faltando no Storage (linha existe, objeto não) → `404` vazio + `log.warn("Product cover object missing (product {})", id)`. Storage fora → `503` sem corpo. A CSP já tem `img-src 'self'`, então nada muda nela.
- Painel: `GET /api/admin/products/{id}/cover` (mesma resposta, sem o filtro de publicado) para a prévia do rascunho.

## 4. Troca e remoção (item 4)

Tabela `pending_object_deletions (object_key varchar(120) primary key, created_at timestamptz not null default now())`: **toda chave que pode virar órfã é gravada antes** de existir no Storage ou antes de deixar de ser a atual.

Envio (`ProductCoverService.upload`, fora de transação, como o #18):
1. Produto existe? senão `404`. Valida (`CoverImageValidator`); recusa → auditoria FAILURE, `400`/`413`.
2. Grava os bytes limpos num temporário próprio (apagado no `finally`).
3. Transação curta: `insert pending(newKey)`.
4. `storage.put(newKey, temp, size, format.mediaType())`.
5. Transação: trava a linha do Produto; lê a capa atual; `upsert product_covers`; `delete pending(newKey)`; se havia antiga, `insert pending(oldKey)`; auditoria SUCCESS.
6. Depois do commit: `storage.delete(oldKey)` e, se deu certo, `delete pending(oldKey)`.
7. Falha em 4 ou 5: `storage.delete(newKey)` com melhor esforço; se deu certo, `delete pending(newKey)`; senão a linha fica.

Remoção (`DELETE`): transação trava, lê a atual, apaga a linha de `product_covers`, `insert pending(oldKey)`, auditoria; depois do commit apaga o objeto e a linha pendente. Sem capa → `204` mesmo assim (idempotente).

Tarefa recorrente `delete-orphan-cover-objects` (db-scheduler, a cada 1 h): pega as pendências com mais de 15 min (bem acima dos 60 s de leitura do Storage, então nunca pega um envio em andamento), confere que a chave **não** está em `product_covers` (defesa extra), apaga o objeto (`404` do Storage conta como apagado) e a linha; loga só as contagens. Resíduo aceito (o mesmo do #18): se o commit do passo 5 falhar *depois* de o banco ter gravado, o passo 7 apaga o objeto atual. Os PDFs do #18 continuam como estão (fora do escopo; vira sugestão de issue).

## 5. Autorização (item 5)

- Painel, todas com `panel(Capability.PRODUCTS_EDIT)` (D4): `POST /api/admin/products/*/cover` (multipart, campo `file`), `DELETE /api/admin/products/*/cover`, `GET /api/admin/products/*/cover`. **Sem reautenticação** para Produto publicado: a Capa é apresentação, como o texto, que também se edita sem reautenticar; o motivo do #22 ("é o que o Cliente recebe") não vale aqui.
- Público: `GET /api/products/*/cover` no `PUBLIC_CATALOG` (o mesmo matcher abre a rota e evita o cookie XSRF). `PUT`, `POST`, `DELETE` nessa rota continuam negados.
- Testes negativos: Visitante e Cliente logado em `POST`/`DELETE`/`GET` do painel (com token CSRF válido) → `401` / `403` e nada muda; Visitante e Cliente em `GET /api/products/{rascunho}/cover` → `404` vazio.

## 6. Tela (item 6)

- Painel: seção nova `ProductCoverSection` em `frontend/src/features/products/`, renderizada no `ProductFormPage` entre os dados do Produto e o `ProductFileSection` (mesmo layout, `role="alert"` nos erros, `role="status"` no sucesso). Prévia da capa atual pela rota do painel.
- Público: componente `CoverImage` em `frontend/src/features/catalog/CoverImage.tsx` (`{ src: string | null; alt: string; className?: string }`): `<img loading="lazy" decoding="async">` com proporção 2:3 e `object-contain` sobre fundo neutro (nada é cortado); sem `src`, ou se a imagem falhar (`onError`), mostra o **quadro neutro**: um `div` com a mesma proporção, `bg-muted`, `aria-hidden`, sem texto (D6). Usado no card do `CatalogPage` (acima do título) e no `CatalogProductPage` (ao lado do título no desktop, acima no celular), e na prévia do painel.
- API: `CatalogItem` e `CatalogProduct` ganham `coverVersion: string | null`; `ProductResponse` ganha `cover: { mediaType, sizeBytes, width, height, version, uploadedAt } | null`. URL montada no front: `/api/products/${id}/cover?v=${coverVersion}`.
- Glossário: termo novo **Capa** ("imagem de apresentação de um Produto, mostrada no Catálogo e na página do Produto; não é entregue ao Cliente") e a frase "(a capa ainda não existe)" do verbete Catálogo sai.

## 7. Textos que o usuário vê (todos)

Painel (`PRODUCT_COVER_TEXTS` / `COVER_MESSAGES` em `products/messages.ts`):

| # | Onde | Texto |
|---|---|---|
| 1 | Título da seção | Capa do produto |
| 2 | Rótulo do campo | Imagem da capa |
| 3 | Ajuda | PNG, JPEG ou WebP, até 2 MB e até 4.000 pixels de largura e de altura. Recomendado: 1200 × 1800 pixels. |
| 4 | Ajuda | Enviar outra imagem substitui a atual. Os dados da foto (como local e aparelho) são removidos. |
| 5 | Sem capa | Nenhuma capa enviada. |
| 6 | Com capa | Capa atual: {largura} × {altura} pixels, {X,Y} MB, enviada em {dd/mm/aaaa} às {hh:mm}. |
| 7 | Texto alternativo da prévia | Capa de {título} |
| 8 | Botão | Enviar capa |
| 9 | Botão pendente | Enviando capa… |
| 10 | Sucesso | Capa enviada. |
| 11 | Botão | Remover capa |
| 12 | Confirmação | Remover a capa deste Produto? O Catálogo passa a mostrar um quadro sem imagem. |
| 13 | Botão da confirmação | Confirmar remoção |
| 14 | Botão pendente | Removendo capa… |
| 15 | Botão | Cancelar |
| 16 | Sucesso | Capa removida. |
| 17 | `COVER_REQUIRED` | Selecione uma imagem. |
| 18 | `COVER_EMPTY` | A imagem está vazia. |
| 19 | `COVER_TOO_LARGE` | A imagem passa de 2 MB. Envie uma imagem menor, de preferência em JPEG ou WebP. |
| 20 | `COVER_NOT_IMAGE` | A imagem precisa ser PNG, JPEG ou WebP. SVG e GIF não são aceitos. |
| 21 | `COVER_INVALID` | Não foi possível ler esta imagem. Ela pode estar corrompida. Exporte a imagem de novo e tente outra vez. |
| 22 | `COVER_TOO_MANY_PIXELS` | A imagem passa de 4.000 pixels de largura ou de altura. Reduza a imagem e envie de novo. |
| 23 | `COVER_ANIMATED` | Imagens animadas não são aceitas. Envie uma imagem parada. |
| 24 | 403 | Você não tem permissão para alterar a capa do Produto. |
| 25 | 503 | O sistema está indisponível no momento e a capa não foi alterada. Tente novamente em alguns instantes. |
| 26 | Outro erro | Não foi possível alterar a capa. Verifique a conexão e tente novamente. |
| 27 | 404 | Produto não encontrado. (já existe) |

Público: **28** — texto alternativo da capa no card e na página do Produto: `Capa de {título}`. O quadro neutro não tem texto.

## 8. Logs (item 8)

- `SupabaseProductFileStorage`: a lista do que se apaga do corpo de erro passa a incluir os caminhos dos **dois** buckets (`/storage/v1/object/product-covers/`, `/storage/v1/object/authenticated/product-covers/`) e a chave; o resto da redação do #7/#100 (secret, URL, host, padrão de segredo, padrão de nome de arquivo, 200 caracteres) vale igual.
- Serviço e controller: só id do Produto e nome da classe (`Product cover object could not be deleted (product {}): {}`); a tarefa: `Deleted {} orphan cover objects ({} failed)`.
- O nome enviado do arquivo **não é lido** em lugar nenhum.

## 9. Decisões para o Sérgio

- **D1** Bucket novo `product-covers` (seção 1) e o passo a passo do painel.
- **D2** Limites: 2 MB e 4.000 px por lado.
- **D3** Remover EXIF/XMP/IPTC sem recompressão; aceitar que a orientação de foto de celular se perca.
- **D4** Capacidade `PRODUCTS_EDIT` (não uma nova `PRODUCTS_COVER`) e **sem** reautenticação para Produto publicado.
- **D5** Remoção idempotente (`204` sem capa) e com confirmação na tela.
- **D6** Visual: proporção 2:3, imagem inteira sem corte (`object-contain`), quadro neutro cinza sem texto; capa acima do título no card.
- **D7** Todos os textos da seção 7.
- **D8** ADR: atualizar o ADR-0007 (parágrafo novo "Capa pública") e o ADR-0002 (bucket `product-covers` e o registro de órfãos), em vez de um ADR novo.

## 10. O que não será provado (item 9)

- **Supabase de verdade:** CI e e2e usam o stub `.github/supabase-storage-stub.mjs`; os testes do adaptador usam respostas simuladas. O formato exato da rota de leitura (`GET /storage/v1/object/authenticated/<bucket>/<key>`) é conferido na documentação oficial na Task 2, mas o comportamento real (bucket inexistente, MIME e limite do bucket) só se prova na homologação, pelo checklist do runbook.
- **Decodificação no navegador de todos os formatos limpos:** o e2e prova PNG e WebP (com chunk EXIF removido) no Chromium; JPEG limpo é provado decodificando com `ImageIO` no teste do validador. Safari/Firefox não são testados.
- **Polyglots mais exóticos** (JPEG que também é um HTML válido *no meio* dos dados de imagem): a resposta tem tipo exato e `nosniff`, que é o que impede o navegador de interpretá-los; não há CDR (recodificação) — SEC-044 fica parcial, como no #18.
- **Antimalware (SEC-044):** fora, como no #18.
- **Carga:** a leitura do Storage carrega até 2 MB por requisição em memória; o `304` evita isso nas visitas repetidas. Sem teste de carga; limite de taxa por rota continua no #42.
- **Bucket criado público por engano:** a aplicação não detecta; só o checklist do runbook.

---

### Task 1: `CoverImageValidator` (formato, tamanho, dimensões, metadados)

**Files:**
- Create: `backend/src/main/java/br/com/sergio/sapiensia/product/CoverImageValidator.java`, `CoverCheck.java`, `CoverFormat.java`, `ProductCoverRejection.java`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/product/CoverImageValidatorTest.java`, `TestImages.java`

**Interfaces:**
- Produces: `CoverCheck check(Path file)`; `sealed interface CoverCheck { record Valid(CoverFormat format, int width, int height, byte[] cleaned); record Rejected(ProductCoverRejection reason); }`; `enum CoverFormat { PNG, JPEG, WEBP; String mediaType(); String extension(); }`; `enum ProductCoverRejection { COVER_REQUIRED, COVER_EMPTY, COVER_TOO_LARGE, COVER_NOT_IMAGE, COVER_INVALID, COVER_TOO_MANY_PIXELS, COVER_ANIMATED }`; constantes `MAX_BYTES = 2_097_152L`, `MAX_SIDE = 4000`.
- `TestImages` (test): `png(int w, int h)` e `jpeg(int w, int h)` via `ImageIO`; `webpLossless1x1()` (constante base64 `UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==`); `withPngChunk(byte[] png, String type, byte[] data)`; `withJpegSegment(byte[] jpeg, int marker, byte[] data)`; `webpVp8xWith(byte[] vp8l, boolean exif, boolean animated)`; `pngHeaderOnly(int w, int h)` (IHDR com dimensões enormes e um IDAT mínimo).

- [ ] **Step 1: Testes que falham** — `@DisplayName` com `SEC-043`/`SEC-047`:
  - aceita PNG 10×20, JPEG 10×20, WebP 1×1 → `Valid` com formato e dimensões certos;
  - ignora o nome/tipo: o mesmo PNG gravado como `capa.jpg` é `PNG` (o método nem recebe nome);
  - SVG (`<svg xmlns=…>`), GIF (`GIF89a…`), PDF (`%PDF-1.7`), BMP → `COVER_NOT_IMAGE`; arquivo vazio → `COVER_EMPTY`;
  - 2_097_152 bytes passa da checagem de tamanho; 2_097_153 → `COVER_TOO_LARGE`;
  - PNG `pngHeaderOnly(4001, 10)`, `(10, 4001)`, `(30000, 30000)` → `COVER_TOO_MANY_PIXELS`; `4000×4000` passa; JPEG com `SOF0` 0×10 → `COVER_INVALID`;
  - PNG com `acTL` e WebP com bit de animação / chunk `ANIM` → `COVER_ANIMATED`;
  - comprimento de chunk/segmento maior que o arquivo (os três formatos), PNG truncado sem `IEND`, JPEG sem `SOF` → `COVER_INVALID` (nunca exceção);
  - limpeza: PNG com `tEXt`/`eXIf` sai sem eles e com os mesmos `IDAT`; bytes depois do `IEND` somem; JPEG com `APP1` "Exif" (com miniatura JPEG dentro) e `COM` sai sem eles, com `APP0` e `APP2 ICC_PROFILE` mantidos, e `ImageIO.read(cleaned)` decodifica com as mesmas dimensões; WebP `VP8X` com chunk `EXIF` sai sem o chunk, com o bit 0x08 zerado e o tamanho do RIFF = `cleaned.length - 8`.
- [ ] **Step 2: Rodar e ver falhar** — `cd backend; .\mvnw.cmd test -Dtest=CoverImageValidatorTest` → falha de compilação.
- [ ] **Step 3: Implementar** — um método privado por formato (`png`, `jpeg`, `webp`), cada um percorrendo o array com índice e comprimentos conferidos (`long` para os `uint32`), devolvendo `Valid` ou `Rejected`. Nenhuma chamada a `ImageIO` no código de produção.
- [ ] **Step 4: Rodar e ver passar** — mesmo comando → PASS.
- [ ] **Step 5: Commit** — `feat: validate and clean product cover images by their bytes (#21)`

### Task 2: Storage com tipo, leitura e bucket das capas

**Files:**
- Modify: `ProductFileStorage.java`, `SupabaseProductFileStorage.java`, `FileProductFileStorage.java`, `ProductFileKeys.java`, `ProductFileService.java` (passa `MediaType.APPLICATION_PDF`), `backend/src/main/resources/application-prod.yml`, `.github/supabase-storage-stub.mjs`, `docs/runbooks/bucket-arquivos-produto.md`
- Test: `SupabaseProductFileStorageTest.java`, `SupabaseProductFileStorageWireTest.java`, `SupabaseProductFileStorageConfigurationTest.java`, `FileProductFileStorageTest.java`, `SwitchableProductFileStorage.java`

**Interfaces:**
- Produces: `void put(String key, Path file, long sizeBytes, MediaType type)`; `Optional<byte[]> get(String key)` (vazio = objeto não existe; lê no máximo `CoverImageValidator.MAX_BYTES + 1` e falha com `ProductFileStorageException` acima disso); `ProductFileKeys.newCoverKey(UUID productId, CoverFormat format)` → `covers/<productId>/<uuid>.<ext>`; `ProductFileKeys.isValid` aceita também `^covers/[0-9a-f-]{36}/[0-9a-f-]{36}\.(png|jpg|webp)$`. Propriedade `app.storage.supabase.cover-bucket` (`product-covers` no `application-prod.yml`).

- [ ] **Step 1: Conferir a API** na documentação oficial do Supabase Storage: rota de download autenticado com a secret key (`GET /storage/v1/object/authenticated/{bucket}/{path}`) e o corpo de erro de objeto inexistente. Anotar o link no comentário da classe.
- [ ] **Step 2: Testes que falham** (wire test com servidor HTTP local, como os existentes):
  - `put` de chave `covers/…` vai para `/storage/v1/object/product-covers/…` com o `Content-Type` passado; `products/…` continua em `product-files` com `application/pdf`;
  - `get` devolve os bytes; `404` HTTP ou `400` com `statusCode` `"404"` no corpo → `Optional.empty()`; `500` → `ProductFileStorageException`; corpo maior que `MAX_BYTES + 1` → exceção;
  - corpo de erro que repete a URL, a chave, o caminho do bucket de capas e um `sb_secret_…` montado em tempo de execução → o log não contém nenhum deles (SEC-047/#7);
  - configuração: sem `cover-bucket` no perfil supabase o contexto não sobe;
  - disco: `put`/`get`/`delete` de chave de capa; chave fora do padrão → exceção.
- [ ] **Step 3: Rodar e ver falhar** — `.\mvnw.cmd test -Dtest='SupabaseProductFileStorage*Test,FileProductFileStorageTest'`.
- [ ] **Step 4: Implementar.** Bucket escolhido pelo prefixo da chave (`products/` ou `covers/`). Stub do CI: aceitar `GET` em `/storage/v1/object/authenticated/<bucket>/<key>`, guardar e devolver o `content-type` do envio. `SwitchableProductFileStorage` ganha `get` (com `failGets`). Runbook: seção "Bucket das Capas" (passos da seção 1 do plano) e checklist de homologação (enviar capa, ver o objeto em `product-covers/covers/<id>/…`, URL pública falha, trocar e ver o antigo sumir, imagem de 2 MB aceita e maior recusada).
- [ ] **Step 5: Rodar e ver passar**, inclusive `ProductFileServiceTest` e `ProductFileControllerTest` (o `put` mudou de assinatura).
- [ ] **Step 6: Commit** — `feat: read objects and route product covers to their own bucket (#21)`

### Task 3: Tabelas, serviço da Capa, órfãos registrados e auditoria

**Files:**
- Create: `backend/src/main/resources/db/migration/V18__create_product_covers.sql` (skill `nova-migration`), `ProductCovers.java` (repositório), `PendingObjectDeletions.java`, `ProductCoverService.java`, `ProductCoverCleanup.java` (tarefa recorrente, padrão do `AuditRetentionCleanup`), `ProductCoverInfo.java`
- Modify: `AuditAction.java` (+ `ADMIN_PRODUCT_COVER_UPLOADED`, `ADMIN_PRODUCT_COVER_REMOVED`), `Products.java` (`find` preenche `cover`), `ProductResponse.java` (+ `ProductCoverInfo cover`), `application.yml` e test `application.properties` (`app.product-cover.orphan-min-age: PT15M`)
- Test: `ProductCoverServiceTest.java`, `ProductCoverCleanupTest.java`

**Interfaces:**
- Migration: `product_covers (product_id uuid pk references products(id), object_key varchar(120) not null unique check (padrão de capa), media_type varchar(16) not null check (in 'image/png','image/jpeg','image/webp'), size_bytes integer not null check (between 1 and 2097152), width integer not null check (between 1 and 4000), height integer idem, version char(16) not null, uploaded_at timestamptz not null default now())`; `pending_object_deletions (object_key varchar(120) primary key, created_at timestamptz not null default now())`.
- Produces: `ProductResponse upload(UUID productId, Path tempFile, UUID administratorId, String origin)`; `void remove(UUID productId, UUID administratorId, String origin)`; `Optional<StoredCover> publishedCover(UUID productId)` e `Optional<StoredCover> anyCover(UUID productId)` com `record StoredCover(String objectKey, String mediaType, String version)`; `byte[] read(StoredCover)` (lança `ProductCoverMissingException` se o Storage não tem o objeto); `record ProductCoverInfo(String mediaType, int sizeBytes, int width, int height, String version, Instant uploadedAt)`; `int ProductCoverCleanup.Cleaner.deleteOrphans()`.

- [ ] **Step 1: Testes que falham** (Testcontainers, `SwitchableProductFileStorage`, `@DisplayName` com IDs):
  - envio grava a linha com formato, dimensões, tamanho dos bytes **limpos** e `version` = 16 hex do SHA-256; objeto existe; `pending_object_deletions` vazia; auditoria SUCCESS;
  - troca: objeto antigo some, linha aponta para o novo, pendências vazias;
  - `failPuts`: nenhuma linha de capa, pendência do `newKey` registrada **só** se o delete de limpeza falhar (`failDeletes` junto) — senão vazia (SEC-050);
  - falha na transação (Produto removido entre a validação e a trava, via o `Pause` do dublê) → objeto novo apagado; com `failDeletes`, pendência registrada;
  - delete do antigo falha depois do commit → linha nova vale e o `oldKey` fica em pendências;
  - a chave atual **nunca** aparece em pendências em nenhum desses cenários;
  - recusa do validador → auditoria FAILURE, nada no Storage;
  - remoção: linha some, objeto some, auditoria `ADMIN_PRODUCT_COVER_REMOVED`; remover sem capa não falha; Produto inexistente → `ProductNotFoundException`;
  - limpeza: pendência com `created_at` de 16 min é apagada do Storage e da tabela; de 5 min fica; pendência cuja chave está em `product_covers` não é apagada; `get` faltando conta como apagado.
- [ ] **Step 2: Rodar e ver falhar** — `.\mvnw.cmd test -Dtest='ProductCoverServiceTest,ProductCoverCleanupTest'`.
- [ ] **Step 3: Implementar** seguindo a seção 4 do plano; transações com `TransactionTemplate` e o mesmo `Assert.state(!isActualTransactionActive())` do `ProductFileService`; tarefa `Tasks.recurring("delete-orphan-cover-objects", Schedules.fixedDelay(Duration.ofHours(1)))`.
- [ ] **Step 4: Rodar e ver passar** — mesmo comando, mais `ProductFileServiceTest` e os testes de auditoria (`git grep -l ADMIN_PRODUCT -- backend/src/test`).
- [ ] **Step 5: Commit** — `feat: store, replace and remove product covers without unrecorded orphans (#21)`

### Task 4: Endpoints, segurança, Catálogo e ADRs

**Files:**
- Create: `ProductCoverController.java` (painel), `CatalogCoverController.java` (público)
- Modify: `SecurityConfig.java` (`PUBLIC_CATALOG` + 3 regras do painel), `CatalogItem.java`, `CatalogProduct.java`, `Products.java` (`left join product_covers` em `listPublished`/`findPublished` → `coverVersion`), `docs/adr/0007-modelo-comercial-da-fase-1.md`, `docs/adr/0002-banco-e-arquivos-no-supabase-com-backup-no-r2.md`
- Test: `CatalogCoverTest.java`, `ProductCoverControllerTest.java`, `CatalogTest.java` (campo novo), o teste existente da CSP (precisa continuar verde **sem** alteração)

**Interfaces:**
- `POST /api/admin/products/{id}/cover` multipart `file` → `200 ProductResponse`; `400 {"errors":{"cover":CODE}}`; `413 COVER_TOO_LARGE` (inclusive `MaxUploadSizeExceededException`); `404`; `503` sem corpo. Controller copia a parte para temporário (`Files.createTempFile("product-cover-", ".tmp")`) e apaga no `finally`; nunca chama `getOriginalFilename()` nem `getContentType()`.
- `DELETE /api/admin/products/{id}/cover` → `204`; `404`.
- `GET /api/admin/products/{id}/cover` e `GET /api/products/{id}/cover` → seção 3 do plano.
- `CatalogItem(…, String coverVersion)`, `CatalogProduct(…, String coverVersion)`.

- [ ] **Step 1: Testes que falham** (servidor real em porta aleatória, `HttpClient`, como o `CatalogTest`; títulos com `FR-016`, `SEC-017`, `SEC-010`):
  - Visitante: capa de Produto publicado → `200`, `Content-Type` igual ao formato **conferido** (envio de um PNG com `Content-Type: image/gif` e nome `x.svg` → servido como `image/png`), `X-Content-Type-Options: nosniff`, `Cache-Control: private, no-cache`, `ETag` = `"<version>"`, corpo = bytes limpos, **sem** `Set-Cookie`, e o `Content-Security-Policy` igual à constante atual;
  - `If-None-Match` igual → `304` sem corpo; Produto despublicado com o mesmo `If-None-Match` → `404` vazio;
  - rascunho, despublicado, inexistente e publicado sem capa → `404` **com corpo vazio e cabeçalhos iguais entre si**; id `abc` → `400`;
  - Cliente logado: mesmo `404` para a capa do rascunho;
  - Visitante e Cliente (com CSRF válido) em `POST`, `DELETE` e `GET` do painel → `401` / `403`, capa inalterada; `PUT`/`POST`/`DELETE` em `/api/products/{id}/cover` → negados;
  - Administrador: envio, prévia do rascunho pela rota do painel, remoção; erros 400/413/503 com o código certo; temporário apagado em todos os casos;
  - `GET /api/products` e `/api/products/{id}` trazem `coverVersion` (`null` sem capa).
- [ ] **Step 2: Rodar e ver falhar** — `.\mvnw.cmd test -Dtest='CatalogCoverTest,ProductCoverControllerTest,CatalogTest'`.
- [ ] **Step 3: Implementar.** Se o Spring Security sobrescrever o `Cache-Control` do controller, ajustar só para essa rota (sem mexer no padrão das demais). ADR-0007: parágrafo "Capa pública (#21)" (rota, `404` igual, `private, no-cache` + ETag, por que é `permitAll`); ADR-0002: bucket `product-covers` e `pending_object_deletions`.
- [ ] **Step 4: Rodar e ver passar** — mesmo comando, mais o teste da CSP/cabeçalhos (`git grep -l CONTENT_SECURITY_POLICY -- backend/src/test`) e os que citam SEC-017 (`git grep -l SEC-017 -- backend/src/test`).
- [ ] **Step 5: Commit** — `feat: serve product covers through the API (#21)`

### Task 5: Painel — seção "Capa do produto"

**Files:**
- Create: `frontend/src/features/products/ProductCoverSection.tsx`, `ProductCoverSection.test.tsx`, `frontend/src/features/catalog/CoverImage.tsx`, `CoverImage.test.tsx`
- Modify: `products/api.ts` (`uploadProductCover`, `removeProductCover`), `products/hooks.ts` (`useUploadProductCover`, `useRemoveProductCover`: mesmo padrão do `useUploadProductFile`), `products/schemas.ts` (`cover` em `productSchema`; `COVER_REJECTION_CODES`), `products/messages.ts` (textos 1–27), `products/testing.tsx` (`product()` com `cover: null`), `ProductFormPage.tsx`

**Interfaces:**
- `CoverImage({ src, alt, className })` (seção 6). `uploadProductCover(id: string, file: File): Promise<Product>`, `removeProductCover(id: string): Promise<void>`; erros `ProductCoverRejectedError(code)`.
- Pré-checagem no cliente: sem arquivo → 17; `size > 2_097_152` → 19; `type` fora de `image/png|image/jpeg|image/webp` → 20 (o servidor decide de verdade). `accept="image/png,image/jpeg,image/webp"`.

- [ ] **Step 1: Testes que falham** (Vitest, `mockApi` de `products/testing`): renderiza 1–5 sem capa; com capa mostra 6 e a prévia com alt 7 e `src` `/api/admin/products/<id>/cover?v=<version>`; envio mostra 9 e depois 10; cada código 17–23 mostra o texto certo em `role="alert"`; 403/503/rede → 24/25/26; remover pede 12, `Cancelar` não chama a API, `Confirmar remoção` chama `DELETE` e mostra 16; `CoverImage` com `src` null e depois de `onError` mostra o quadro `aria-hidden` sem `img`.
- [ ] **Step 2: Rodar e ver falhar** — `cd frontend; pnpm vitest run src/features/products/ProductCoverSection.test.tsx src/features/catalog/CoverImage.test.tsx`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** — mesmo comando.
- [ ] **Step 5: `/verificar-tela`** na tela "Editar Produto" (desktop e celular, teclado).
- [ ] **Step 6: Commit** — `feat: upload, replace and remove the product cover in the panel (#21)`

### Task 6: Catálogo e página do Produto com a Capa

**Files:**
- Modify: `frontend/src/features/catalog/schemas.ts` (`coverVersion: z.string().nullable()`), `CatalogPage.tsx`, `CatalogProductPage.tsx`, `CatalogPage.test.tsx`, `CatalogProductPage.test.tsx`

- [ ] **Step 1: Testes que falham:** item com `coverVersion` mostra `img` com alt `Capa de {título}` e `src` `/api/products/<id>/cover?v=<v>`; sem capa mostra o quadro neutro e nenhuma `img`; o mesmo na página do Produto.
- [ ] **Step 2: Rodar e ver falhar** — `pnpm vitest run src/features/catalog`.
- [ ] **Step 3: Implementar** com o `CoverImage` da Task 5 (`width`/`height` fixos na proporção 2:3 para a página não pular).
- [ ] **Step 4: Rodar e ver passar.**
- [ ] **Step 5: `/verificar-tela`** em `/catalogo` e `/catalogo/:id`.
- [ ] **Step 6: Commit** — `feat: show product covers in the Catalog (#21)`

### Task 7: e2e, glossário e sistema de verdade

**Files:**
- Create: `frontend/e2e/image.ts` (`buildPng(w, h)` sem dependência — PNG mínimo com zlib do Node; `buildWebpWithExif()` a partir da constante 1×1 do Task 1 dentro de um `VP8X` com chunk `EXIF`)
- Modify: `frontend/e2e/admin-panel.e2e.ts`, `GLOSSARY.md`

- [ ] **Step 1: Teste e2e** `FR-016 SEC-017 SEC-010: capa enviada no painel aparece no Catálogo e capa de rascunho não é servida` (sem `page.route`, sem mocks), depois do teste de publicação existente, reaproveitando `loginWithStoredCredentials` e a criação de Produto pela tela:
  1. cria Produto, envia `buildWebpWithExif()` em "Imagem da capa" → "Capa enviada." e a prévia carrega (`naturalWidth > 0`);
  2. Visitante (contexto novo): `request.get('/api/products/<id>/cover')` → `404` e corpo vazio (rascunho);
  3. publica; Visitante em `/catalogo` vê `img` com alt `Capa de <título>` carregada (`naturalWidth > 0`), e a resposta tem `content-type: image/webp`, `nosniff` e corpo **sem** a string `Exif`;
  4. troca por `buildPng(12, 18)` → a página do Produto mostra a nova (`naturalWidth === 12`);
  5. remove → o Catálogo mostra o quadro neutro e a rota responde `404`.
- [ ] **Step 2: Glossário** — termo **Capa** e verbete Catálogo (seção 6).
- [ ] **Step 3: Verificação completa** — conferir 2 GB livres; `just check-resumo`; conferir 2 GB livres; com `just db` (na pasta principal), `just dev-back` e `just dev-front`, rodar `just e2e` e conferir o fluxo à mão no navegador (envio, Catálogo, troca, remoção).
- [ ] **Step 4: Commit** — `test: cover the product cover flow end to end (#21)`
- [ ] **Step 5:** skill `verificar-entrega`; push só com `git push -u origin feat/21-capa-do-produto` e PR com `Closes #21`, depois do OK.
