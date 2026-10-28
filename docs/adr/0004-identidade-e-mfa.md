---
status: accepted
---

# Identidade e MFA: Administrador com aplicativo autenticador, Cliente sem MFA

O Administrador entra com senha e MFA obrigatório por aplicativo autenticador (TOTP), com 10 códigos de recuperação de uso único. O Spring Security 7 oferece o mecanismo de MFA (a exigência de mais de um fator no login), mas não o fator TOTP: o projeto implementa o TOTP (RFC 6238) e os códigos de recuperação sobre esse mecanismo, com testes próprios, usando uma biblioteca mantida para o cálculo do TOTP, se houver uma adequada. A troca do Arquivo do produto exige nova autenticação. O Cliente não tem MFA na Fase 1 (fica opcional na Fase 3), mas tem senha forte, rejeição de senhas vazadas, bloqueio após tentativas erradas, e-mail confirmado e aviso de login novo, porque a conta dele só dá acesso a PDFs que ele já pagou.

## Consequences

- Cada operação do painel exige uma capacidade verificada no servidor, mesmo com um só Administrador, para que novos perfis (Editor, Revisor, Financeiro) entrem depois sem refazer a autorização.
- Passkey fica como melhoria futura sem retrabalho, pois o Spring Security 7 aceita vários tipos de fator.
- Os códigos de recuperação são a única saída se o Administrador perder o celular.
- O cadastro do Cliente é público: `POST /api/customers` é liberado no `SecurityConfig` (o resto de `/api` segue negado).
- A senha do Cliente tem de 12 a 128 caracteres, sem exigir símbolo, é conferida contra senhas vazadas (HIBP) e guardada com Argon2id (m=19456 KiB, t=2, p=1).
- Se o serviço HIBP estiver fora do ar ou lento (timeouts de 2 s para conectar e 3 s para ler), o cadastro segue sem a checagem e a falha vai para o log, nunca com a senha nem o e-mail (o log de um erro de conexão com o HIBP pode trazer o prefixo SHA-1 de 5 caracteres da senha, que não é a senha).
- Cadastro com um e-mail que já tem conta responde exatamente como um novo (HTTP 202, mesma mensagem) e é ignorado em silêncio, para a resposta nunca revelar se a conta existe.
