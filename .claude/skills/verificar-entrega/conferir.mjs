// Conferência mecânica da entrega de um ticket. Compara a branch atual com a main e imprime um relatório:
//   1. resultado e data do último just check-resumo
//   2. arquivos alterados que o plano do ticket não cita
//   3. sinais de teste afrouxado: teste apagado ou desligado, verificações removidas
// Não muda nada. Quem decide o que fazer com cada achado é o Claude, com a skill verificar-entrega.
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

function git(args, raiz) {
  try {
    return execFileSync('git', ['-C', raiz, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], maxBuffer: 64 * 1024 * 1024 }).trim();
  } catch {
    return '';
  }
}

const raiz = git(['rev-parse', '--show-toplevel'], process.cwd());
if (!raiz) {
  console.log('Fora de um repositório git.');
  process.exit(0);
}
const branch = git(['branch', '--show-current'], raiz);
const base = git(['merge-base', 'HEAD', 'origin/main'], raiz) || git(['merge-base', 'HEAD', 'main'], raiz);
console.log(`# Conferência da entrega: ${branch}`);
if (!base) {
  console.log('Não achei a main para comparar.');
  process.exit(0);
}

const linhas = (s) => s.split('\n').filter(Boolean);
const alterados = [...new Set([...linhas(git(['diff', '--name-only', base], raiz)), ...linhas(git(['ls-files', '--others', '--exclude-standard'], raiz))])];
const apagados = new Set(linhas(git(['diff', '--name-only', '--diff-filter=D', base], raiz)));

// 1. check
const arquivoCheck = join(raiz, '.tmp', 'check-resultado.json');
let ultimaEdicao = 0;
for (const f of alterados) {
  const c = join(raiz, f);
  if (/^(backend|frontend)\//.test(f) && existsSync(c)) ultimaEdicao = Math.max(ultimaEdicao, statSync(c).mtimeMs);
}
console.log('\n## 1. just check-resumo');
if (!existsSync(arquivoCheck)) {
  console.log('- REPROVADO: não rodou nesta branch.');
} else {
  const r = JSON.parse(readFileSync(arquivoCheck, 'utf8'));
  const velho = r.quando < ultimaEdicao;
  console.log(`- ${r.ok && !velho ? 'OK' : 'REPROVADO'}: ${r.ok ? 'passou' : 'falhou'} em ${new Date(r.quando).toLocaleString('pt-BR')}${velho ? ', mas houve edição de código depois' : ''}.`);
}

// 2. arquivos fora do plano
console.log('\n## 2. Arquivos alterados que o plano não cita');
const planos = alterados.filter((f) => /^docs\/superpowers\/plans\/.+\.md$/.test(f) && existsSync(join(raiz, f)));
if (!planos.length) {
  console.log('- ATENÇÃO: nenhum plano em docs/superpowers/plans/ nesta branch.');
} else {
  const texto = planos.map((p) => readFileSync(join(raiz, p), 'utf8')).join('\n');
  const citados = [...texto.matchAll(/`([^`\s]+)`/g)]
    .map((m) => m[1].replace(/:\d+(-\d+)?$/, '').replace(/^\.\//, ''))
    .filter((c) => c.includes('/') || /\.[a-z]{1,5}$/i.test(c));
  const ignorar = (f) => planos.includes(f) || /^docs\/adr\//.test(f) || /(^|\/)(GLOSSARY|CONTEXT)\.md$/.test(f) || /pnpm-lock\.yaml$/.test(f);
  const citado = (f) => citados.some((c) => f === c || f.endsWith(`/${c}`) || f.startsWith(c.endsWith('/') ? c : `${c}/`));
  const fora = alterados.filter((f) => !ignorar(f) && !citado(f));
  console.log(`- Plano: ${planos.join(', ')}`);
  if (!fora.length) console.log('- OK: todos os arquivos alterados aparecem no plano.');
  else for (const f of fora) console.log(`- FORA DO PLANO: ${f}${apagados.has(f) ? ' (apagado)' : ''}`);
}

// 3. testes afrouxados
console.log('\n## 3. Sinais de teste afrouxado');
const ehTeste = (f) => /(^|\/)src\/test\//.test(f) || /\.(test|spec|e2e)\.[jt]sx?$/.test(f);
const achados = [];
for (const f of alterados.filter(ehTeste)) {
  if (apagados.has(f)) {
    achados.push(`- TESTE APAGADO: ${f}`);
    continue;
  }
  const diff = git(['diff', '--unified=0', base, '--', f], raiz);
  // conta as ocorrências (não as linhas) nas linhas adicionadas (+) ou removidas (-)
  const conta = (sinal, re) => diff.split('\n')
    .filter((l) => l.startsWith(sinal) && !l.startsWith(`${sinal}${sinal}${sinal}`))
    // nosemgrep: javascript.lang.security.audit.detect-non-literal-regexp.detect-non-literal-regexp -- re is one of the fixed patterns below
    .reduce((n, l) => n + (l.match(new RegExp(re.source, 'g')) || []).length, 0);
  const casos = /@Test\b|@ParameterizedTest\b|\b(it|test)(\.(each\([^)]*\)|skip|only|todo))?\s*\(/;
  const verificacoes = /\b(expect|assertThat|assertEquals|assertTrue|assertFalse|assertThrows|assertNotNull|verify)\s*\(|\.andExpect\(/;
  const desligados = /@Disabled\b|@Ignore\b|\.(skip|only|todo)\s*\(|\bx(it|describe|test)\s*\(/;
  const casosRemovidos = conta('-', casos) - conta('+', casos);
  const verifRemovidas = conta('-', verificacoes) - conta('+', verificacoes);
  const novosDesligados = conta('+', desligados);
  if (casosRemovidos > 0) achados.push(`- CASOS DE TESTE A MENOS: ${f} (${casosRemovidos})`);
  if (verifRemovidas > 0) achados.push(`- VERIFICAÇÕES A MENOS: ${f} (${verifRemovidas})`);
  if (novosDesligados > 0) achados.push(`- TESTE DESLIGADO OU ISOLADO (skip, only, @Disabled): ${f} (${novosDesligados})`);
}
console.log(achados.length ? achados.join('\n') : '- OK: nenhum teste apagado, desligado ou com menos verificações.');

// 4. requisitos da spec citados no plano (FR-019, SEC-025...) e os testes desta branch que os citam
console.log('\n## 4. Requisitos da spec citados no plano');
const idsRequisito = /\b(?:FR|SEC|NFR)-\d{3}\b/g;
const textoPlanos = planos.map((p) => readFileSync(join(raiz, p), 'utf8')).join('\n');
const requisitos = [...new Set(textoPlanos.match(idsRequisito) || [])].sort();
if (!requisitos.length) {
  console.log('- Nenhum: o plano não cita requisitos com ID.');
} else {
  const textoTestes = alterados
    .filter((f) => ehTeste(f) && !apagados.has(f) && existsSync(join(raiz, f)))
    .map((f) => readFileSync(join(raiz, f), 'utf8'))
    .join('\n');
  for (const id of requisitos) {
    console.log(textoTestes.includes(id) ? `- OK: ${id} citado num teste desta branch` : `- REQUISITO SEM TESTE: ${id}`);
  }
}

// 5. o que mais conferir
console.log('\n## 5. Outras conferências do ciclo');
const mexeuTela = alterados.some((f) => /^frontend\/src\//.test(f) && !ehTeste(f));
const mexeuApi = alterados.some((f) => /^backend\/src\/main\//.test(f));
console.log(`- Tela alterada: ${mexeuTela ? 'sim, o /verificar-tela é obrigatório' : 'não'}`);
console.log(`- API e tela alteradas: ${mexeuTela && mexeuApi ? 'sim, o teste com o sistema de verdade é obrigatório: banco, API e frontend rodando, o fluxo principal clicado no navegador, sem API simulada (page.route, stub)' : 'não'}`);
