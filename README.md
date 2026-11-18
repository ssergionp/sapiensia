# sapiensia

Plataforma web para publicar e vender e-books, cartilhas, tutoriais e checklists prontos (PDF + capa enviados por mim), usada por mim no painel de administração e pelos clientes que compram e baixam os conteúdos. A criação por IA fica fora do plano por enquanto (docs/adr/0008).

## Como rodar
1. Abra o Docker Desktop
2. `just setup` e `just db`
3. `just dev-back` e, em outro terminal, `just dev-front`