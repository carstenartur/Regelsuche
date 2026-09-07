'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const api = require(path.resolve(__dirname, '../app/src/main/resources/web/admissible-proof.js'));
const old = JSON.parse(zlib.gunzipSync(fs.readFileSync(path.resolve(__dirname,
    '../app/src/e2eTest/resources/admissible/ci-window-128.json.gz'))).toString('utf8'));
function live() { return {schema: 'admissible-workbench/v2', scope: 'exploratory',
    sourceManifestSha256: old.sourceManifestSha256, selectedPolicy: old.selectedPolicy, runs: old.runs}; }
test('old experiment import stays unchanged', async () => {
    const result = await api.checkBundle(JSON.stringify(old));
    assert.equal(result.exploratory, undefined);
    assert.equal(result.runs.length, 2);
});
test('new explorative scope survives serialize and reimport', async () => {
    const encoded = JSON.stringify(live()) + '\n';
    const result = await api.checkBundle(encoded);
    assert.equal(result.exploratory, true);
    assert.equal(result.runs.length, 2);
    assert.equal(result.runs[0].witness.length, 28);
    assert.equal((await api.checkBundle(JSON.stringify(JSON.parse(encoded)))).exploratory, true);
});
test('new schema requires exactly the declared exploratory scope', async () => {
    for (const scope of [undefined, 'holdout', '', null, {verified: true}]) {
        const value = live(); value.scope = scope;
        await assert.rejects(api.checkBundle(JSON.stringify(value)));
    }
});
test('scope cannot extend the old closed schema silently', async () => {
    await assert.rejects(api.checkBundle(JSON.stringify({...old, scope: 'exploratory'})));
});
test('exploratory marking never bypasses mathematical verification', async () => {
    const value = live(); value.runs = structuredClone(value.runs);
    value.runs[0].proof = value.runs[0].proof.replace('admissible-cardinality/v1', 'wrong/v1');
    await assert.rejects(api.checkBundle(JSON.stringify(value)));
});
