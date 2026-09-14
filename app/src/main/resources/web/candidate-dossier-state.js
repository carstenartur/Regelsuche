/* One authoritative correlation adapter for the retained #663 scenario DTO. */
(() => {
    'use strict';
    const SCHEMA = 'regelsuche.target-free-sympy-bridge-discovery/v1';
    const SEARCH = 'regelsuche.target-free-representation-search/v1';
    const NATIVE = 'regelsuche.target-free-search-execution/v1';
    const supports = schema => schema === SCHEMA || schema === NATIVE;
    const fail = (message) => { throw new Error(message); };
    const freeze = (value) => {
        if (value && typeof value === 'object' && !Object.isFrozen(value)) {
            Object.values(value).forEach(freeze); Object.freeze(value);
        }
        return value;
    };
    function decode(workspace, reply) {
        const reference = workspace.artifacts.find(a => a.role === 'CANDIDATE_DOSSIERS');
        if (reference.status !== 'AVAILABLE' || !supports(reference.artifactSchema)) fail('Dossier nicht verfügbar oder Schema nicht unterstützt');
        const artifact = window.RegelsucheRunWorkspace.parseExactJson(reply.raw), data = artifact.content;
        if (reply.runId !== workspace.runId || artifact.contentHash !== reference.targetContentHash
            || reply.etag !== '"' + artifact.contentHash.slice(7) + '"') fail('Dossier-Antwort gehört zu einem anderen Run oder Artefakt');
        if (reference.artifactSchema === NATIVE) return decodeNative(workspace, artifact);
        if (!data || data.schema !== SCHEMA || !data.search || data.search.schema !== SEARCH
            || !data.freeze || !data.discoveredBridge || !data.classification || !data.followOnExecution) fail('Nicht unterstützter Dossier-Vertrag');
        if (data.search.sourceExpression !== workspace.input.displayText
            || data.freeze.enabledBoundaryHash !== workspace.plan.informationBoundaryHash
            || data.freeze.enabledCatalogHash !== workspace.plan.knownStructureCatalogHash
            || data.search.ruleInventoryHash !== workspace.plan.ruleInventoryHash) fail('Dossier und Run-Kontext stimmen nicht überein');
        for (const role of ['SEARCH_GRAPH', 'REPRESENTATION_CANDIDATES', 'PROGRESS_LEDGER']) {
            const bound = workspace.artifacts.find(a => a.role === role);
            if (!bound || bound.status !== 'AVAILABLE' || bound.artifactSchema !== SEARCH
                || bound.targetContentHash !== data.searchContentHash) fail('Ungebundener Suchbeleg: ' + role);
        }
        if (!Array.isArray(data.search.states) || !data.search.states.length || !Array.isArray(data.search.transitions)
            || new Set(data.search.states.map(s => s.stateHash)).size !== data.search.states.length
            || !data.search.states.some(s => s.stateHash === data.discoveredBridge.stateHash && s.depth > 0)) fail('Kandidaten oder Graphbelege fehlen');
        return freeze(artifact);
    }
    function decodeNative(workspace, artifact) {
        const data = artifact.content;
        if (!data || data.schema !== NATIVE || data.goalStatus !== 'UNTARGETED' || data.bestDistance !== -1
            || data.stageAssessment !== 'NOT_EVALUATED' || JSON.stringify(data.input) !== JSON.stringify(workspace.input)
            || JSON.stringify(data.plan) !== JSON.stringify(workspace.plan) || JSON.stringify(data.revisions) !== JSON.stringify(workspace.revisions)
            || workspace.outcome.canonicalWorkLedgerHash !== artifact.contentHash
            || !Array.isArray(data.states) || !data.states.length || !Array.isArray(data.generations)
            || !Array.isArray(data.transitions) || !Array.isArray(data.events) || !data.events.length
            || data.metrics?.exploredStates !== data.states.length || data.work?.generatedTransformations !== data.generations.length
            || new Set(data.states.map(s => s.stateId)).size !== data.states.length) fail('Unvollständiger oder fremder nativer Suchbeleg');
        for (const role of ['SEARCH_GRAPH', 'REPRESENTATION_CANDIDATES', 'PROGRESS_LEDGER', 'PATH_REPLAY']) {
            const bound = workspace.artifacts.find(a => a.role === role);
            if (!bound || bound.status !== 'AVAILABLE' || bound.artifactSchema !== NATIVE
                || bound.targetContentHash !== artifact.contentHash) fail('Ungebundener nativer Suchbeleg: ' + role);
        }
        data.states.forEach(state => {
            if (!/^sha256:[0-9a-f]{64}$/.test(state.stateId) || typeof state.canonicalStateJson !== 'string'
                || !Array.isArray(state.generationSequences)) fail('Ungültige native Zustandsidentität');
            const value = window.RegelsucheRunWorkspace.parseExactJson(state.canonicalStateJson);
            if (value.schema !== 'regelsuche.search-state-replay/v2'
                || value.scoringRevision !== 'regelsuche.expression-score/v2'
                || value.score?.scoringRevision !== value.scoringRevision || !value.executionRetained
                || value.depth !== state.generationSequences.length || value.path?.[0] !== workspace.input.displayText
                || state.generationSequences.some(i => !Number.isInteger(i) || !data.generations[i] || data.generations[i].sequence !== i)) {
                fail('Native Zustandslinie ist nicht an ihre Erzeugungen gebunden');
            }
        });
        return freeze(artifact);
    }
    function selection(artifact, candidateId, edge = '') {
        if (artifact.content.schema === NATIVE) {
            const data = artifact.content, candidate = data.states.find(s => s.stateId === candidateId);
            if (!candidate) fail('Kandidat ist nicht in diesem Run enthalten. Auswahl bleibt unverändert.');
            const edges = data.transitions.filter(t => t.toStateId === candidateId);
            const selectedEdge = edge ? edges.find(t => String(t.sequence) === edge) : null;
            if (edge && !selectedEdge) fail('Graphkante gehört nicht zum ausgewählten Kandidaten.');
            const observation = window.RegelsucheRunWorkspace.parseExactJson(candidate.canonicalStateJson);
            return freeze({native: true, candidate, observation, edges, selectedEdge,
                generations: candidate.generationSequences.map(i => data.generations[i])});
        }
        const data = artifact.content, search = data.search;
        const candidate = search.states.find(s => s.stateHash === candidateId && s.depth > 0);
        if (!candidate) fail('Kandidat ist nicht in diesem Run enthalten. Auswahl bleibt unverändert.');
        const edges = search.transitions.filter(t => t.toStateHash === candidateId);
        const selectedEdge = edge ? edges.find(t => String(t.sequence) === edge) : null;
        if (edge && !selectedEdge) fail('Graphkante gehört nicht zum ausgewählten Kandidaten.');
        return freeze({candidate, source: search.states[0], edges, selectedEdge,
            bridge: data.discoveredBridge.stateHash === candidateId ? data.discoveredBridge : null});
    }
    function createStore(load, changed = () => {}) {
        let generation = 0, current = freeze({status: 'EMPTY', runId: '', artifact: null, error: ''});
        const publish = value => { current = freeze(value); changed(current); };
        return Object.freeze({
            state: () => current,
            clear: () => { generation++; publish({status: 'EMPTY', runId: '', artifact: null, error: ''}); },
            open: async (workspace, loader = () => load(workspace)) => {
                const token = ++generation;
                publish({status: 'LOADING', runId: workspace.runId, artifact: null, error: ''});
                try {
                    const reply = await loader();
                    if (generation !== token) return;
                    publish({status: 'READY', runId: workspace.runId, artifact: decode(workspace, reply), error: ''});
                } catch (error) {
                    if (generation !== token) return;
                    publish({status: 'ERROR', runId: workspace.runId, artifact: null, error: error.message || String(error)});
                }
            }
        });
    }
    window.RegelsucheCandidateDossierState = Object.freeze({schema: SCHEMA, nativeSchema: NATIVE, supports, decode, selection, createStore});
})();
