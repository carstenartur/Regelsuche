/* Immutable correlation for retained runs. The canonical Java HTTP repository
 * owns content-hash verification; the browser checks the requested identity,
 * transport binding and supported DTO before displaying its retained bytes. */
(() => {
    'use strict';
    const SCHEMA = 'regelsuche.representation-discovery-run-workspace/v1';
    const DIGEST = /^[0-9a-f]{64}$/;
    const HASH = /^sha256:[0-9a-f]{64}$/;
    const ROLES = Object.freeze(['SEARCH_GRAPH', 'REPRESENTATION_CANDIDATES',
        'CANDIDATE_DOSSIERS', 'PATH_REPLAY', 'RULE_RADAR', 'PROOF_OBLIGATIONS',
        'EXPORT_BUNDLE', 'PROGRESS_LEDGER']);
    const STATES = Object.freeze(['CREATED', 'RUNNING', 'COMPLETED',
        'BUDGET_EXHAUSTED', 'NO_RESULT', 'CANCELLED', 'FAILED', 'UNSUPPORTED']);
    const STATUSES = Object.freeze(['AVAILABLE', 'NOT_PRODUCED', 'UNSUPPORTED', 'FAILED']);
    const fail = (message) => { throw new Error(message); };
    const text = (value, name) => typeof value === 'string' ? value : fail('Ungültiges Feld: ' + name);
    const hash = (value, name) => HASH.test(text(value, name)) ? value : fail('Ungültiger Hash: ' + name);
    const freeze = (value) => {
        if (value && typeof value === 'object' && !Object.isFrozen(value)) {
            Object.values(value).forEach(freeze);
            Object.freeze(value);
        }
        return value;
    };

    function parseExactJson(raw) {
        // Preserve all string tokens. Quote only integer tokens outside the
        // exact Number range; canonical Java long counters/seeds remain exact.
        const preserved = raw.replace(/"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g, (token) => {
            if (/^-?\d+$/.test(token) && !Number.isSafeInteger(Number(token))) {
                return JSON.stringify(token);
            }
            return token;
        });
        return JSON.parse(preserved);
    }

    function integer(value) {
        if ((typeof value === 'number' && !Number.isSafeInteger(value))
                || !/^-?\d+$/.test(String(value))) fail('Ungültiger ganzzahliger Arbeitswert');
        const parsed = BigInt(value);
        if (parsed < -9223372036854775808n || parsed > 9223372036854775807n) {
            fail('Arbeitswert außerhalb des Java-long-Vertrags');
        }
        return parsed;
    }

    function remainingWork(configured, consumed) {
        const total = integer(configured), used = integer(consumed);
        if (used < 0n || total < used) fail('Inkonsistente Arbeitsabrechnung');
        return (total - used).toString();
    }

    function decode(reply, expectedDigest) {
        if (!reply || typeof reply.raw !== 'string') fail('Run-Dokument fehlt');
        const workspace = parseExactJson(reply.raw);
        if (!workspace || workspace.schema !== SCHEMA) fail('Nicht unterstütztes Run-Schema');
        const runId = hash(workspace.runId, 'runId');
        const digest = runId.slice(7);
        if (expectedDigest && digest !== expectedDigest) fail('Antwort gehört zu einem anderen Run');
        if (workspace.contentHash !== runId || reply.etag !== '"' + digest + '"') {
            fail('Run-ID, Manifest und ETag stimmen nicht überein');
        }
        const input = workspace.input, plan = workspace.plan, outcome = workspace.outcome;
        if (!input || !plan || !outcome || !workspace.revisions) fail('Run-Vertrag ist unvollständig');
        ['domainId', 'inputSchema', 'displayText'].forEach((key) => text(input[key], key));
        if (!Array.isArray(input.assumptions) || input.assumptions.some((value) => typeof value !== 'string')) {
            fail('Annahmen sind unvollständig');
        }
        ['informationTrack', 'searchStrategyId', 'searchProfileId', 'objectiveId'].forEach((key) => text(plan[key], key));
        ['informationBoundaryHash', 'ruleInventoryHash', 'knowledgePackSelectionHash',
            'knownStructureCatalogHash', 'budgetHash'].forEach((key) => hash(plan[key], key));
        if (!Array.isArray(plan.backendIdentities) || !plan.backendIdentities.length
                || plan.backendIdentities.some((value) => typeof value !== 'string')) fail('Backend-Identitäten fehlen');
        integer(plan.deterministicSeed);
        if (!STATES.includes(outcome.state)) fail('Nicht unterstützter Run-Zustand');
        text(outcome.terminalReason, 'terminalReason');
        remainingWork(outcome.configuredWork, outcome.consumedWork);
        if (outcome.state === 'CREATED' && (integer(outcome.configuredWork) !== 0n
                || integer(outcome.consumedWork) !== 0n || outcome.terminalReason !== 'NOT_STARTED')) {
            fail('Ungültiger CREATED-Zustand');
        }
        ['canonicalWorkLedgerHash', 'runtimeDiagnosticsHash'].forEach((key) => hash(outcome[key], key));
        const artifacts = workspace.artifacts;
        if (!Array.isArray(artifacts) || artifacts.length !== ROLES.length
                || new Set(artifacts.map((item) => item.role)).size !== ROLES.length) {
            fail('Artefaktrollen fehlen oder sind doppelt');
        }
        artifacts.forEach((item) => {
            if (!ROLES.includes(item.role) || !STATUSES.includes(item.status)) fail('Nicht unterstützte Artefaktrolle oder Status');
            text(item.artifactSchema, 'artifactSchema');
            text(item.detail, 'detail');
            hash(item.targetContentHash, 'targetContentHash');
        });
        return freeze(workspace);
    }

    function createStore(load, onChange = () => {}) {
        let generation = 0;
        let current = freeze({status: 'EMPTY', digest: '', role: '', workspace: null, raw: '', error: ''});
        function publish(value) {
            current = freeze(value);
            onChange(current);
        }
        async function execute(digest, role, loader) {
            const token = ++generation;
            publish({status: 'LOADING', digest: digest || '', role: role || '', workspace: null, raw: '', error: ''});
            try {
                if (digest !== null && (typeof digest !== 'string' || !DIGEST.test(digest))) fail('Ungültige Run-ID');
                if (role && !ROLES.includes(role)) fail('Ungebundene Artefaktauswahl');
                const reply = await loader();
                if (token !== generation) return;
                const workspace = decode(reply, digest);
                publish({status: 'READY', digest: workspace.runId.slice(7), role: role || '',
                    workspace, raw: reply.raw, error: ''});
            } catch (error) {
                if (token !== generation) return;
                publish({status: 'ERROR', digest: digest || '', role: role || '', workspace: null, raw: '',
                    error: error instanceof Error ? error.message : String(error)});
            }
        }
        return Object.freeze({
            state: () => current,
            open: (digest, role = '') => execute(digest, role, () => load(digest)),
            import: (loader) => execute(null, '', loader),
            clear: () => {
                generation++;
                publish({status: 'EMPTY', digest: '', role: '', workspace: null, raw: '', error: ''});
            },
            selectRole: (role) => {
                if (!ROLES.includes(role)) fail('Ungebundene Artefaktauswahl');
                if (current.status !== 'READY') fail('Kein gespeicherter Run geöffnet');
                publish({...current, role});
            }
        });
    }

    window.RegelsucheRunWorkspace = Object.freeze({createStore, parseExactJson, remainingWork, roles: ROLES});
})();
