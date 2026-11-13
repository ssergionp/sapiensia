import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { after, test } from 'node:test';

const hook = join(import.meta.dirname, 'check-antes-de-parar.mjs');
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

// Throwaway repo on branch fix/1-x with one backend file changed; its mtime is fixed at EDIT.
const EDIT = 1_700_000_000_000;
function criarRepo() {
  const repo = mkdtempSync(join(tmpdir(), 'gancho-'));
  dirs.push(repo);
  git(repo, 'init', '-b', 'main');
  git(repo, 'config', 'user.name', 'Test');
  git(repo, 'config', 'user.email', 'test@example.com');
  git(repo, 'config', 'commit.gpgsign', 'false');
  escrever(repo, 'README.md', 'x\n');
  git(repo, 'add', '-A');
  git(repo, 'commit', '-m', 'init');
  git(repo, 'checkout', '-b', 'fix/1-x');
  escrever(repo, 'backend/src/A.java', 'class A {}\n');
  utimesSync(join(repo, 'backend/src/A.java'), EDIT / 1000, EDIT / 1000);
  return repo;
}

function resultado(repo, nome, ok, quando) {
  escrever(repo, `.tmp/${nome}.json`, JSON.stringify({ ok, quando, segundos: 1 }));
}

function rodar(repo, extra = {}) {
  return execFileSync(process.execPath, [hook], { input: JSON.stringify({ cwd: repo, ...extra }), encoding: 'utf8' });
}

test('blocks when no check result exists', () => {
  const out = JSON.parse(rodar(criarRepo()));
  assert.equal(out.decision, 'block');
  assert.match(out.reason, /just check-rapido ainda não rodou/);
  assert.match(out.reason, /rode just check-rapido/);
});

test('passes with a fresh green quick check', () => {
  const repo = criarRepo();
  resultado(repo, 'check-rapido-resultado', true, EDIT + 1000);
  assert.equal(rodar(repo), '');
});

test('blocks when the newest result (quick) failed, even if an older full check passed', () => {
  const repo = criarRepo();
  resultado(repo, 'check-resultado', true, EDIT + 1000);
  resultado(repo, 'check-rapido-resultado', false, EDIT + 2000);
  const out = JSON.parse(rodar(repo));
  assert.equal(out.decision, 'block');
  assert.match(out.reason, /O último check desta branch falhou/);
});

test('a newer green full check wins over an older failed quick check', () => {
  const repo = criarRepo();
  resultado(repo, 'check-rapido-resultado', false, EDIT + 1000);
  resultado(repo, 'check-resultado', true, EDIT + 2000);
  assert.equal(rodar(repo), '');
});

test('blocks when the green quick check is older than the last code edit', () => {
  const repo = criarRepo();
  resultado(repo, 'check-rapido-resultado', true, EDIT - 1000);
  const out = JSON.parse(rodar(repo));
  assert.equal(out.decision, 'block');
  assert.match(out.reason, /Houve edição de código depois do último check/);
});

test('does not block twice (stop_hook_active)', () => {
  assert.equal(rodar(criarRepo(), { stop_hook_active: true }), '');
});
