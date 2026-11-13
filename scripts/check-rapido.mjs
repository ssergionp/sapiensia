// Quick check: lint always, then only the tests of the areas this branch changed.
// The full `just check` stays for the CI. Writes .tmp/check-rapido.log and .tmp/check-rapido-resultado.json
// (read by the Stop hook and by the verificar-entrega skill) and prints a short summary.
import { execFileSync, spawnSync } from 'node:child_process';
import { appendFileSync, existsSync, mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const PACKAGE = 'br/com/sergio/sapiensia';
const FEATURES = ['admin', 'audit', 'customer', 'demo', 'email', 'product'];
const BACKEND_JAVA = new RegExp(`^backend/src/(?:main|test)/java/${PACKAGE}/([^/]+)/`);
const REQUIREMENT_ID = /\b(?:SEC|FR|NFR)-\d{3}\b/g;

// Pure: which test sets a list of changed files needs.
// backend: 'none' | 'all' | string[] of -Dtest patterns; frontend: 'all' (config/tooling change outside src and e2e) or the changed files under frontend/src; nodeTests: .claude or scripts changed.
export function chooseTests(changedFiles) {
  const features = new Set();
  const frontend = [];
  let frontendAll = false;
  let backendAll = false;
  let nodeTests = false;
  for (const f of changedFiles) {
    if (f.startsWith('frontend/src/')) frontend.push(f);
    // package.json, lockfile, vite/vitest/tsconfig...: any test can be affected. e2e files are Playwright, not vitest; docs do not count.
    else if (f.startsWith('frontend/') && !f.startsWith('frontend/e2e/') && !/\.md$/i.test(f)) frontendAll = true;
    if (f.startsWith('.claude/') || f.startsWith('scripts/')) nodeTests = true;
    if (!f.startsWith('backend/')) continue;
    const feature = BACKEND_JAVA.exec(f)?.[1];
    if (feature && FEATURES.includes(feature)) features.add(feature);
    else backendAll = true; // payment, security, web, root package, resources, migrations, pom.xml...
  }
  let backend = 'none';
  if (backendAll) backend = 'all';
  else if (features.size) backend = [...features].map((x) => `${PACKAGE}/${x}/**`);
  return { backend, frontend: frontendAll ? 'all' : frontend, nodeTests };
}

// Pure: null means no comparison base could be found, so the changes are unknown: fail closed and run everything.
export function selectionFor(changedFiles) {
  if (changedFiles === null) return { backend: 'all', frontend: 'all', nodeTests: true };
  return chooseTests(changedFiles);
}

// Pure: unique, sorted requirement IDs (SEC-, FR-, NFR-) found in the given texts.
export function extractRequirementIds(texts) {
  const ids = new Set();
  for (const t of texts) for (const id of t.match(REQUIREMENT_ID) || []) ids.add(id);
  return [...ids].sort();
}

// Pure: total tests Surefire reports in a Maven output (the final "Tests run: N, Failures: ..." line, the one
// without "-- in <class>"). Returns null when there is no summary at all.
export function surefireTotal(output) {
  let total = null;
  for (const l of output.split('\n')) {
    const m = /Tests run: (\d+), Failures: \d+, Errors: \d+, Skipped: \d+\s*$/.exec(l.trim());
    if (m) total = Number(m[1]);
  }
  return total;
}

function git(args, cwd) {
  try {
    return execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], maxBuffer: 64 * 1024 * 1024 }).trim();
  } catch {
    return '';
  }
}

const lines = (s) => s.split('\n').filter(Boolean);

function changedFilesOf(root) {
  const base = git(['merge-base', 'HEAD', 'origin/main'], root) || git(['merge-base', 'HEAD', 'main'], root);
  if (!base) return null; // unknown base: the caller runs everything
  const diff = lines(git(['diff', '--name-only', base], root));
  return [...new Set([...diff, ...lines(git(['ls-files', '--others', '--exclude-standard'], root))])];
}

// Test classes in backend/src/test that cite the given requirement IDs (names for -Dtest).
function testClassesCiting(ids, root) {
  const classes = new Set();
  for (const id of ids) {
    for (const file of lines(git(['grep', '-l', id, '--', 'backend/src/test'], root))) {
      const m = /([^/]+Test)\.java$/.exec(file);
      if (m) classes.add(m[1]);
    }
  }
  return [...classes].sort();
}

function run() {
  const root = git(['rev-parse', '--show-toplevel'], process.cwd()) || process.cwd();
  const tmp = join(root, '.tmp');
  mkdirSync(tmp, { recursive: true });
  const logPath = join(tmp, 'check-rapido.log');
  writeFileSync(logPath, '');

  const changedOrNull = changedFilesOf(root);
  const semBase = changedOrNull === null;
  const changed = changedOrNull ?? [];
  const chosen = selectionFor(changedOrNull);
  const mvnw = process.platform === 'win32' ? '.\\mvnw.cmd' : './mvnw';

  let ids = [];
  let citing = [];
  if (Array.isArray(chosen.backend)) {
    const texts = changed
      .filter((f) => existsSync(join(root, f)) && statSync(join(root, f)).isFile() && statSync(join(root, f)).size < 1_000_000)
      .map((f) => readFileSync(join(root, f), 'utf8'));
    ids = extractRequirementIds(texts);
    citing = testClassesCiting(ids, root);
  }

  const steps = [{ nome: 'lint (Spotless + Biome)', cmd: 'just lint', cwd: root }];
  if (semBase || changed.some((f) => f.startsWith('frontend/'))) {
    steps.push({ nome: 'tipos do frontend (tsc -b)', cmd: 'pnpm exec tsc -b', cwd: join(root, 'frontend') });
    if (chosen.frontend === 'all') {
      steps.push({ nome: 'testes do frontend (todos)', cmd: 'pnpm exec vitest run --passWithNoTests', cwd: join(root, 'frontend') });
    } else {
      const related = chosen.frontend.filter((f) => existsSync(join(root, f))).map((f) => f.replace(/^frontend\//, ''));
      if (related.length) steps.push({ nome: `testes do frontend (${related.length} arquivo(s) e relacionados)`, cmd: `pnpm exec vitest related --run --passWithNoTests ${related.join(' ')}`, cwd: join(root, 'frontend') });
    }
  }
  if (chosen.backend === 'all') {
    steps.push({ backend: true, nome: 'testes do backend (todos)', cmd: `${mvnw} test`, cwd: join(root, 'backend') });
  } else if (chosen.backend !== 'none') {
    const patterns = [...chosen.backend, ...citing].join(',');
    steps.push({ backend: true, nome: `testes do backend (${patterns})`, cmd: `${mvnw} test -Dtest=${patterns} -Dsurefire.failIfNoSpecifiedTests=false`, cwd: join(root, 'backend') });
  }
  if (chosen.nodeTests) {
    steps.push({ nome: 'testes de .claude e scripts (node --test)', cmd: 'node --test ".claude/**/*.test.mjs" "scripts/*.test.mjs"', cwd: root });
  }

  console.log(`Check rapido: ${changed.length} arquivo(s) mudado(s). Testes escolhidos:`);
  if (semBase) console.log('  (sem base de comparacao com a main: roda tudo)');
  for (const s of steps) console.log(`  - ${s.nome}`);
  if (ids.length) console.log(`  (requisitos citados nos arquivos mudados: ${ids.join(', ')})`);

  const inicio = Date.now();
  let falhou = null;
  // One heavy command at a time; stop at the first failure.
  for (const s of steps) {
    appendFileSync(logPath, `\n===== ${s.cmd} (${s.cwd}) =====\n`);
    // nosemgrep: javascript.lang.security.audit.spawn-shell-true.spawn-shell-true -- fixed commands; only repo file paths are interpolated; shell needed for .cmd shims on Windows
    const r = spawnSync(s.cmd, { cwd: s.cwd, shell: true, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });
    const saida = `${r.stdout || ''}${r.stderr || ''}`;
    appendFileSync(logPath, saida);
    // a backend selection that ran zero tests is missing evidence, never a pass
    const vazio = s.backend && r.status === 0 && !((surefireTotal(saida) ?? 0) > 0);
    if (vazio) appendFileSync(logPath, '\nNenhum teste do backend rodou: o filtro nao casou com nada.\n');
    if (r.status !== 0 || vazio) {
      falhou = s;
      break;
    }
  }
  const segundos = Math.round((Date.now() - inicio) / 1000);
  const ok = falhou === null;
  // atomic: write a temp file, then rename, so the hook never reads a half-written result
  const resultado = join(tmp, 'check-rapido-resultado.json');
  writeFileSync(`${resultado}.tmp`, JSON.stringify({ ok, quando: Date.now(), segundos, tipo: 'rapido' }));
  renameSync(`${resultado}.tmp`, resultado);

  const log = lines(readFileSync(logPath, 'utf8'));
  if (ok) {
    console.log(`CHECK RAPIDO OK em ${segundos} s (${log.length} linhas de log em .tmp/check-rapido.log)`);
    const resumo = log.filter((l) => /Tests run: \d+, Failures|Test Files |^\s+Tests\s|# (tests|pass|fail) /.test(l)).slice(-6);
    for (const l of resumo) console.log(`  ${l.trim()}`);
    return 0;
  }
  console.log(`CHECK RAPIDO FALHOU em ${segundos} s, passo: ${falhou.nome} (${log.length} linhas de log em .tmp/check-rapido.log)`);
  console.log('--- linhas com erro (ate 40) ---');
  for (const l of log.filter((x) => /\[ERROR\]|FAIL|error:|Error:|BUILD FAILURE|Failures: [1-9]|Errors: [1-9]|^not ok|^# fail [1-9]/.test(x)).slice(0, 40)) console.log(l);
  console.log('--- ultimas 25 linhas ---');
  for (const l of log.slice(-25)) console.log(l);
  return 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exit(run());
}
