# Envio do PDF do Produto (#18) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** APROVADO em 10/11/2026, com as decisões e ajustes abaixo (prevalecem sobre o texto do plano quando diferirem).

**Decisões aprovadas:**
- D0: PDFBox 3.0.8 aprovado. Publicação conferida no Maven Central (Last-Modified do .pom: 08/07/2026). Sem BouncyCastle nem xmpbox na árvore.
- D1 (a): enviar e substituir sem reautenticação enquanto o Produto nunca foi publicado. Linha nova no ADR-0004.
- D2: sem versão otimista; a trava nunca fica aberta durante a rede: (1) validar fora de transação; (2) subir ao Storage com nome novo gerado pelo servidor; (3) transação curta com FOR UPDATE só para trocar o ponteiro; (4) apagar o objeto antigo depois do commit, sem derrubar a resposta se falhar. Teste de cada falha.
- D3 parser tolerante; D4 `application/pdf`, `application/x-pdf`, `application/octet-stream`; D5 sem recusa de conteúdo ativo (fica para o #79, os textos condicionais não entram); D6 textos da seção 7 exatamente como estão, "enviado em" no fuso America/Sao_Paulo; D7 20 s e uma validação por vez, tempo em `app.product-file.validation-timeout` (também no application.properties de teste); D8 ticket #79 já criado (a Task 6 não cria o ticket SEC-044/045).

**Ajustes obrigatórios:** 50 MB = 52.428.800 bytes, fronteira testada (exato passa, +1 recusa, corte durante o recebimento); perfil `prod` falha ao iniciar com o adaptador de disco (com teste); temporário apagado em `finally` em toda saída (com teste); dependency:tree no PR e Trivy/SBOM sem achado novo; validador só com as chamadas da seção 2 e teste de zero conexões de rede.

**Goal:** O Administrador envia o Arquivo do produto de um Produto em rascunho. O servidor aceita só PDF de verdade, até 50 MB e 1.000 páginas, guarda o arquivo num bucket privado do Supabase Storage atrás de um adaptador e mantém uma só versão atual por Produto.

**Architecture:** Rota nova `POST /api/admin/products/{id}/file` (multipart), liberada pela capacidade nova `PRODUCTS_FILE`. O Tomcat corta o corpo acima do limite durante o recebimento; o arquivo vai para um temporário em disco, passa pela validação em ordem barata primeiro (o PDFBox só entra no fim, com tempo limitado e uma validação por vez) e então sobe para o Storage com um nome gerado pelo servidor. A tabela `product_files` (uma linha por Produto) aponta para o objeto atual; o objeto anterior é apagado depois do commit.

**Tech Stack:** Spring Boot 4.1.2 (multipart do Servlet/Tomcat, `RestClient`), JdbcClient, Flyway, **Apache PDFBox 3.0.8 (dependência nova, depende do OK)**, React + TanStack Query, Vitest, Playwright.

**Spec:** `docs/spec/sapiensia-spec.md` (SEC-043, SEC-046, SEC-047, FR-034); issue #18 (pai #5); `docs/adr/0002-banco-e-arquivos-no-supabase-com-backup-no-r2.md`; `docs/adr/0004-identidade-e-mfa.md`; `docs/adr/0006-meta-de-seguranca-asvs-nivel-2.md`; `GLOSSARY.md` ("Arquivo do produto", "Rascunho"); plano do #17 (`2026-11-09-17-cadastro-produto-admin.md`).

## IDs cobertos

| ID | Como | Onde |
|---|---|---|
| SEC-043 | Extensão, MIME real, assinatura, tamanho e número de páginas, nessa ordem barata (seção 3) | Tasks 1 e 4 |
| SEC-046 | O PDF nunca é renderizado nem interpretado: só o parser estrutural e a árvore de páginas; nada de JavaScript, ações, links, fontes, XFA ou anexos (seção 2) | Task 1 |
| SEC-047 | Nome do objeto gerado pelo servidor (path traversal); limite de tempo, uma validação por vez e leitura do disco sem carregar tudo (bomba e exploração do parser); nenhuma chamada de rede do parser (SSRF); XFA/XMP nunca lidos (XXE) | Tasks 1, 2 e 3 |
| FR-034 | **Só a base:** bucket privado, sem URL pública permanente, chave do objeto fora da resposta da API. A URL assinada curta é do #33 | Task 2 |
| SEC-010, SEC-017 | Rota negada por padrão, liberada só com `PRODUCTS_FILE` e os dois fatores; CSRF exigido | Task 4 |
| SEC-011 | Produto não tem dono; resposta da rota negada igual exista o Produto ou não; envio para o Produto A nunca toca o B | Task 4 |
| SEC-065 | Auditoria do envio (sucesso e recusa), sem nome de arquivo nem conteúdo | Tasks 3 e 4 |
| SEC-050 | Parcial: temporários apagados no `finally`; a cadeia de custódia completa fica com SEC-044 | Task 3 |

## Global Constraints

- Todo endpoint começa com `/api`; a rota nova nasce negada e é liberada na `SecurityConfig` por capacidade.
- Código, commits e comentários em inglês; textos da tela em pt-BR, **só os da seção 7**.
- Uma só dependência nova: `org.apache.pdfbox:pdfbox:3.0.8` (só depois do OK). Nenhuma outra, nem no frontend.
- 50 MB = 52.428.800 bytes (`50MB` no `DataSize` do Spring); 1.000 páginas; arquivo de exatamente 52.428.800 bytes ou 1.000 páginas é aceito.
- O nome enviado pelo usuário nunca vira caminho, chave, coluna, log nem auditoria: serve só para conferir a extensão e é descartado.
- Nenhum segredo lido, exibido ou commitado. Variáveis novas vão para `.env.example` sem valor.
- PDFs de teste gerados em código (PDFBox nos testes Java; bytes montados à mão no e2e). Nenhum arquivo PDF versionado.
- Teste que prova requisito leva o ID no `@DisplayName`/título. Sem `@MockitoBean` em teste de integração: falhas se simulam com dublês trocáveis (padrão do `SwitchableAuditLog`).
- Controller e repositório package-private; DTOs como `record`.

## Review Focus

1. **PDF com `/Count` mentiroso na árvore de páginas** (diz 10, tem 1.500; ou ciclo na árvore): a contagem tem de percorrer as páginas até 1.001 e recusar, nunca confiar no `/Count`. Teste na Task 1.
2. **Arquivo de 50 MB + 1 byte enviado por cliente que não é o navegador**: a resposta é `413` com `FILE_TOO_LARGE` e nenhum byte além do limite vai para o disco; o navegador nem envia (checagem antes). Testes nas Tasks 4 e 5.
3. **Falha do banco depois de o objeto subir para o Storage**: `503`, a linha antiga continua valendo e o objeto novo é apagado (compensação). Teste na Task 3.
4. **Dois envios ao mesmo tempo para o mesmo Produto**: termina com uma só linha, apontando para um dos dois objetos, e o outro objeto é apagado. Teste na Task 3.
5. **Nome de arquivo com `../`, `\`, NUL ou `.pdf.exe`**: recusado ou aceito só pela extensão final, e a chave gravada segue `products/<uuid>/<uuid>.pdf`. Teste na Task 4.

---

## 1. Escopo

**Entra:**
- Rota `POST /api/admin/products/{id}/file` (multipart, campo `file`), validação SEC-043, armazenamento atrás do adaptador `ProductFileStorage` (Supabase em produção, arquivo em disco no local, no CI e nos testes), tabela `product_files`, capacidade `PRODUCTS_FILE`, auditoria e o campo `file` (tamanho, páginas e data) no `ProductResponse`.
- Substituir o arquivo **enquanto o Produto é rascunho** (decisão 9.D1): enviar outro arquivo troca o atual e apaga o anterior.
- Seção "Arquivo do produto" na tela de edição do Produto (`/painel/produtos/:id`), teste e2e do envio.
- Atualização de uma linha no ADR-0002 (adaptador, nome do objeto, órfãos) e no ADR-0004 (troca sem reautenticação só em rascunho), se 9.D1 for aprovada.

**Fica fora:**
- Download e URL assinada (FR-034 completo): #33. Aqui só a base (bucket privado, chave nunca exposta).
- Reautenticação na troca e troca de arquivo de Produto publicado: #22.
- Antimalware, quarentena e CDR (SEC-044) e processamento isolado com CPU, memória e rede limitados por processo (SEC-045).
- Recusar PDF com conteúdo ativo (JavaScript, anexos, XFA) — ver 9.D5; limpeza periódica de objetos órfãos; nome original do arquivo na tela; barra de progresso.

**SEC-044/045:** sim, pedem um ticket novo — não existe nenhum hoje; proponho um só ("Quarentena, antimalware e validação isolada do PDF"), ligado ao gate do #47, criado na Task 6 com `needs-triage`.

## 2. Dependência nova: Apache PDFBox 3.0.8 — **PARE: precisa do seu OK**

| Item | Valor |
|---|---|
| Artefato | `org.apache.pdfbox:pdfbox:3.0.8` (Apache License 2.0) |
| Publicação no Maven Central | 08/07/2026 (4 meses antes de hoje, bem acima dos 7 dias). É a última 3.x |
| Transitivas | `pdfbox-io` 3.0.8, `fontbox` 3.0.8, `commons-logging` (o Spring Framework 7 já usa; conferir com `mvnw dependency:tree` na Task 1). BouncyCastle é opcional e não entra (já temos `bcprov` 1.86 por outro motivo; o PDFBox só o usa para decifrar, o que recusamos antes) |
| CVEs que afetam a 3.0.8 | **Nenhuma conhecida.** As duas de 2026 (CVE-2026-23907 e CVE-2026-33929, path traversal) estão só no módulo `pdfbox-examples` (`ExtractEmbeddedFiles`), que não usamos, e foram corrigidas na 3.0.7 e na 3.0.8. As antigas do parser (CVE-2021-27807/27906/31811/31812: laço infinito e falta de memória com PDF malicioso; CVE-2018-11797; XXE CVE-2016-2175 e CVE-2019-0228) afetam só a 1.8/2.0 antigas. Uma busca citou a CVE-2025-48631 como "PDFBox", mas ela não está na página de segurança do Apache PDFBox |
| Fontes | Página de segurança https://pdfbox.apache.org/security.html; índice do Maven Central https://repo1.maven.org/maven2/org/apache/pdfbox/pdfbox/; javadoc 3.0 do `PDFParser` |

**Alternativa sem dependência nova:** existe, mas não recomendo. Dá para conferir assinatura, tamanho, `%%EOF` e a presença de `/Encrypt` com código nosso, só que **contar páginas** exige ler a tabela xref e, nos PDFs 1.5+ (a maioria dos exportados por Word, Canva, Google Docs), descomprimir *object streams* (Flate) e percorrer a árvore de páginas — ou seja, escrever um parser de PDF próprio, com mais superfície de ataque (bomba de descompressão, ciclos) e sem o histórico de correções do PDFBox. Contar `/Type /Page` no texto erra nos PDFs comprimidos. Sem o PDFBox, o critério "mais de 1.000 páginas é recusado" fica sem cumprimento confiável.

**O que a biblioteca pode fazer de perigoso e como o plano limita:**

| Perigo | Limite no plano |
|---|---|
| Carregar o PDF inteiro em memória (`Loader.loadPDF(byte[])`, `RandomAccessReadBuffer(InputStream)`) | Só `RandomAccessReadBufferedFile` sobre o temporário em disco (lê por páginas de buffer). `byte[]` e `InputStream` proibidos no validador |
| Bomba de descompressão em *object stream* / xref stream, laço em árvore de páginas | Tempo máximo de 20 s por validação (`app.product-file.validation-timeout`); ao estourar, o arquivo de leitura é fechado (o parser falha na próxima leitura) e a resposta é `FILE_INVALID`. Uma validação por vez (executor de 1 thread, sem fila: o segundo envio simultâneo recebe `503`). A contagem de páginas para em 1.001 |
| Memória sem limite por requisição | A JVM não limita memória por thread: o teto real é o heap da aplicação (`MaxRAMPercentage=75`). O plano reduz o risco com a validação única por vez e o tempo limite. **Limite de memória de verdade só com processo isolado (SEC-045, ticket novo)** — risco aceito até lá |
| Rede (SSRF): ações `/URI`, `/GoToR`, `/Launch`, arquivos externos, fontes remotas | O PDFBox não abre conexões ao fazer o parse. O validador nunca chama `getActions()`, `getEmbeddedFiles()`, fontes, renderização, extração de texto, assinatura, nem resolve arquivos externos. Teste com PDF que aponta `/URI` e `/GoToR` para um `ServerSocket` local prova zero conexões |
| Executar JavaScript, macros, links | O PDFBox não tem motor de JavaScript nem executa ações; o validador não as lê. O arquivo é guardado **sem alteração** (CDR é SEC-044) |
| XXE (XFA e XMP são XML) | O validador nunca chama `PDAcroForm.getXFA()`, `getMetadata()` nem o `xmpbox` (que não entra como dependência) |
| Senha/criptografia | PDF cifrado é recusado; nunca tentamos senha |
| Log com conteúdo do PDF ou enxurrada de avisos | `logging.level.org.apache.pdfbox: ERROR` no `application.yml` |

**Regra de código (revisor confere):** o validador só usa `new PDFParser(RandomAccessReadBufferedFile, "")` + `parse(true)`, `PDDocument.isEncrypted()`, `getPages()` (iteração) e `close()`. Qualquer outra chamada do PDFBox é motivo de rejeição na revisão.

## 3. Validação, em ordem barata primeiro

Resposta de recusa: `400` (ou `413`) com `{"errors":{"file":"<CÓDIGO>"}}`, no mesmo formato do #17. A primeira recusa encerra; nada vai para o Storage.

| # | Onde | Checagem | Código | HTTP |
|---|---|---|---|---|
| 0 | filtros do Spring Security | sessão do Administrador com os dois fatores, `PRODUCTS_FILE`, token CSRF — **antes de ler o corpo** | — | 401/403 |
| 1 | Tomcat, durante o recebimento | `Content-Length` acima de 51 MB recusado antes de ler; parte `file` acima de 52.428.800 bytes interrompida ao passar do limite (`spring.servlet.multipart.max-file-size: 50MB`, `max-request-size: 51MB`, `file-size-threshold: 0`, `resolve-lazily: true`) | `FILE_TOO_LARGE` | 413 |
| 2 | controller | parte `file` ausente | `FILE_REQUIRED` | 400 |
| 3 | controller | arquivo com 0 byte | `FILE_EMPTY` | 400 |
| 4 | serviço (banco) | Produto existe | — | 404 |
| 5 | validador | extensão: último segmento do nome (depois de `/` ou `\`), sem caractere de controle, terminando em `.pdf` sem diferenciar maiúsculas | `FILE_NOT_PDF` | 400 |
| 6 | validador | MIME declarado da parte: `application/pdf`, `application/x-pdf` ou `application/octet-stream` (o declarado não decide; ver 9.D4) | `FILE_NOT_PDF` | 400 |
| 7 | validador | assinatura: os 5 primeiros bytes são `%PDF-` seguidos de `1.` ou `2.` | `FILE_NOT_PDF` | 400 |
| 8 | validador (PDFBox, com tempo) | MIME real e estrutura: o parser abre o arquivo (modo tolerante, ver 9.D3). `InvalidPasswordException` ou `isEncrypted()` → cifrado; outra `IOException` → malformado | `FILE_ENCRYPTED` / `FILE_INVALID` | 400 |
| 9 | validador (PDFBox, com tempo) | páginas: percorre a árvore até 1.001; 0 página → malformado; 1.001 → recusa | `FILE_INVALID` / `FILE_TOO_MANY_PAGES` | 400 |
| 10 | validador | 20 s estourados nas etapas 8–9 | `FILE_INVALID` | 400 |
| 11 | validador | outra validação em andamento | — | 503 |

O texto de cada código está na seção 7. O tamanho também é conferido de novo no validador (`Files.size ≤ 52.428.800`), como segunda linha.

## 4. Armazenamento

**Interface** (`product/ProductFileStorage.java`, pública só dentro do pacote):

```java
interface ProductFileStorage {
    /** Stores the file under the key; never overwrites. Throws ProductFileStorageException on any failure. */
    void put(String key, Path file, long sizeBytes);
    /** Deletes the object; a missing object is not an error. Throws ProductFileStorageException on failure. */
    void delete(String key);
}
```

O download (#33) acrescenta `signedUrl(key, Duration)`; aqui não (YAGNI).

**Nome do objeto:** `products/{productId}/{UUID.randomUUID()}.pdf`, montado só pelo servidor (`ProductFileKeys.newKey(UUID productId)`). O nome enviado nunca entra. As duas implementações recusam chave fora do padrão `^products/[0-9a-f-]{36}/[0-9a-f-]{36}\.pdf$` (defesa em profundidade contra path traversal).

**`SupabaseProductFileStorage`** (`@ConditionalOnProperty(app.storage.provider = supabase)`), no padrão do `ResendEmailSender`:
- `POST {url}/storage/v1/object/{bucket}/{key}` com `apikey: <secret key>`, `Content-Type: application/pdf`, `x-upsert: false` (nunca sobrescreve), corpo lido do temporário.
- `DELETE {url}/storage/v1/object/{bucket}/{key}`; 404 conta como apagado.
- Tempo: 2 s para conectar, 60 s para ler. Erro vira `ProductFileStorageException` só com o status HTTP ou o nome da classe (nunca corpo de resposta, URL com chave ou o segredo).
- Bucket **privado**: criado à mão no painel do Supabase (runbook novo, Task 6), com limite de 50 MB no bucket (o plano Free do Supabase não passa de 50 MB por arquivo, o que coincide com o nosso limite). Nenhuma URL pública é gerada nem guardada; a resposta da API nunca traz a chave.
- Segredo novo: a *secret key* do Supabase (`sb_secret_...`), enviada no cabeçalho `apikey` (a documentação manda não usar `Authorization: Bearer` com as chaves novas; a `service_role` antiga está sendo descontinuada até o fim de 2026).

**`FileProductFileStorage`** (`provider = file`): grava em `app.storage.file.dir` (padrão `../.tmp/storage`), confere com `normalize()` + `startsWith(root)`. É o falso usado no local, no CI, no e2e e nos testes de integração; tem um irmão de teste `SwitchableProductFileStorage` (em `src/test`) que faz `put`/`delete` falharem sob comando.

**Configuração** (sem ler nem mostrar segredo):

| Propriedade | `application.yml` | `application-prod.yml` | teste (`application.properties`) |
|---|---|---|---|
| `app.storage.provider` | `file` | `supabase` | `file` |
| `app.storage.file.dir` | `${APP_STORAGE_FILE_DIR:../.tmp/storage}` | — | diretório temporário por classe de teste |
| `app.storage.supabase.url` | — | `${APP_STORAGE_SUPABASE_URL}` (sem padrão: não sobe sem ela) | — |
| `app.storage.supabase.secret-key` | — | `${APP_STORAGE_SUPABASE_SECRET_KEY:}` (vazio → não sobe, como o Resend) | — |
| `app.storage.supabase.bucket` | — | `product-files` | — |
| `app.product-file.max-pages` | `1000` | — | `1000` |
| `app.product-file.validation-timeout` | `PT20S` | — | `PT20S` |
| `spring.servlet.multipart.*` | ver seção 3, linha 1 | — | — |
| `logging.level.org.apache.pdfbox` | `ERROR` | — | — |

`.env.example` ganha `APP_STORAGE_SUPABASE_URL=` e `APP_STORAGE_SUPABASE_SECRET_KEY=`, sem valor. Os segredos de verdade vão para os Fly secrets por você, no deploy (#7).

## 5. Uma só versão atual por Produto

**Migration `V14__create_product_files.sql`** (próxima numeração; criada com a skill `nova-migration`):

```sql
create table product_files (
  product_id uuid primary key references products (id),
  object_key varchar(120) not null unique check (object_key ~ '^products/[0-9a-f-]{36}/[0-9a-f-]{36}\.pdf$'),
  size_bytes bigint not null check (size_bytes between 1 and 52428800),
  page_count integer not null check (page_count between 1 and 1000),
  uploaded_at timestamp with time zone not null default now()
);
```

A chave primária em `product_id` é a garantia de "uma só versão atual". Não guardamos o nome original do arquivo nem quem enviou (a auditoria já tem o ator).

**Fluxo do envio** (`ProductFileService.upload`):
1. Validação (seção 3) sobre o temporário.
2. `key = ProductFileKeys.newKey(id)`; `storage.put(key, temp, size)`. Falhou → `503`, nada no banco.
3. Transação: `select ... from products where id = :id for update` (404 se sumiu); `select object_key from product_files where product_id = :id`; `insert ... on conflict (product_id) do update` com a chave nova; auditoria SUCCESS na mesma transação.
4. Falhou o passo 3 (banco, auditoria) → `storage.delete(key)` do objeto novo, em melhor esforço, e `503`. A linha antiga continua valendo.
5. Depois do commit (`TransactionSynchronization.afterCommit`): `storage.delete(chaveAntiga)`, em melhor esforço. Falhou → log `WARN` com o tipo de erro e o id do Produto (a chave não é dado pessoal, mas não precisa ir ao log) e o objeto fica órfão.

**Órfãos:** só nascem de queda do processo entre os passos 2 e 3, ou de falha no `delete`. Nunca são entregues (o download lê a chave do banco). Limpeza periódica fica fora (vai para a issue de sobras). O `FOR UPDATE` no Produto serializa dois envios simultâneos: cada um apaga exatamente o objeto que substituiu.

**Versão otimista do Produto (#17):** o envio **não** usa nem incrementa `products.version` (decisão 9.D2). O arquivo é independente do texto e do preço; a concorrência dele é resolvida pela trava de linha acima, e "aba velha" não faz sentido para um arquivo escolhido na hora. Assim, enviar o arquivo não invalida o formulário de texto aberto na mesma tela.

**`ProductResponse`** ganha `file: ProductFileInfo | null`, com `record ProductFileInfo(long sizeBytes, int pageCount, Instant uploadedAt)`. `Products.find` faz `left join product_files`. A listagem não muda.

## 6. Segurança e autorização

- **Rota:** `POST /api/admin/products/{id}/file`, `multipart/form-data`, resposta `200` com o `ProductResponse` atualizado. (POST, e não PUT, porque o Tomcat só garante o parse de multipart no POST.)
- **SecurityConfig:** `.requestMatchers(HttpMethod.POST, "/api/admin/products/*/file").access(panel(Capability.PRODUCTS_FILE))`, antes do `/api/**` negado. `GET`/`PUT`/`DELETE` em `/file` continuam negados.
- **Capacidade nova `PRODUCTS_FILE`, não `PRODUCTS_EDIT`.** Por quê: o arquivo é o que o Cliente recebe; um perfil futuro (Editor, Revisor) pode corrigir texto sem poder trocar a entrega. O plano do #17 já previa isso ("enviar arquivo (#18) ganha a sua"), e o #22 reaproveita a mesma capacidade somando a reautenticação.
- **CSRF:** exigido como em toda rota que muda dado (o `apiFetch` manda o `X-XSRF-TOKEN`; o filtro lê o cabeçalho, não o corpo). Teste: sem token → `403` e nada gravado no Storage nem no banco.
- **Auditoria:** `AuditAction.ADMIN_PRODUCT_FILE_UPLOADED`, `AuditObject.PRODUCT`, id do Produto, ator, origem. SUCCESS na transação do passo 3; FAILURE em transação própria (`recordIndependent`) nas recusas 5–10 da seção 3 (tentativa de envio inválido é sinal de segurança). Nada do nome, tamanho, chave ou conteúdo. O `AuditPrivacyTest` existente continua passando; teste novo confere que o nome enviado não aparece em `audit_events` nem no log.
- **Teste negativo de ID (SEC-011):** o Produto não tem dono (um só Administrador). Os testes provam: (a) sem `PRODUCTS_FILE`, `403` igual para id existente e inexistente, sem tocar o Storage; (b) id inexistente com a capacidade → `404` e nenhum objeto gravado; (c) envio para o Produto A deixa a linha e o objeto do Produto B intactos; (d) sessão de Cliente → negado.
- **Sem reautenticação** neste ticket (só rascunho; ver 9.D1). O #22 a acrescenta.

## 7. Tela do painel

**Onde:** na página de edição do Produto (`/painel/produtos/:id`, `ProductFormPage`), uma seção nova abaixo de "Preço", só no modo edição (no cadastro ainda não existe id). Componente `ProductFileSection.tsx`, hook `useUploadProductFile` (`FormData` via `apiFetch`, sem `Content-Type` manual). Antes de enviar, o navegador confere a extensão e o tamanho (> 52.428.800 bytes não sai da máquina). Durante o envio o botão fica desabilitado.

**Estados:** sem arquivo · com arquivo · enviando · erro (mensagem abaixo do campo, com `role="alert"`) · sucesso (mensagem com `role="status"`, e a linha "Arquivo atual" atualizada).

**Todos os textos que o usuário vê (para aprovação):**

| Onde | Texto |
|---|---|
| Título da seção | `Arquivo do produto` |
| Sem arquivo | `Nenhum arquivo enviado.` |
| Com arquivo | `Arquivo atual: {N} páginas, {tamanho}, enviado em {dd/mm/aaaa} às {hh:mm}.` (ex.: `Arquivo atual: 120 páginas, 3,4 MB, enviado em 09/11/2026 às 14:32.`; com 1 página: `1 página`; tamanho em MB com uma casa, vírgula decimal; horário de Brasília) |
| Orientação | `Somente PDF, até 50 MB e 1.000 páginas.` |
| Orientação extra, só com arquivo | `Enviar outro arquivo substitui o atual.` |
| Rótulo do campo | `Arquivo PDF` |
| Botão | `Enviar arquivo` |
| Botão durante o envio | `Enviando arquivo…` |
| Sucesso | `Arquivo enviado.` |
| Nenhum arquivo escolhido (`FILE_REQUIRED`) | `Selecione um arquivo PDF.` |
| `FILE_EMPTY` | `O arquivo está vazio.` |
| `FILE_TOO_LARGE` (navegador e servidor) | `O arquivo passa de 50 MB. Envie um PDF menor.` |
| `FILE_NOT_PDF` (navegador e servidor) | `O arquivo não é um PDF. Envie um arquivo PDF.` |
| `FILE_INVALID` | `Não foi possível ler este PDF. Ele pode estar corrompido. Gere o PDF de novo e tente outra vez.` |
| `FILE_ENCRYPTED` | `O PDF está protegido por senha. Envie uma versão sem proteção.` |
| `FILE_TOO_MANY_PAGES` | `O PDF passa de 1.000 páginas. Envie um arquivo com até 1.000 páginas.` |
| 403 | `Você não tem permissão para enviar o arquivo do Produto.` |
| 404 | `Produto não encontrado.` (já existe, `PRODUCT_NOT_FOUND`) |
| 503 | `O sistema está indisponível no momento e o arquivo não foi enviado. Tente novamente em alguns instantes.` |
| Falha de rede ou resposta inesperada | `Não foi possível enviar o arquivo. Verifique a conexão e tente novamente.` |
| 401 (sessão encerrada) | o tratamento que já existe na tela (`ProductSessionEndedError`), sem texto novo |

**Fluxo crítico novo:** e2e `frontend/e2e/product-file.e2e.ts` contra API e banco de verdade, sem `page.route` nem mocks; o Storage é o adaptador de disco (`provider=file`), porque o CI não tem Supabase — o Supabase de verdade é conferido à mão na homologação (Task 6 e riscos).

## 8. Testes por requisito

**PDFs de teste** gerados em código: `TestPdfs` (em `src/test/.../product/`) com PDFBox (`PDDocument` + `addPage` + `save` em `byte[]`), e `frontend/e2e/pdf.ts` montando um PDF mínimo de 1 página à mão (com offsets da xref calculados). Nenhum PDF no git.

| Teste (título com ID) | Tipo | Prova |
|---|---|---|
| `SEC-043: PDF válido de 1 página é aceito` | unit validador | `Valid(pageCount=1)` |
| `SEC-043: arquivo de texto com extensão .pdf é recusado (FILE_NOT_PDF)` | unit | assinatura |
| `SEC-043: PNG renomeado para .pdf é recusado` | unit | assinatura |
| `SEC-043: PDF de verdade com extensão .txt é recusado` | unit | extensão |
| `SEC-043: MIME declarado image/png é recusado` | unit | MIME declarado |
| `SEC-043: PDF de 1.000 páginas é aceito e o de 1.001 é recusado (FILE_TOO_MANY_PAGES)` | unit | limite exato |
| `SEC-043: árvore de páginas com /Count mentiroso é contada de verdade` | unit | Review Focus 1 |
| `SEC-043: PDF sem nenhuma página é recusado (FILE_INVALID)` | unit | |
| `SEC-043: PDF malformado (cabeçalho seguido de lixo) é recusado (FILE_INVALID)` | unit | |
| `SEC-043: PDF truncado no meio é recusado ou aceito só se o parser tolerante o abrir` | unit | fixa o comportamento de 9.D3 |
| `SEC-043: PDF com senha de abertura e PDF só com senha de dono são recusados (FILE_ENCRYPTED)` | unit | `StandardProtectionPolicy` |
| `SEC-047: validação que passa do tempo limite é interrompida e recusada` | unit | parser dublê que bloqueia; tempo de 200 ms no teste |
| `SEC-047: segunda validação simultânea recebe ocupado` | unit | executor sem fila |
| `SEC-046 SEC-047: PDF com /URI, /GoToR, /Launch e JavaScript não abre conexão na validação` | unit | `ServerSocket` local com contagem de aceites = 0; arquivo aceito e guardado igual, byte a byte |
| `SEC-043: arquivo de 52.428.800 bytes é aceito e o de 52.428.801 recebe 413 FILE_TOO_LARGE` | integração | limite exato no Tomcat de verdade (`webEnvironment = RANDOM_PORT`), sem carregar o corpo inteiro no teste (corpo gerado em stream) |
| `SEC-043: Content-Length acima de 51 MB é recusado sem gravar temporário` | integração | |
| `SEC-047: nome ../../etc/passwd.pdf, ..\\x.pdf, a.pdf<NUL>.exe e x.pdf.exe nunca viram caminho` | integração | chave gravada casa o padrão; nome fora de `product_files`, `audit_events` e do log; `.pdf.exe` → `FILE_NOT_PDF` |
| `SEC-047: FileProductFileStorage recusa chave fora do padrão` | unit storage | `../`, absoluto, `\` |
| `SEC-047: SupabaseProductFileStorage manda apikey, x-upsert false e content-type, sem vazar segredo no erro` | unit storage | `MockRestServiceServer`; segredo fictício nunca na mensagem da exceção |
| `FR-034: resposta do Produto nunca traz chave nem URL do arquivo` | integração | JSON sem `objectKey`/`url` |
| `SEC-010: sem PRODUCTS_FILE a rota nega igual exista o Produto ou não` | integração | (SEC-011) |
| `SEC-010: sessão pendente de MFA, sessão de Cliente e anônimo são negados` | integração | |
| `SEC-017: envio sem token CSRF é recusado e nada é gravado` | integração | |
| `SEC-011: envio para o Produto A não toca o arquivo do Produto B` | integração | |
| `uma só versão atual: o segundo envio troca a linha e apaga o objeto anterior` | integração | |
| `falha do Storage no envio responde 503 e não muda o banco` | integração | `SwitchableProductFileStorage` |
| `falha do banco depois do upload apaga o objeto novo e mantém o anterior` | integração | `SwitchableAuditLog` falhando (Review Focus 3) |
| `dois envios simultâneos deixam uma linha e nenhum objeto sobrando` | integração | Review Focus 4 |
| `falha ao apagar o objeto anterior não muda a resposta 200` | integração | |
| `SEC-065: envio aceito e recusado gravam auditoria sem nome, tamanho nem chave` | integração | |
| `ProductFileSection`: estados, textos da seção 7, botão desabilitado no envio, checagem de tamanho e extensão no navegador sem requisição | Vitest | |
| `SEC-043 FR-034: o Administrador envia o PDF do Produto e o substitui; arquivo falso é recusado` | e2e | |

## 9. Riscos, decisões que dependem de você e ordem das tarefas

### Decisões que dependem de você

- **D0 — Dependência nova PDFBox 3.0.8** (seção 2). Recomendo aprovar. Sem ela, o limite de páginas não tem cumprimento confiável.
- **D1 — Substituir o arquivo neste ticket?** (a) **Recomendado:** o #18 aceita substituir enquanto o Produto é rascunho (hoje todo Produto é rascunho), sem reautenticação; o #22 acrescenta a reautenticação e a troca em Produto publicado. Isso pede uma linha nova no ADR-0004 ("a troca exige nova autenticação a partir do #22; em rascunho, enviar outro arquivo não exige") e um comentário no #19: publicar antes do #22 deve bloquear a troca de arquivo de Produto publicado. (b) O #18 só aceita o primeiro envio (`409` se já houver arquivo) e toda troca fica para o #22 — segue a letra do ADR-0004, mas um PDF errado num rascunho fica preso até o #22, e pede mais um texto: `Este Produto já tem arquivo. A troca chega numa próxima versão do painel.`
- **D2 — O envio não usa a versão otimista do Produto** (seção 5). Recomendo assim: o arquivo é independente do texto e o formulário aberto não fica velho a cada envio.
- **D3 — Parser tolerante (lenient) ou estrito?** Recomendo **tolerante**: muitos PDFs exportados por ferramentas comuns têm pequenos defeitos de xref que o PDFBox conserta sozinho; o estrito recusaria arquivos que o leitor do Cliente abre normalmente. "Malformado" passa a ser "o PDFBox não consegue abrir nem consertando". O arquivo é guardado como veio.
- **D4 — MIME declarado**: aceitar `application/pdf`, `application/x-pdf` e `application/octet-stream` (alguns Windows sem leitor de PDF instalado mandam `octet-stream`); o "MIME real" vem do conteúdo (assinatura + parse). Recomendo assim.
- **D5 — Recusar PDF com conteúdo ativo** (JavaScript, `/Launch`, anexos, XFA)? O servidor não executa nada (SEC-046 cumprido), mas o leitor do Cliente pode executar. Recomendo **deixar para o ticket do SEC-044 (CDR)**, porque uma checagem parcial no catálogo daria falsa segurança e mais código aqui. Se você quiser já no #18, o texto seria: `O PDF tem scripts, anexos ou formulários dinâmicos, que não são permitidos. Gere o PDF sem esses recursos.`
- **D6 — Textos da seção 7**, todos.
- **D7 — Tempo limite de 20 s** por validação e **uma validação por vez**. Um PDF de 1.000 páginas comum abre em bem menos que isso; ajusto se preferir outro valor.
- **D8 — Ticket novo para SEC-044/045** (um só, ligado ao gate #47).

### Riscos

- **Supabase de verdade não roda no CI.** O adaptador é testado com `MockRestServiceServer`; a primeira prova real é na homologação. Pontos a conferir lá: cabeçalho `apikey` com a *secret key* basta (a documentação diz que sim; se o gateway pedir, entra também `Authorization`), bucket privado, limite de 50 MB, upload de 50 MB numa só requisição (o Supabase recomenda upload resumível acima de 6 MB, mas aceita o padrão até o limite do plano).
- **Memória**: sem processo isolado, um PDF hostil pode pressionar o heap durante até 20 s. Mitigado (uma validação por vez, leitura do disco, tempo limite), resolvido só com SEC-045.
- **Thread que não para**: o PDFBox não responde a interrupção; fechar o arquivo faz o parser falhar, mas um laço sem leitura poderia seguir ocupando a única thread. Nesse caso os envios seguintes recebem `503` até ela terminar. Risco aceito; SEC-045 resolve.
- **Corpo acima do limite e conexão cortada:** o Tomcat descarta até 2 MB (`max-swallow-size`) do excesso e depois fecha a conexão; um cliente fora do navegador pode ver "conexão encerrada" em vez do `413`. O navegador não chega lá (checa antes). Não aumento o descarte para não abrir porta a corpos enormes de anônimos.
- **Órfãos no Storage** em queda do processo: custo de espaço apenas; limpeza vai para a issue de sobras.
- **Temporários no contêiner:** o usuário `app` da imagem precisa escrever no diretório temporário do Java. Conferir com `just imagem` e um envio na imagem (Task 6).
- **`streamCacheCreateFunction`** do PDFBox 3 tem um bug conhecido (PDFBOX-5757, não repassado ao parser); o plano não depende dele (lemos de arquivo, sem cache de escrita).

### Ordem das tarefas e subagentes (todos `model: sonnet`)

| Task | O quê | Depende de | Subagente |
|---|---|---|---|
| 1 | PDFBox no `pom.xml` + `PdfFileValidator` + `TestPdfs` | D0, D3, D4, D7 | sonnet |
| 2 | `ProductFileStorage` + implementações de disco e Supabase + configuração | — | sonnet, **em paralelo com a 1** (arquivos disjuntos) |
| 3 | Migration V14, `ProductFiles`, `ProductFileService` (fluxo, compensação, auditoria), `ProductResponse.file` | 1, 2, D1, D2 | sonnet |
| 4 | Rota, `PRODUCTS_FILE`, `SecurityConfig`, multipart, testes HTTP | 3 | sonnet |
| 5 | `ProductFileSection` + hook + mensagens + Vitest + `/verificar-tela` | contrato da seção 6/7 (pode começar junto com a 3) e D6 | sonnet |
| 6 | e2e, ADRs, runbook do bucket, issues novas, sistema de verdade, `verificar-entrega` | 4, 5 | sonnet para o e2e; o resto comigo |

---

## Tasks

### Task 1: Validador de PDF

**Files:**
- Modify: `backend/pom.xml` (`org.apache.pdfbox:pdfbox:3.0.8`)
- Modify: `backend/src/main/resources/application.yml` (`app.product-file.*`, `logging.level.org.apache.pdfbox: ERROR`)
- Modify: `backend/src/test/resources/application.properties` (`app.product-file.max-pages=1000`, `app.product-file.validation-timeout=PT20S`)
- Create: `backend/src/main/java/br/com/sergio/sapiensia/product/PdfFileValidator.java`
- Create: `backend/src/main/java/br/com/sergio/sapiensia/product/PdfCheck.java`
- Create: `backend/src/main/java/br/com/sergio/sapiensia/product/ProductFileRejection.java`
- Create: `backend/src/main/java/br/com/sergio/sapiensia/product/ValidatorBusyException.java`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/product/TestPdfs.java`, `PdfFileValidatorTest.java`

**Interfaces:**
- Produces:
  - `enum ProductFileRejection { FILE_REQUIRED, FILE_EMPTY, FILE_TOO_LARGE, FILE_NOT_PDF, FILE_INVALID, FILE_ENCRYPTED, FILE_TOO_MANY_PAGES }`
  - `sealed interface PdfCheck { record Valid(int pageCount) ; record Rejected(ProductFileRejection reason) }`
  - `@Component class PdfFileValidator { PdfCheck check(Path file, String submittedName, String declaredContentType) }` — lança `ValidatorBusyException` (vira `503`) se outra validação estiver em andamento.
  - `PdfFileValidator(int maxPages, Duration timeout, PdfOpener opener)` como construtor de teste; `interface PdfOpener { PDDocument open(RandomAccessReadBufferedFile file) throws IOException }` (o padrão usa `new PDFParser(file, "").parse(true)`; o teste injeta um que bloqueia).
  - `TestPdfs.pages(int n): byte[]`, `TestPdfs.encryptedWithUserPassword()`, `TestPdfs.encryptedWithOwnerPasswordOnly()`, `TestPdfs.lyingCount(int realPages, int declaredCount)`, `TestPdfs.withActiveContent(int port)`, `TestPdfs.zeroPages()`.

- [ ] **Step 1:** Adicionar a dependência e rodar `cd backend; .\mvnw.cmd dependency:tree -Dincludes=org.apache.pdfbox,commons-logging` (lista o que entrou). Esperado: `pdfbox`, `pdfbox-io`, `fontbox` 3.0.8 e um único `commons-logging`.
- [ ] **Step 2: Testes que falham** — os testes "unit" da seção 8 (SEC-043, SEC-046/047 de rede, tempo e ocupado), por exemplo:

```java
@Test
@DisplayName("SEC-043: PDF de 1.000 páginas é aceito e o de 1.001 é recusado (FILE_TOO_MANY_PAGES)")
void pageLimit() throws Exception {
    assertThat(validator.check(write(TestPdfs.pages(1000)), "a.pdf", "application/pdf"))
            .isEqualTo(new PdfCheck.Valid(1000));
    assertThat(validator.check(write(TestPdfs.pages(1001)), "a.pdf", "application/pdf"))
            .isEqualTo(new PdfCheck.Rejected(ProductFileRejection.FILE_TOO_MANY_PAGES));
}
```

- [ ] **Step 3:** Rodar `cd backend; .\mvnw.cmd test -Dtest=PdfFileValidatorTest` → FAIL (classe não existe).
- [ ] **Step 4: Implementar** na ordem 5–10 da seção 3. Executor `new ThreadPoolExecutor(1, 1, 0, SECONDS, new SynchronousQueue<>())` (thread com nome `pdf-validation`, daemon); `RejectedExecutionException` → `ValidatorBusyException`; `future.get(timeout)` → no `TimeoutException`, `future.cancel(true)` e fechar o `RandomAccessReadBufferedFile`. Páginas: iterar `document.getPages()` contando até `maxPages + 1`. Só as chamadas do PDFBox listadas na seção 2.
- [ ] **Step 5:** Rodar o mesmo teste → PASS.
- [ ] **Step 6: Commit** `feat: validate the product PDF (SEC-043, SEC-046, SEC-047) (#18)`.

### Task 2: Adaptador de Storage

**Files:**
- Create: `backend/src/main/java/br/com/sergio/sapiensia/product/ProductFileStorage.java`, `ProductFileStorageException.java`, `ProductFileKeys.java`, `FileProductFileStorage.java`, `SupabaseProductFileStorage.java`
- Modify: `application.yml`, `application-prod.yml`, `backend/src/test/resources/application.properties`, `.env.example` (seção 4)
- Test: `FileProductFileStorageTest.java`, `SupabaseProductFileStorageTest.java`, `SupabaseProductFileStorageConfigurationTest.java`; `src/test/.../product/SwitchableProductFileStorage.java`

**Interfaces:**
- Produces: `ProductFileStorage.put(String key, Path file, long sizeBytes)`, `delete(String key)`; `ProductFileKeys.newKey(UUID productId): String`, `ProductFileKeys.isValid(String key): boolean`; `ProductFileStorageException(String message)` sem causa encadeada; `SwitchableProductFileStorage` (`@Primary` em configuração de teste, delega ao de disco, `failPuts(boolean)`, `failDeletes(boolean)`, `keys(): Set<String>`).

- [ ] **Step 1: Testes que falham:** chave gerada casa o padrão e nunca repete; `FileProductFileStorage` recusa `../x.pdf`, `/etc/x.pdf`, `products\\..\\x.pdf`; grava e apaga; apagar inexistente não falha. Supabase com `MockRestServiceServer`: `POST /storage/v1/object/product-files/<key>` com `apikey`, `x-upsert: false`, `Content-Type: application/pdf`; `DELETE` com 404 tratado como sucesso; 500 → exceção cuja mensagem tem só `HTTP 500` e não contém o segredo fictício `test-secret-not-real`. Configuração: `provider=supabase` com segredo vazio impede a subida (`IllegalStateException` com `APP_STORAGE_SUPABASE_SECRET_KEY is required`).
- [ ] **Step 2:** `cd backend; .\mvnw.cmd test -Dtest='*ProductFileStorage*'` → FAIL.
- [ ] **Step 3: Implementar**, no padrão do `ResendEmailSender` / `FileEmailSender` (construtor com `RestClient.Builder` como costura de teste; diretório lido como `String`). Timeouts: 2 s conexão, 60 s leitura. Corpo do upload com `FileSystemResource` (sem `byte[]`).
- [ ] **Step 4:** mesmo comando → PASS.
- [ ] **Step 5: Commit** `feat: product file storage adapter for Supabase and local disk (#18)`.

### Task 3: Modelo de dados e serviço de envio

**Files:**
- Create: `backend/src/main/resources/db/migration/V14__create_product_files.sql` (skill `nova-migration`)
- Create: `product/ProductFiles.java` (repositório), `product/ProductFileService.java`, `product/ProductFileInfo.java`, `product/ProductFileRejectedException.java`
- Modify: `product/ProductResponse.java` (+ `ProductFileInfo file`), `product/Products.java` (`left join product_files` no `find` e no `insert`/updates que devolvem o Produto), `audit/AuditAction.java` (+ `ADMIN_PRODUCT_FILE_UPLOADED`)
- Test: `admin/AdminProductFileServiceTest.java` (integração com Testcontainers)

**Interfaces:**
- Consumes: `PdfFileValidator.check`, `ProductFileStorage`, `ProductFileKeys.newKey`, `AuditLog.recordInTransaction/recordIndependent`.
- Produces: `ProductResponse ProductFileService.upload(UUID productId, Path tempFile, String submittedName, String declaredContentType, UUID administratorId, String origin)`; lança `ProductNotFoundException`, `ProductFileRejectedException(ProductFileRejection reason)`, `ProductFileStorageException`, `ValidatorBusyException`, `DataAccessException`.

- [ ] **Step 1: Testes que falham** (seção 8, linhas "integração" de versão atual, falhas e concorrência): primeiro envio grava linha e objeto; segundo envio troca a linha e o objeto anterior some de `storage.keys()`; `failPuts` → exceção, banco igual; auditoria falhando → exceção, objeto novo apagado, linha antiga intacta; dois envios em paralelo (`CountDownLatch`) → 1 linha e exatamente 1 objeto em `keys()`; `failDeletes` no anterior → retorno normal; Produto inexistente → `ProductNotFoundException` sem objeto gravado; recusa → FAILURE em `audit_events` e nada no Storage.
- [ ] **Step 2:** `cd backend; .\mvnw.cmd test -Dtest=AdminProductFileServiceTest` → FAIL.
- [ ] **Step 3: Implementar** o fluxo da seção 5 (validação fora da transação; `put`; transação com `FOR UPDATE`; `afterCommit` apaga o anterior; compensação no `catch`). Logs só com o tipo de erro e o id do Produto.
- [ ] **Step 4:** mesmo comando, mais `-Dtest='AdminProduct*Test,AuditPrivacyTest'` para provar que o #17 não quebrou → PASS.
- [ ] **Step 5: Commit** `feat: one current product file per Product with storage compensation (#18)`.

### Task 4: Rota HTTP, capacidade e limites de recebimento

**Files:**
- Create: `product/ProductFileController.java`
- Modify: `admin/Capability.java` (+ `PRODUCTS_FILE`, com javadoc), o mapeamento de capacidades do Administrador (onde o #17 adicionou `PRODUCTS_PRICE`), `security/SecurityConfig.java` (regra da seção 6), `application.yml` (`spring.servlet.multipart.*` da seção 3)
- Test: `admin/AdminProductFileAuthorizationTest.java`, `admin/AdminProductFileUploadTest.java` (`RANDOM_PORT`, cliente HTTP de verdade para o limite de tamanho), atualizar `AdminRouteDenyByDefaultTest`

**Interfaces:**
- Consumes: `ProductFileService.upload`.
- Produces: `POST /api/admin/products/{id}/file` (`@RequestPart("file") MultipartFile`) → `200 ProductResponse`; `400 {"errors":{"file":CODE}}`; `413 {"errors":{"file":"FILE_TOO_LARGE"}}` (handler de `MaxUploadSizeExceededException` no próprio controller, possível por causa do `resolve-lazily`); `404`; `503` sem corpo. O controller copia a parte para `Files.createTempFile("product-file-", ".tmp")` e apaga no `finally`.

- [ ] **Step 1: Testes que falham:** todas as linhas "integração" restantes da seção 8 (limites de tamanho exatos, `Content-Length`, nomes maliciosos, FR-034 sem chave na resposta, SEC-010/011/017, auditoria e log sem o nome). O teste dos 52.428.800 bytes gera o corpo em stream (PDF válido de 1 página com um stream de preenchimento não comprimido).
- [ ] **Step 2:** `cd backend; .\mvnw.cmd test -Dtest='AdminProductFile*Test,AdminRouteDenyByDefaultTest'` → FAIL.
- [ ] **Step 3: Implementar.** Mapear exceções: `ProductFileRejectedException` → 400 com o código; `ValidatorBusyException`, `ProductFileStorageException`, `DataAccessException` → 503 (log só com a classe, como o `ProductAdminController`).
- [ ] **Step 4:** mesmo comando → PASS; depois `just check-resumo` → OK.
- [ ] **Step 5: Commit** `feat: product PDF upload route with PRODUCTS_FILE (#18)`.

### Task 5: Seção "Arquivo do produto" no painel

**Files:**
- Create: `frontend/src/features/products/ProductFileSection.tsx`, `ProductFileSection.test.tsx`
- Modify: `frontend/src/features/products/api.ts` (`uploadProductFile(id, file)` com `FormData` via `apiFetch`, erros `ProductFileRejectedError(code)`), `hooks.ts` (`useUploadProductFile`, que atualiza o cache do Produto com a resposta), `schemas.ts` (`file` no `Product`, `nullable`), `messages.ts` (textos da seção 7, exatamente), `ProductFormPage.tsx` (renderiza a seção no modo edição)

**Interfaces:**
- Consumes: contrato da Task 4.
- Produces: `MAX_FILE_BYTES = 52_428_800`; `FILE_MESSAGES: Record<ProductFileRejectionCode, string>`; `formatFileSize(bytes): string` (`3,4 MB`).

- [ ] **Step 1: Testes que falham** (Vitest + Testing Library, API simulada só aqui): sem arquivo mostra `Nenhum arquivo enviado.`; com arquivo mostra a linha "Arquivo atual" com plural/singular e a orientação extra; arquivo de 52.428.801 bytes ou `.txt` mostra o erro **sem** chamar `fetch`; durante o envio o botão mostra `Enviando arquivo…` e fica desabilitado; cada código do servidor vira o texto da seção 7; sucesso mostra `Arquivo enviado.` e atualiza a linha.
- [ ] **Step 2:** `cd frontend; pnpm vitest run src/features/products` → FAIL.
- [ ] **Step 3: Implementar.** Campo `<input type="file" accept="application/pdf,.pdf">` com `<Label>`; nenhum texto fora da seção 7.
- [ ] **Step 4:** mesmo comando → PASS; `/verificar-tela` na tela de edição (desktop, celular e teclado).
- [ ] **Step 5: Commit** `feat: product file section in the panel (#18)`.

### Task 6: E2E, documentação e entrega

**Files:**
- Create: `frontend/e2e/pdf.ts`, `frontend/e2e/product-file.e2e.ts`, `docs/runbooks/bucket-arquivos-produto.md`
- Modify: `docs/adr/0002-*.md` (uma linha: adaptador `ProductFileStorage`, chave gerada pelo servidor, bucket privado sem URL pública, órfãos possíveis), `docs/adr/0004-*.md` (uma linha, só se D1 = a)

- [ ] **Step 1:** e2e `SEC-043 FR-034: o Administrador envia o PDF do Produto e o substitui; arquivo falso é recusado`: entra no painel (helper existente), cria Produto, envia PDF de 1 página → `Arquivo enviado.` e `Arquivo atual: 1 página…`; recarrega a página e a linha continua; envia texto com nome `falso.pdf` → `O arquivo não é um PDF. Envie um arquivo PDF.`; envia outro PDF → linha atualizada. Rodar com `just db`, `just dev-back`, `just dev-front` e `just e2e` → PASS.
- [ ] **Step 2:** Sistema de verdade no navegador (banco, API, frontend) no fluxo principal; `just imagem` e um envio na imagem (temporário gravável pelo usuário `app`).
- [ ] **Step 3:** ADRs e runbook (criar bucket privado `product-files`, limite de 50 MB, onde pôr os dois segredos no Fly — sem valores).
- [ ] **Step 4:** Issues: ticket novo SEC-044/045 (D8) e uma issue de sobras do #18 com `needs-triage` (limpeza de órfãos, checagem automática de bucket privado, D5 se adiado); comentário no #19 (D1) e no #33 (usar `product_files.object_key`). Só depois do seu OK no texto das issues.
- [ ] **Step 5:** `just check-resumo` → OK; skill `verificar-entrega` com a tabela.
- [ ] **Step 6: Commit** `test: e2e for the product PDF upload (#18)` e `docs: storage adapter and product file decisions (#18)`. Push e PR (`Closes #18`) só com o seu OK.
