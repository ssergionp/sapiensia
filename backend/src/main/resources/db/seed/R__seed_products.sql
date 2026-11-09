-- Fictitious Products for local development only (NFR-012): db/seed is a Flyway location of the local profile alone.
-- Fixed IDs and "on conflict do nothing" keep whatever was edited locally when the seed runs again.
insert into products (id, title, description, category, price_cents) values
  ('00000000-0000-4000-8000-000000000101', '[Fictício] Guia de alimentação saudável', 'E-book fictício com um guia prático de refeições equilibradas para a semana.', 'HEALTH', 2990),
  ('00000000-0000-4000-8000-000000000102', '[Fictício] Checklist de contrato de aluguel', 'Checklist fictício para conferir as cláusulas de um contrato de aluguel residencial.', 'LAW', 1490),
  ('00000000-0000-4000-8000-000000000103', '[Fictício] Cartilha de orçamento doméstico', 'Cartilha fictícia para organizar receitas e despesas da casa mês a mês.', 'FINANCE', 1990),
  ('00000000-0000-4000-8000-000000000104', '[Fictício] Tutorial de senhas seguras', 'Tutorial fictício sobre gerenciadores de senhas e verificação em duas etapas.', 'SECURITY', 3990),
  ('00000000-0000-4000-8000-000000000105', '[Fictício] Guia de estudo para concursos', 'E-book fictício com um plano de estudos de doze semanas para concursos públicos.', 'EDUCATION', 4990)
on conflict (id) do nothing;
