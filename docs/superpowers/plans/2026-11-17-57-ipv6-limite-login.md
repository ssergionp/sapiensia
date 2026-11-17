# #57 — Limite de login por IP: agrupar IPv6 pelo prefixo /64

Origem: code-review do #11. Refs #11, #56, ADR-0004. Requisito: SEC-014 (limite por IP).

Só backend; sem migration (a V33 reservada não é usada); nenhum texto novo.

## O que muda

No `LoginAttemptLimiter` (`backend/src/main/java/br/com/sergio/sapiensia/customer/LoginAttemptLimiter.java`), a chave do limite por IP deixa de ser o endereço inteiro quando ele é IPv6: passa a ser o prefixo /64 (por exemplo, `2001:db8:1:2::/64`). O IPv4 continua por endereço, e o IPv6 que embute um IPv4 (`::ffff:a.b.c.d`) conta como esse IPv4.

O `ClientIpResolver` **não** muda: o IP exato continua indo para a auditoria, para os logs e para a checagem do webhook do Asaas.

## Task 1: chave /64 no limitador de login

Arquivos: `backend/src/main/java/br/com/sergio/sapiensia/customer/LoginAttemptLimiter.java`, `backend/src/test/java/br/com/sergio/sapiensia/customer/LoginAttemptLimiterTest.java` e `docs/adr/0004-identidade-e-mfa.md` (uma linha: o IPv6 conta por /64).

- Uma função privada `ipKey(String clientIp)`: `null` ou vazio viram `""` (como hoje); texto sem `:` volta igual (IPv4); com `:`, `InetAddress.ofLiteral` (sem DNS). Um `Inet4Address` (IPv4 embutido) volta o endereço IPv4; um `Inet6Address` zera os 64 bits finais e volta `<prefixo>/64`. Se o texto não for um literal válido, volta o texto igual (nunca lança exceção nem deixa de contar).
- Testes (cada um falha pela asserção antes do código):
  1. `SEC-014: dois IPv6 do mesmo /64 dividem o limite por IP` (20 tentativas por um endereço, a 21ª por outro do mesmo /64 é bloqueada).
  2. `SEC-014: IPv6 de /64 diferentes não dividem o limite`.
  3. `SEC-014: IPv4 continua limitado por endereço` (dois IPv4 vizinhos não dividem).
  4. `SEC-014: IPv6 com IPv4 embutido conta como o IPv4` (`::ffff:203.0.113.7` e `203.0.113.7` dividem).
  5. A forma escrita não importa: `2001:db8::1` e `2001:0db8:0:0::2` dividem.

## Decisões

| ID | Decisão | Marca |
|----|---------|-------|
| D1 | Sim, agrupar IPv6 por /64 no limite de login (Cliente, Administrador e MFA, todos passam pelo `LoginAttemptLimiter`) | DECIDIDO NO MODO CONTÍNUO — conferir |
| D2 | A normalização fica no limitador, não no `ClientIpResolver`: o webhook do Asaas e a auditoria precisam do IP exato | DECIDIDO NO MODO CONTÍNUO — conferir |
| D3 | O limite continua 20 por 15 minutos por /64. Numa rede que divide um /64 entre muitas pessoas, o limite passa a ser dividido; aceito, porque o bloqueio por conta (5 falhas) não muda e o e2e usa o piso `ip-capacity-floor` | DECIDIDO NO MODO CONTÍNUO — conferir |
| D4 | O `RequestRateLimiter` (outras rotas limitadas por IP) tem a mesma fraqueza; fica fora deste ticket e vira uma issue `needs-triage` citando o #57 | DECIDIDO NO MODO CONTÍNUO — conferir |

## Textos que o usuário vê

Nenhum.

## Riscos

- `InetAddress.ofLiteral` com um texto que não é IP lança exceção; o `catch` devolve o texto igual, então o comportamento antigo fica como reserva.
- O ADR-0004 descreve o limite por IP: acrescentar uma linha dizendo que IPv6 conta por /64.

## Verificação

`-Dtest=LoginAttemptLimiterTest,FlyClientIpLoginLimitTest,AdminAccountKeysLimiterTest` e as classes que citam SEC-014 (`git grep -l SEC-014 -- backend/src/test`); `just lint-back`; `just check-rapido` no fim.
