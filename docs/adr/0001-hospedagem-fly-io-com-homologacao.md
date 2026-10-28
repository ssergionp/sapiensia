---
status: accepted
---

# Hospedagem no Fly.io (gru), com homologação separada

O sistema roda como uma imagem Docker única (React dentro do jar) no Fly.io, na região de São Paulo (gru), sob o domínio `sapiensia.app.br`. Existem dois ambientes: produção e homologação. A homologação é um segundo app no Fly.io que desliga sozinho quando ninguém usa, com projeto Supabase próprio e o Asaas em sandbox, para ensaiar compra, webhook, download e restauração de backup sem tocar em dado real.

## Consequences

- O webhook do Asaas só alcança o sistema na internet, por isso o fluxo completo de pagamento é testado na homologação, não na máquina local.
- O `.app.br` só funciona por HTTPS, o que reforça a meta de segurança do [ADR-0006](0006-meta-de-seguranca-asvs-nivel-2.md).
- Os limites do plano gratuito do Supabase (mais de um projeto) devem ser conferidos na documentação oficial antes de criar a homologação.
