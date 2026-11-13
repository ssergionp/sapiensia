import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { after, test } from 'node:test';

const conferir = join(import.meta.dirname, 'conferir.mjs');
const dirs = [];

after(() => {
  for (const d of dirs) rmSync(d, { recursive: true, force: true });
});

const git = (cwd, ...args) => execFileSync('git', args, { cwd, encoding: 'utf8' });

function escrever(repo, rel, conteudo) {
  const p = join(repo, rel);
  mkdirSync(dirname(p), { recursive: true });
  writeFileSync(p, conteudo);
}

// Throwaway repo: one commit on main, then branch fix/1-x (no origin, so the script falls back to main).
function criarRepo(arquivosNaMain = {}) {
  const repo = mkdtempSync(join(tmpdir(), 'conferir-'));
  dirs.push(repo);
  git(repo, 'init', '-b', 'main');
  git(repo, 'config', 'user.name', 'Test');
  git(repo, 'config', 'user.email', 'test@example.com');
  git(repo, 'config', 'commit.gpgsign', 'false');
  escrever(repo, 'README.md', 'x\n');
  for (const [rel, c] of Object.entries(arquivosNaMain)) escrever(repo, rel, c);
  git(repo, 'add', '-A');
  git(repo, 'commit', '-m', 'init');
  git(repo, 'checkout', '-b', 'fix/1-x');
  return repo;
}

function commitar(repo, arquivos) {
  for (const [rel, c] of Object.entries(arquivos)) escrever(repo, rel, c);
  git(repo, 'add', '-A');
  git(repo, 'commit', '-m', 'work');
}

const rodar = (repo) =>
  execFileSync(process.execPath, [conferir], { cwd: repo, encoding: 'utf8' });

test('recognizes an ID cited only in an e2e test title', () => {
  const repo = criarRepo();
  commitar(repo, {
    'docs/superpowers/plans/p.md': 'Covers FR-001\n',
    'frontend/e2e/a.e2e.ts': 'test("FR-001: abre o catálogo", async () => {});\n',
  });
  assert.match(rodar(repo), /OK: FR-001 citado num teste desta branch/);
});

test('still flags an ID no test cites', () => {
  const repo = criarRepo();
  commitar(repo, {
    'docs/superpowers/plans/p.md': 'Covers FR-001 and SEC-002\n',
    'frontend/e2e/a.e2e.ts': 'test("FR-001: abre o catálogo", async () => {});\n',
  });
  assert.match(rodar(repo), /REQUISITO SEM TESTE: SEC-002/);
});

test('section 1 accepts a fresh green quick check and names it', () => {
  const repo = criarRepo();
  commitar(repo, { 'backend/src/A.java': 'class A {}\n' });
  escrever(repo, '.tmp/check-rapido-resultado.json', JSON.stringify({ ok: true, quando: Date.now() + 60000, segundos: 5, tipo: 'rapido' }));
  assert.match(rodar(repo), /- OK: check rápido passou/);
});

test('section 1 reports when no check ran', () => {
  const repo = criarRepo();
  commitar(repo, { 'backend/src/A.java': 'class A {}\n' });
  assert.match(rodar(repo), /REPROVADO: nenhum check rodou nesta branch/);
});

test('flags a skip added to an e2e file', () => {
  const repo = criarRepo({
    'frontend/e2e/a.e2e.ts': 'test("abre", async () => {});\n',
  });
  commitar(repo, {
    'frontend/e2e/a.e2e.ts': 'test("abre", async () => {});\ntest.skip("x", async () => {});\n',
  });
  assert.match(rodar(repo), /TESTE DESLIGADO OU ISOLADO/);
});
