import assert from 'node:assert/strict';
import { test } from 'node:test';
import { chooseTests, extractRequirementIds, surefireTotal } from './check-rapido.mjs';

const J = 'backend/src/main/java/br/com/sergio/sapiensia';

test('frontend source change picks related vitest files and no backend', () => {
  const r = chooseTests(['frontend/src/a.ts']);
  assert.equal(r.backend, 'none');
  assert.deepEqual(r.frontend, ['frontend/src/a.ts']);
  assert.equal(r.nodeTests, false);
});

test('a per-feature backend change picks only that feature', () => {
  const r = chooseTests([`${J}/product/X.java`]);
  assert.deepEqual(r.backend, ['br/com/sergio/sapiensia/product/**']);
});

test('test sources of a feature count as that feature', () => {
  const r = chooseTests(['backend/src/test/java/br/com/sergio/sapiensia/demo/XTest.java']);
  assert.deepEqual(r.backend, ['br/com/sergio/sapiensia/demo/**']);
});

test('security changes run all backend tests', () => {
  assert.equal(chooseTests([`${J}/security/SecurityConfig.java`]).backend, 'all');
});

test('payment changes run all backend tests', () => {
  assert.equal(chooseTests([`${J}/payment/AsaasWebhookService.java`]).backend, 'all');
});

test('migrations run all backend tests', () => {
  assert.equal(chooseTests(['backend/src/main/resources/db/migration/V19__x.sql']).backend, 'all');
});

test('pom.xml runs all backend tests', () => {
  assert.equal(chooseTests(['backend/pom.xml']).backend, 'all');
});

test('root package changes run all backend tests', () => {
  assert.equal(chooseTests([`${J}/App.java`]).backend, 'all');
});

test('docs only changes pick nothing', () => {
  assert.deepEqual(chooseTests(['docs/a.md']), { backend: 'none', frontend: [], nodeTests: false });
});

test('.claude and scripts changes run the node tests', () => {
  assert.equal(chooseTests(['.claude/hooks/x.mjs']).nodeTests, true);
  assert.equal(chooseTests(['scripts/x.mjs']).nodeTests, true);
});

test('several features are merged without duplicates', () => {
  const r = chooseTests([`${J}/product/A.java`, `${J}/product/B.java`, `${J}/email/C.java`]);
  assert.deepEqual(r.backend, ['br/com/sergio/sapiensia/product/**', 'br/com/sergio/sapiensia/email/**']);
});

test('all wins over per-feature', () => {
  assert.equal(chooseTests([`${J}/product/A.java`, 'backend/pom.xml']).backend, 'all');
});

test('extractRequirementIds finds unique SEC, FR and NFR ids', () => {
  const ids = extractRequirementIds(['cites SEC-047 and FR-001', 'again SEC-047, NFR-012; XSEC-1 FR-12']);
  assert.deepEqual(ids, ['FR-001', 'NFR-012', 'SEC-047']);
});

test('surefireTotal reads the final Surefire summary', () => {
  const out = [
    '[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 s -- in a.BTest',
    '[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 1',
    '[INFO] BUILD SUCCESS',
  ].join('\n');
  assert.equal(surefireTotal(out), 14);
});

test('surefireTotal is null when nothing ran, and 0 for an empty summary', () => {
  assert.equal(surefireTotal('[INFO] BUILD SUCCESS'), null);
  assert.equal(surefireTotal('[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0'), 0);
});
