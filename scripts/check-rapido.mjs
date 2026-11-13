// Quick check: lint always, then only the tests of the areas this branch changed.
// The full `just check` stays for the CI. Writes .tmp/check-rapido.log and .tmp/check-rapido-resultado.json
// (read by the Stop hook and by the verificar-entrega skill) and prints a short summary.
import { execFileSync, spawnSync } from 'node:child_process';
import { appendFileSync, existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const PACKAGE = 'br/com/sergio/sapiensia';
const FEATURES = ['admin', 'audit', 'customer', 'demo', 'email', 'product'];
const BACKEND_JAVA = new RegExp(`^backend/src/(?:main|test)/java/${PACKAGE}/([^/]+)/`);
const REQUIREMENT_ID = /\b(?:SEC|FR|NFR)-\d{3}\b/g;

// Pure: which test sets a list of changed files needs.
// backend: 'none' | 'all' | string[] of -Dtest patterns; frontend: changed files under frontend/src; nodeTests: .claude or scripts changed.
export function chooseTests(changedFiles) {
  const features = new Set();
  const frontend = [];
  let backendAll = false;
  let nodeTests = false;
  for (const f of changedFiles) {
    if (f.startsWith('frontend/src/')) frontend.push(f);
    if (f.startsWith('.claude/') || f.startsWith('scripts/')) nodeTests = true;
    if (!f.startsWith('backend/')) continue;
    const feature = BACKEND_JAVA.exec(f)?.[1];
    if (feature && FEATURES.includes(feature)) features.add(feature);
    else backendAll = true; // payment, security, web, root package, resources, migrations, pom.xml...
  }
  let backend = 'none';
  if (backendAll) backend = 'all';
  else if (features.size) backend = [...features].map((x) => `${PACKAGE}/${x}/**`);
  return { backend, frontend, nodeTests };
}

// Pure: unique, sorted requirement IDs (SEC-, FR-, NFR-) found in the given texts.
export function extractRequirementIds(texts) {
  const ids = new Set();
  for (const t of texts) for (const id of t.match(REQUIREMENT_ID) || []) ids.add(id);
  return [...ids].sort();
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
  const diff = base ? lines(git(['diff', '--name-only', base], root)) : [];
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

  const changed = changedFilesOf(root);
  const chosen = chooseTests(changed);
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
  if (changed.some((f) => f.startsWith('frontend/'))) {
    steps.push({ nome: 'tipos do frontend (tsc -b)', cmd: 'pnpm exec tsc -b', cwd: join(root, 'frontend') });
    const related = chosen.frontend.filter((f) => existsSync(join(root, f))).map((f) => f.replace(/^frontend\//, ''));
    if (related.length) steps.push({ nome: `testes do frontend (${related.length} arquivo(s) e relacionados)`, cmd: `pnpm exec vitest related --run ${related.join(' ')}`, cwd: join(root, 'frontend') });
  }
  if (chosen.backend === 'all') {
    steps.push({ nome: 'testes do backend (todos)', cmd: `${mvnw} test`, cwd: join(root, 'backend') });
  } else if (chosen.backend !== 'none') {
    const patterns = [...chosen.backend, ...citing].join(',');
    steps.push({ nome: `testes do backend (${patterns})`, cmd: `${mvnw} test -Dtest=${patterns} -Dsurefire.failIfNoSpecifiedTests=false`, cwd: join(root, 'backend') });
  }
  if (chosen.nodeTests) {
    steps.push({ nome: 'testes de .claude e scripts (node --test)', cmd: 'node --test ".claude/**/*.test.mjs" "scripts/*.test.mjs"', cwd: root });
  }

  console.log(`Check rapido: ${changed.length} arquivo(s) mudado(s). Testes escolhidos:`);
  for (const s of steps) console.log(`  - ${s.nome}`);
  if (ids.length) console.log(`  (requisitos citados nos arquivos mudados: ${ids.join(', ')})`);

  const inicio = Date.now();
  let falhou = null;
  // One heavy command at a time; stop at the first failure.
  for (const s of steps) {
    appendFileSync(logPath, `\n===== ${s.cmd} (${s.cwd}) =====\n`);
    const r = spawnSync(s.cmd, { cwd: s.cwd, shell: true, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });
    appendFileSync(logPath, `${r.stdout || ''}${r.stderr || ''}`);
    if (r.status !== 0) {
      falhou = s;
      break;
    }
  }
  const segundos = Math.round((Date.now() - inicio) / 1000);
  const ok = falhou === null;
  writeFileSync(join(tmp, 'check-rapido-resultado.json'), JSON.stringify({ ok, quando: Date.now(), segundos, tipo: 'rapido' }));

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
