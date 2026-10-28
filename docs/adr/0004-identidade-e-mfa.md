---
status: accepted
---

# Identidade e MFA: Administrador com aplicativo autenticador, Cliente sem MFA

O Administrador entra com senha e MFA obrigatório por aplicativo autenticador (TOTP), com 10 códigos de recuperação de uso único, usando o suporte a MFA do Spring Security 7. Ações sensíveis (Reembolso, troca de Arquivo do produto) exigem nova autenticação. O Cliente não tem MFA na Fase 1 (fica opcional na Fase 3), mas tem senha forte, rejeição de senhas vazadas, bloqueio após tentativas erradas, e-mail confirmado e aviso de login novo, porque a conta dele só dá acesso a PDFs que ele já pagou.

## Consequences

- Cada operação do painel exige uma capacidade verificada no servidor, mesmo com um só Administrador, para que novos perfis (Editor, Revisor, Financeiro) entrem depois sem refazer a autorização.
- Passkey fica como melhoria futura sem retrabalho, pois o Spring Security 7 aceita vários tipos de fator.
- Os códigos de recuperação são a única saída se o Administrador perder o celular.
