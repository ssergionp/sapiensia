import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { test } from 'node:test';

const TITLE =
  'NFR-008 backup workflow never uploads artifacts, pins actions and only runs on schedule or manually';
const FILE = new URL('../.github/workflows/backup.yml', import.meta.url);
// A missing file reads as empty text, so the first assertion fails instead of the test crashing.
const yml = existsSync(FILE) ? readFileSync(FILE, 'utf8') : '';
const lines = yml.split('\n');

/** Lines of the top-level block `key:` (the indented lines that follow it). */
function topBlock(key) {
  const start = lines.findIndex((l) => l === `${key}:`);
  if (start < 0) return [];
  const out = [];
  for (const l of lines.slice(start + 1)) {
    if (l.trim() !== '' && !l.startsWith(' ')) break;
    out.push(l);
  }
  return out;
}

test(`${TITLE}: triggers are schedule and workflow_dispatch only, at 17 7,19 UTC`, () => {
  const keys = topBlock('on')
    .filter((l) => /^ {2}\S/.test(l))
    .map((l) => l.trim().replace(/:.*$/, ''));
  assert.deepEqual(keys, ['schedule', 'workflow_dispatch']);
  assert.ok(yml.includes("cron: '17 7,19 * * *'"));
});

test(`${TITLE}: every uses: is pinned to a 40 character SHA`, () => {
  const uses = lines.filter((l) => /^\s*-?\s*uses:/.test(l));
  assert.ok(uses.length > 0);
  for (const l of uses) assert.match(l, /uses:\s*[\w.-]+\/[\w.-]+@[0-9a-f]{40}(\s|$)/);
});

test(`${TITLE}: no upload-artifact and no cache`, () => {
  assert.ok(yml.length > 0);
  assert.doesNotMatch(yml, /upload-artifact/);
  assert.doesNotMatch(yml, /actions\/cache/);
});

test(`${TITLE}: permissions are only contents read and issues write`, () => {
  const perms = topBlock('permissions')
    .map((l) => l.trim())
    .filter(Boolean);
  assert.deepEqual(perms, ['contents: read', 'issues: write']);
});

test(`${TITLE}: the alert step runs on failure`, () => {
  assert.match(yml, /^\s*if: failure\(\)\s*$/m);
});

test(`${TITLE}: secrets are only referenced in env, never inside run`, () => {
  assert.ok(yml.length > 0);
  let inRun = false;
  let runIndent = 0;
  for (const l of lines) {
    const indent = l.length - l.trimStart().length;
    if (inRun && l.trim() !== '' && indent <= runIndent) inRun = false;
    if (inRun) assert.doesNotMatch(l, /\$\{\{/, `expression inside run: ${l.trim()}`);
    if (/^\s*(-\s+)?run:/.test(l)) {
      assert.doesNotMatch(l, /secrets\./);
      if (/run:\s*[|>]/.test(l)) {
        inRun = true;
        runIndent = indent;
      } else assert.doesNotMatch(l, /\$\{\{/);
    }
  }
});

test(`${TITLE}: the backup step requires TLS for the database connection`, () => {
  assert.match(yml, /^\s*PGSSLMODE:\s*require\s*$/m);
});

test(`${TITLE}: the alert step sets GH_REPO, so it works even when checkout failed`, () => {
  const start = lines.findIndex((l) => /^\s*-\s+name:\s*Report the failure/.test(l));
  assert.ok(start >= 0, 'alert step not found');
  const indent = lines[start].length - lines[start].trimStart().length;
  const step = [];
  for (const l of lines.slice(start + 1)) {
    if (l.trim() !== '' && l.length - l.trimStart().length <= indent) break;
    step.push(l);
  }
  assert.ok(step.some((l) => /^\s*GH_REPO:\s*\$\{\{\s*github\.repository\s*\}\}\s*$/.test(l)));
});
