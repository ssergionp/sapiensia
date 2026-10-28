import { execSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';

const file = JSON.parse(readFileSync(0, 'utf8')).tool_input?.file_path;
if (!file || !/\.(ts|tsx|js|jsx|json|css)$/.test(file)) process.exit(0);

// sobe até a pasta do frontend; funciona também dentro de .worktrees/
let dir = dirname(file);
while (!existsSync(join(dir, 'biome.json')) && !existsSync(join(dir, 'biome.jsonc'))) {
  if (dirname(dir) === dir) process.exit(0);
  dir = dirname(dir);
}
try {
  execSync(`pnpm exec biome check --write --no-errors-on-unmatched "${relative(dir, file)}"`, { cwd: dir, stdio: 'pipe' });
} catch (e) {
  process.stderr.write(String(e.stdout || e.message));
  process.exit(2); // mostra o erro do Biome para o Claude corrigir
}
