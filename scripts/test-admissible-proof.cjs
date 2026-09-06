/* node --test scripts/test-admissible-proof.cjs; no third-party dependency. */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const zlib = require('node:zlib');
const checker = require('../app/src/main/resources/web/admissible-proof.js');
const source = zlib.gunzipSync(fs.readFileSync(path.join(__dirname, '../app/src/e2eTest/resources/admissible/ci-window-128.json.gz'))).toString('utf8');
const bundle = () => JSON.parse(source);
const canonical = data => JSON.stringify(data) + '\n';
const sha = s => crypto.createHash('sha256').update(s).digest('hex');

test('exact small proof and all child alternatives', () => {
    const proof = checker.parseProof(checker.example);
    assert.equal(proof.checkedNodes, 2);
    const first = checker.step(proof, proof.root);
    assert.equal(first.prime, 2);
    const second = checker.step(proof, first.children[0].mask);
    assert.equal(second.prime, 3);
    assert.deepEqual(second.children.map(c => c.size), [4, 3]);
    assert.equal(checker.step(proof, second.children[0].mask).terminal, 'bound');
});
test('actual CI certificates imported, old and selected', async () => {
    const result = await checker.checkBundle(source);
    assert.equal(result.runs.length, 2);
    assert.equal(result.selectedPolicy, 'first');
    assert.ok(result.runs.every(r => r.status === 'OPTIMAL' && r.witness.length === 28));
});
test('missing root is rejected', () => assert.throws(() => checker.parseProof(checker.example.replace('1ff:2\n', ''))));
test('missing descendant is rejected', () => assert.throws(() => checker.parseProof(checker.example.replace('155:3\n', ''))));
test('composite branch is rejected', () => assert.throws(() => checker.parseProof(checker.example.replace('1ff:2', '1ff:4'))));
test('noncovering branch is rejected', () => assert.throws(() => checker.parseProof(checker.example.replace('155:3', '155:2'))));
test('unused node is rejected', () => assert.throws(() => checker.parseProof(checker.example.replace('155:3', '1:2\n155:3'))));
test('noncanonical node and duplicate node are rejected', () => {
    for (const extra of ['0155:3', '155:3\n155:3'])
        assert.throws(() => checker.parseProof(checker.example.replace('155:3', extra)));
});
test('invalid lower witness is rejected', () => {
    for (const witness of ['[0, 2, 4, 6, 8]', '[0, 2, 6, 10]', '[0, 2, 2, 8]', '[2, 6, 8]'])
        assert.throws(() => checker.parseProof(checker.example.replace('[0, 2, 6, 8]', witness)));
});
test('work budget does not confirm a partial proof', () => {
    assert.throws(() => checker.parseProof(checker.example, checker.budget(1)), /budget/);
});
test('changed proof rejected after recalculating digest', async () => {
    const data = bundle(), run = data.runs[0], old = sha(run.proof);
    const lines = run.proof.trimEnd().split('\n'); lines.pop();
    run.proof = lines.join('\n') + '\n'; run.receipt = run.receipt.replace(old, sha(run.proof));
    await assert.rejects(checker.checkBundle(canonical(data)));
});
test('arithmetic counter sum must match', async () => {
    const data = bundle(); data.runs[0].receipt = data.runs[0].receipt.replace(/\ncost=\d+\n/, '\ncost=0\n');
    await assert.rejects(checker.checkBundle(canonical(data)), /Arbeitswert/);
});
test('all wrong policy, scope, role and duplicate-run claims fail', async () => {
    for (const change of [d => d.runs[0].role = 'selected', d => d.selectedPolicy = 'legacy',
        d => d.runs[0].case = 'different', d => d.runs.push(d.runs[0]), d => d.runs[0].case = '<script>']) {
        const data = bundle(); change(data); await assert.rejects(checker.checkBundle(canonical(data)));
    }
});
test('unknown fields, duplicate JSON keys, deeply nested JSON and byte limit fail', async () => {
    const data = bundle(); data.unexpected = true;
    for (const text of [canonical(data), source.replace('"schema":', '"schema":"duplicate","schema":'),
        '['.repeat(100) + '0' + ']'.repeat(100), ' '.repeat(checker.MAX_BYTES + 1)])
        await assert.rejects(checker.checkBundle(text));
});
test('budget-exhausted record carries only a checked lower witness', async () => {
    const data = bundle();
    for (const run of data.runs) {
        run.receipt = run.receipt.replace('status=OPTIMAL', 'status=BUDGET_EXHAUSTED')
            .replace(/proofSha256=[0-9a-f]{64}/, 'proofSha256=NONE');
        run.proof = null;
    }
    const result = await checker.checkBundle(canonical(data));
    assert.ok(result.runs.every(r => r.status === 'BUDGET_EXHAUSTED' && r.checkedNodes === 0));
    data.runs[0].proof = checker.example;
    await assert.rejects(checker.checkBundle(canonical(data)));
});
test('integer overflow and negative costs fail', async () => {
    for (const value of ['-1', '9223372036854775808', '01']) {
        const data = bundle(); data.runs[0].receipt = data.runs[0].receipt.replace(/\ncost=\d+\n/, '\ncost=' + value + '\n');
        await assert.rejects(checker.checkBundle(canonical(data)));
    }
});

test('missing WebCrypto is a clear domain error', async () => {
    const original = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
    try {
        Object.defineProperty(globalThis, 'crypto', {value: undefined, configurable: true});
        await assert.rejects(checker.checkBundle(source), /localhost oder HTTPS/);
    } finally {
        if (original) Object.defineProperty(globalThis, 'crypto', original);
        else delete globalThis.crypto;
    }
});
test('canonical envelope accepts only optional single LF', async () => {
    await checker.checkBundle(source.trimEnd());
    for (const text of [' ' + source, '\n' + source, source + '\n', source + ' ', source.trimEnd() + '\r\n'])
        await assert.rejects(checker.checkBundle(text), /Nichtkanonisches/);
});
