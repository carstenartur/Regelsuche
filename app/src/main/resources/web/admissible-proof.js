/* Independent finite upper-bound checker. No optimizer, eval or server calls. */
(function (root) {
    'use strict';
    const MAX_BYTES = 8000000, MAX_NODES = 20000, MAX_RUNS = 32;
    const MAX_LONG = (1n << 63n) - 1n;
    const KEYS = `prepare.primalityTests prepare.trialDivisions prepare.residueSlots prepare.residueAssignments
seed.splitCalls seed.primeChecks seed.residueChecks seed.childrenConstructed seed.steps
search.calls search.visited search.deduplicated search.cardinalityPrunes search.branches
search.leaves search.budgetStops search.splitCalls search.primeChecks search.residueChecks search.childrenConstructed
proof.calls proof.cardinalityPrunes proof.deduplicated proof.nodes proof.edges
verify.calls verify.nodes verify.edges verify.cardinalityPrunes verify.deduplicated
verify.primalityTests verify.trialDivisions verify.residueAssignments verify.residueChecks verify.bitTests trace.events`.split(/\s+/).sort();
    const COST_KEYS = ['prepare.trialDivisions', 'prepare.residueAssignments', 'seed.residueChecks',
        'seed.childrenConstructed', 'search.calls', 'search.residueChecks', 'search.childrenConstructed',
        'proof.edges', 'verify.trialDivisions', 'verify.residueAssignments', 'verify.residueChecks', 'verify.bitTests'];
    const HEADER = ['policy', 'universe', 'maximumNodes', 'status', 'witness', 'proofSha256',
        'decisionSha256', 'featureVisits', 'cost'];
    function requireThat(ok, message) { if (!ok) throw new Error(message); }
    function objectKeys(value, expected) {
        requireThat(value && !Array.isArray(value) && typeof value === 'object'
            && Object.keys(value).sort().join('|') === [...expected].sort().join('|'), 'Unerwartete Datenfelder.');
    }
    function integer(value, maximum = MAX_LONG) {
        requireThat(typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value), 'Ungültiger Ganzzahlwert.');
        const n = BigInt(value);
        requireThat(n <= maximum, 'Ganzzahlgrenze überschritten.');
        return n;
    }
    function universe(values) {
        requireThat(Array.isArray(values) && values.length > 0 && values.length <= 257 && values[0] === 0,
            'Die Positionsmenge muss mit 0 beginnen und höchstens 257 Elemente haben.');
        values.forEach((n, i) => requireThat(Number.isSafeInteger(n) && n >= 0 && n <= 4096
            && (i === 0 || n > values[i - 1]), 'Positionen müssen sortiert, eindeutig und in 0..4096 sein.'));
        return values;
    }
    function prime(n) {
        if (!Number.isSafeInteger(n) || n < 2 || n > 257) return false;
        for (let d = 2; d * d <= n; d++) if (n % d === 0) return false;
        return true;
    }
    function count(mask) { let n = 0; while (mask) { mask &= mask - 1n; n++; } return n; }
    function budget(maximum = 8000000) {
        let remaining = maximum;
        return (n = 1) => { remaining -= n; requireThat(remaining >= 0, 'Prüfbudget erschöpft; kein bestätigtes Ergebnis.'); };
    }
    function witness(universeValues, values, tick) {
        universe(values);
        requireThat(values.every(n => universeValues.includes(n)), 'Zeuge liegt außerhalb der Aufgabe.');
        for (let p = 2; p <= values.length; p++) if (prime(p)) {
            tick(values.length);
            requireThat(new Set(values.map(n => n % p)).size < p, 'Der Zeuge ist nicht zulässig.');
        }
    }
    function groups(values, mask, p, tick = () => {}) {
        const residues = Array(p).fill(0n);
        values.forEach((n, i) => {
            tick();
            const bit = 1n << BigInt(i);
            if ((mask & bit) !== 0n) residues[n % p] |= bit;
        });
        return residues;
    }
    function parseProof(text, tick = budget()) {
        requireThat(typeof text === 'string' && text.length <= MAX_BYTES && text.endsWith('\n'), 'Ungültige Beweisdatei.');
        const lines = text.slice(0, -1).split('\n');
        requireThat(lines.length <= MAX_NODES + 1, 'Zu viele Beweisknoten.');
        const match = /^admissible-cardinality\/v1;universe=(\[[0-9, ]+\]);witness=(\[[0-9, ]+\])$/.exec(lines[0]);
        requireThat(match !== null, 'Ungültiger Beweiskopf.');
        const u = universe(JSON.parse(match[1])), w = universe(JSON.parse(match[2]));
        witness(u, w, tick);
        const rootMask = (1n << BigInt(u.length)) - 1n, nodes = new Map();
        let previous = 0n;
        for (const line of lines.slice(1)) {
            tick();
            const item = /^([1-9a-f][0-9a-f]{0,64}):([1-9][0-9]{0,2})$/.exec(line);
            requireThat(item !== null, 'Ungültiger Beweisknoten.');
            const mask = BigInt('0x' + item[1]), p = Number(item[2]);
            requireThat(mask > previous && mask <= rootMask && prime(p), 'Doppelte, ungeordnete oder ungültige Verzweigung.');
            previous = mask;
            nodes.set(item[1], p);
        }
        const todo = [rootMask], seen = new Set();
        while (todo.length) {
            tick();
            const mask = todo.pop(), key = mask.toString(16), size = count(mask);
            if (size <= w.length || seen.has(key)) continue;
            requireThat(nodes.has(key), 'Erforderlicher Beweiszweig fehlt.');
            const p = nodes.get(key);
            requireThat(p <= size, 'Verzweigungsprimzahl ist zu groß.');
            const residues = groups(u, mask, p, tick);
            requireThat(residues.every(r => r !== 0n), 'Verzweigung deckt nicht jede Restklasse.');
            seen.add(key);
            for (const residue of residues.slice(1)) { tick(); todo.push(mask & ~residue); }
        }
        requireThat(seen.size === nodes.size, 'Nicht erreichbare Beweisknoten.');
        return {universe: u, witness: w, root: rootMask.toString(16), nodes: [...nodes], checkedNodes: seen.size};
    }
    function parseReceipt(text, tick) {
        requireThat(typeof text === 'string' && text.length <= 20000 && text.endsWith('\n'), 'Ungültiges Arbeitsprotokoll.');
        const lines = text.slice(0, -1).split('\n');
        requireThat(lines.shift() === 'admissible-policy-run/v1' && lines.length === HEADER.length + KEYS.length,
            'Unbekanntes Protokollformat.');
        const expected = [...HEADER, ...KEYS], f = Object.create(null);
        lines.forEach((line, i) => {
            const at = line.indexOf('=');
            requireThat(at > 0 && line.slice(0, at) === expected[i], 'Ungeordnete oder doppelte Protokollfelder.');
            f[expected[i]] = line.slice(at + 1);
        });
        requireThat(/^(legacy|first|linear-[014]-[014]-(large|small))$/.test(f.policy), 'Unbekannte Strategie.');
        requireThat(['OPTIMAL', 'BUDGET_EXHAUSTED'].includes(f.status), 'Unbekannter Ergebnisstatus.');
        const u = universe(JSON.parse(f.universe)), w = universe(JSON.parse(f.witness));
        witness(u, w, tick);
        requireThat(integer(f.maximumNodes, BigInt(MAX_NODES)) > 0n, 'Ungültiges Knotenbudget.');
        for (const key of [...KEYS, 'featureVisits', 'cost']) integer(f[key]);
        requireThat(/^[0-9a-f]{64}$/.test(f.decisionSha256), 'Ungültige Entscheidungskennung.');
        const cost = COST_KEYS.reduce((sum, key) => sum + BigInt(f[key]), BigInt(f.featureVisits));
        requireThat(cost === BigInt(f.cost), 'Arbeitswert stimmt nicht mit den Zählern überein.');
        requireThat(BigInt(f['search.visited']) <= BigInt(f.maximumNodes), 'Knotenbudget verletzt.');
        return {fields: f, universe: u, witness: w};
    }
    async function digest(text) {
        return [...new Uint8Array(await root.crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)))]
            .map(n => n.toString(16).padStart(2, '0')).join('');
    }
    async function checkBundle(text) {
        requireThat(typeof text === 'string' && text.length <= MAX_BYTES
            && new TextEncoder().encode(text).length <= MAX_BYTES, 'Datei überschreitet 8 MB.');
        // A bounded flat envelope: avoid deeply nested JSON before parsing it.
        let depth = 0, quoted = false, escaped = false;
        for (const c of text) {
            if (quoted) { if (escaped) escaped = false; else if (c === '\\') escaped = true; else if (c === '"') quoted = false; }
            else if (c === '"') quoted = true;
            else if (c === '{' || c === '[') { depth++; requireThat(depth <= 4, 'JSON ist zu tief verschachtelt.'); }
            else if (c === '}' || c === ']') depth--;
        }
        const data = JSON.parse(text);
        // Canonical compact JSON also rejects duplicate object keys before any trusted rendering.
        requireThat(JSON.stringify(data) === text.trim(), 'Nichtkanonisches JSON oder doppelte Schlüssel.');
        objectKeys(data, ['schema', 'sourceManifestSha256', 'selectedPolicy', 'runs']);
        requireThat(data.schema === 'admissible-workbench/v1' && typeof data.sourceManifestSha256 === 'string'
            && /^[0-9a-f]{64}$/.test(data.sourceManifestSha256), 'Unbekannter Import.');
        requireThat(typeof data.selectedPolicy === 'string' && /^(legacy|first|linear-[014]-[014]-(large|small))$/.test(data.selectedPolicy), 'Ungültige ausgewählte Regel.');
        requireThat(Array.isArray(data.runs) && data.runs.length > 0 && data.runs.length <= MAX_RUNS, 'Ungültige Anzahl Läufe.');
        const seen = new Set(), runs = [], tick = budget();
        for (const run of data.runs) {
            objectKeys(run, ['case', 'role', 'receipt', 'proof']);
            requireThat(typeof run.case === 'string' && /^[a-z][a-z0-9-]{0,40}$/.test(run.case)
                && ['legacy', 'selected'].includes(run.role), 'Ungültige Laufkennung.');
            const key = run.case + '/' + run.role;
            requireThat(!seen.has(key), 'Doppelter Lauf.'); seen.add(key);
            const receipt = parseReceipt(run.receipt, tick), f = receipt.fields;
            requireThat(f.policy === (run.role === 'legacy' ? 'legacy' : data.selectedPolicy), 'Falsch zugeordnete Strategie.');
            let proof;
            if (f.status === 'OPTIMAL') {
                proof = parseProof(run.proof, tick);
                requireThat(JSON.stringify(proof.universe) === JSON.stringify(receipt.universe)
                    && JSON.stringify(proof.witness) === JSON.stringify(receipt.witness), 'Beweis gehört nicht zum Lauf.');
                requireThat(await digest(run.proof) === f.proofSha256, 'Beweis-Prüfsumme stimmt nicht.');
                requireThat(BigInt(proof.checkedNodes) === BigInt(f['proof.nodes']), 'Beweisknotenzahl stimmt nicht.');
            } else {
                requireThat(run.proof === null && f.proofSha256 === 'NONE', 'Budgetabbruch darf keinen Optimalitätsbeweis tragen.');
                proof = {universe: receipt.universe, witness: receipt.witness,
                    root: ((1n << BigInt(receipt.universe.length)) - 1n).toString(16), nodes: [], checkedNodes: 0};
            }
            runs.push({...proof, case: run.case, role: run.role, policy: f.policy,
                status: f.status, cost: f.cost, visited: f['search.visited'], maximumNodes: f.maximumNodes});
        }
        for (const run of runs) {
            const partner = runs.find(other => other.case === run.case && other.role !== run.role);
            requireThat(partner && JSON.stringify(partner.universe) === JSON.stringify(run.universe)
                && partner.maximumNodes === run.maximumNodes, 'Vergleich braucht dieselbe Aufgabe und dasselbe Budget.');
            if (run.status === 'OPTIMAL' && partner.status === 'OPTIMAL')
                requireThat(run.witness.length === partner.witness.length, 'Widersprüchliche Maxima.');
        }
        return {sourceManifestSha256: data.sourceManifestSha256, selectedPolicy: data.selectedPolicy, runs};
    }
    function step(model, key) {
        const mask = BigInt('0x' + key), size = count(mask), p = new Map(model.nodes).get(key);
        const values = model.universe.filter((_, i) => (mask & (1n << BigInt(i))) !== 0n);
        if (size <= model.witness.length) return {size, values, terminal: 'bound', children: []};
        if (!p) return {size, values, terminal: 'unproved', children: []};
        const residues = groups(model.universe, mask, p);
        return {size, values, prime: p, children: residues.slice(1).map((r, i) => {
            const child = mask & ~r;
            return {residue: i + 1, mask: child.toString(16), size: count(child), removed: count(r)};
        })};
    }
    const example = 'admissible-cardinality/v1;universe=[0, 1, 2, 3, 4, 5, 6, 7, 8];witness=[0, 2, 6, 8]\n155:3\n1ff:2\n';
    const api = {MAX_BYTES, parseProof, checkBundle, step, example, budget};
    root.AdmissibleProof = api;
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(globalThis);
