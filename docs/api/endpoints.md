# Endpoints da API

Lista revisada de todos os endpoints, conferida contra a `SecurityConfig` (SEC-016). O teste `OpenApiEndpointsTest` compara o OpenAPI gerado com `backend/src/test/resources/openapi/endpoints.txt`: um endpoint novo faz o teste falhar até ser revisado e incluído aqui e nesse arquivo. O Swagger e o `/v3/api-docs` ficam desligados em produção; esta lista é a referência.

Toda rota de `/api` que não está nesta tabela é negada (`denyAll`), com uma exceção: `/api/system/health` (health check) é aberta e fica fora do OpenAPI. Os caminhos fora de `/api` servem o app React e os arquivos estáticos. O logout (`DELETE /api/sessions/current`) é tratado pelo filtro de logout, por isso não aparece no OpenAPI; exige Cliente logado.

"Painel" significa Administrador com a capacidade indicada e os dois fatores (senha e código TOTP). Limites: janela de 10 min salvo indicação; "por IP" significa por endereço IPv4 ou por prefixo /64 do IPv6; "login: limite próprio (ADR-0004)" é o `LoginAttemptLimiter`, usado pelo login do Cliente e do painel, pelas etapas do código (verificação, código de recuperação, confirmação do cadastro do código), pela geração de novos códigos de recuperação e pela reautenticação; `GET /api/admin/sessions/mfa` e `POST /api/admin/sessions/mfa/enrollment` não passam por ele.

| Método | Caminho | Quem pode chamar | Limite |
|---|---|---|---|
| GET | `/api/products` | anônimo | — |
| GET | `/api/products/{id}` | anônimo | — |
| GET | `/api/products/{id}/cover` | anônimo | 600 / 10 min por IP |
| POST | `/api/customers` | anônimo | 5 / h por e-mail; 20 / h por IP |
| POST | `/api/customers/email-confirmations` | anônimo (token no corpo) | 30 / 15 min por IP |
| POST | `/api/customers/password-resets` | anônimo | 5 / h por e-mail; 20 / h por IP |
| POST | `/api/customers/password-resets/confirmation` | anônimo (token no corpo) | 20 / 15 min por IP |
| POST | `/api/sessions` | anônimo | login: limite próprio (ADR-0004) |
| GET | `/api/sessions/current` | Cliente | — |
| DELETE | `/api/sessions` | Cliente | — |
| POST | `/api/orders` | Cliente | 10 / 10 min por conta; 30 / 10 min por IP |
| GET | `/api/orders` | Cliente | — |
| GET | `/api/orders/{id}` | Cliente | 60 / 10 min por conta; 120 / 10 min por IP |
| GET | `/api/orders/{id}/status` | Cliente | — |
| POST | `/api/orders/{id}/refund-request` | Cliente | 10 / 10 min por conta; 30 / 10 min por IP |
| GET | `/api/library` | Cliente | — |
| GET | `/api/library/{productId}/cover` | Cliente | 600 / 10 min por IP |
| POST | `/api/library/{productId}/download` | Cliente | 30 / 10 min por conta; 60 / 10 min por IP (e 10 por Produto por dia, #34) |
| GET | `/api/local-downloads/{token}` | Cliente; só fora de produção | — |
| POST | `/api/webhooks/asaas` | webhook, protegido por token e IP | — |
| POST | `/api/local-demo/charges/{chargeId}/refund` | só com o provedor fake (demonstração local): marca a cobrança como estornada; protegido pelo token e IP do webhook | — |
| POST | `/api/admin/password-setup` | anônimo (token no corpo) | 20 / 15 min por IP |
| POST | `/api/admin/sessions` | anônimo (primeiro passo do painel) | login: limite próprio (ADR-0004) |
| GET | `/api/admin/sessions/mfa` | Administrador com a senha confirmada (código pendente) | — |
| POST | `/api/admin/sessions/mfa` | Administrador com a senha confirmada (código pendente) | login: limite próprio (ADR-0004) |
| POST | `/api/admin/sessions/mfa/enrollment` | Administrador com a senha confirmada (código pendente) | — |
| POST | `/api/admin/sessions/mfa/enrollment/confirmation` | Administrador com a senha confirmada (código pendente) | login: limite próprio (ADR-0004) |
| POST | `/api/admin/sessions/mfa/recovery-code` | Administrador com a senha confirmada (código pendente) | login: limite próprio (ADR-0004) |
| GET | `/api/admin/sessions/current` | Painel: `PANEL_ACCESS` | — (devolve `email`, `capabilities` e `oldCodeRequired`: `false` apenas na sessão que nasceu de um código de recuperação, que troca o autenticador sem o código antigo) |
| POST | `/api/admin/sessions/reauthentication` | Painel: `PANEL_ACCESS` | login: limite próprio (ADR-0004) |
| POST | `/api/admin/recovery-codes` | Painel: `RECOVERY_CODES_MANAGE` | login: limite próprio (ADR-0004) |
| POST | `/api/admin/authenticator-replacement` | Painel: `AUTHENTICATOR_REPLACE` | limite: o do MFA do Administrador (ADR-0004) |
| POST | `/api/admin/authenticator-replacement/confirmation` | Painel: `AUTHENTICATOR_REPLACE` | limite: o do MFA do Administrador (ADR-0004) |
| GET | `/api/admin/products` | Painel: `PRODUCTS_EDIT` | — |
| GET | `/api/admin/products/{id}` | Painel: `PRODUCTS_EDIT` | — |
| POST | `/api/admin/products` | Painel: `PRODUCTS_EDIT` | — |
| PUT | `/api/admin/products/{id}` | Painel: `PRODUCTS_EDIT` | — |
| PUT | `/api/admin/products/{id}/price` | Painel: `PRODUCTS_PRICE` | — |
| POST | `/api/admin/products/{id}/file` | Painel: `PRODUCTS_FILE` e, se já foi publicado, autenticação recente | — |
| GET | `/api/admin/products/{id}/cover` | Painel: `PRODUCTS_EDIT` | — |
| POST | `/api/admin/products/{id}/cover` | Painel: `PRODUCTS_EDIT` | — |
| DELETE | `/api/admin/products/{id}/cover` | Painel: `PRODUCTS_EDIT` | — |
| PUT | `/api/admin/products/{id}/publication` | Painel: `PRODUCTS_PUBLISH` | — |
| DELETE | `/api/admin/products/{id}/publication` | Painel: `PRODUCTS_PUBLISH` | — |
| GET | `/api/admin/orders` | Painel: `ORDERS_VIEW` | — |
| GET | `/api/admin/refund-requests` | Painel: `REFUNDS_VIEW` | — |
| POST | `/api/admin/customer-anonymizations` | Painel: `CUSTOMERS_ANONYMIZE` e autenticação recente | — |
