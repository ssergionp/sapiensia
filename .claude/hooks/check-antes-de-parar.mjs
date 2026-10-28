// Trava do fim da resposta (hook Stop): numa branch de ticket, o Claude só encerra depois de um
// "just check-resumo" que passou e é mais novo que a última edição de código.
// Só olha datas de arquivos; não roda teste nenhum. Bloqueia uma vez só: se o Claude parar de novo
// (por exemplo, para fazer uma pergunta ao usuário), deixa passar.
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

function git(pasta, args) {
  try {
    return execFileSync('git', ['-C', pasta, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 5000 }).trim();
  } catch {
    return null;
  }
}

try {
  const entrada = JSON.parse(readFileSync(0, 'utf8'));
  if (entrada.stop_hook_active) process.exit(0);
  const raiz = git(entrada.cwd || process.cwd(), ['rev-parse', '--show-toplevel']);
  if (!raiz) process.exit(0);
  const branch = git(raiz, ['branch', '--show-current']) || '';
  if (!/^(feat|fix|chore)\//.test(branch)) process.exit(0);

  const base = git(raiz, ['merge-base', 'HEAD', 'origin/main']) || git(raiz, ['merge-base', 'HEAD', 'main']);
  if (!base) process.exit(0);
  const mudados = new Set([
    ...(git(raiz, ['diff', '--name-only', base]) || '').split('\n'),
    ...(git(raiz, ['ls-files', '--others', '--exclude-standard']) || '').split('\n'),
  ]);
  // só código e configuração do backend e do frontend contam; documentação não
  let ultimaEdicao = 0;
  for (const f of mudados) {
    if (!/^(backend|frontend)\//.test(f) || /\.md$/i.test(f)) continue;
    const caminho = join(raiz, f);
    if (existsSync(caminho)) ultimaEdicao = Math.max(ultimaEdicao, statSync(caminho).mtimeMs);
  }
  if (ultimaEdicao === 0) process.exit(0);

  let check = null;
  const arquivo = join(raiz, '.tmp', 'check-resultado.json');
  if (existsSync(arquivo)) check = JSON.parse(readFileSync(arquivo, 'utf8'));
  let motivo = null;
  if (!check) motivo = 'O código desta branch mudou e o just check-resumo ainda não rodou nela.';
  else if (!check.ok) motivo = 'O último just check-resumo desta branch falhou.';
  else if (check.quando < ultimaEdicao) motivo = 'Houve edição de código depois do último just check-resumo.';
  if (!motivo) process.exit(0);

  process.stdout.write(JSON.stringify({
    decision: 'block',
    reason: `${motivo} Antes de dizer que terminou, rode just check-resumo e mostre o resultado. Se você parou só para fazer uma pergunta ao usuário, pode parar de novo.`,
  }));
  process.exit(0);
} catch {
  process.exit(0); // erro interno: não atrapalha
}
