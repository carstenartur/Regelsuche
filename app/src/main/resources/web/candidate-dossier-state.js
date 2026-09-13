/* One authoritative correlation adapter for the retained #663 scenario DTO. */
(() => {
    'use strict';
    const SCHEMA = 'regelsuche.target-free-sympy-bridge-discovery/v1';
    const SEARCH = 'regelsuche.target-free-representation-search/v1';
    const fail = (message) => { throw new Error(message); };
    const freeze = (value) => {
        if (value && typeof value === 'object' && !Object.isFrozen(value)) {
            Object.values(value).forEach(freeze); Object.freeze(value);
        }
        return value;
    };
    function decode(workspace, reply) {
        const reference = workspace.artifacts.find(a => a.role === 'CANDIDATE_DOSSIERS');
        if (reference.status !== 'AVAILABLE' || reference.artifactSchema !== SCHEMA) fail('Dossier nicht verfügbar oder Schema nicht unterstützt');
        const artifact = window.RegelsucheRunWorkspace.parseExactJson(reply.raw), data = artifact.content;
        if (reply.runId !== workspace.runId || artifact.contentHash !== reference.targetContentHash
            || reply.etag !== '"' + artifact.contentHash.slice(7) + '"') fail('Dossier-Antwort gehört zu einem anderen Run oder Artefakt');
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
    function selection(artifact, candidateId, edge = '') {
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
    window.RegelsucheCandidateDossierState = Object.freeze({schema: SCHEMA, decode, selection, createStore});
})();
