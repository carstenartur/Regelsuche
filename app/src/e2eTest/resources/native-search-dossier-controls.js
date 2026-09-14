/* Uses a freshly executed public native run, never a invented ordered application-key set. */
window.nativeSearchDossierControls = async function (run, artifact) {
    const api = window.RegelsucheCandidateDossierState;
    const check = (condition, message) => { if (!condition) throw new Error(message); };
    const reject = (call, message) => { let rejected = false; try { call(); } catch (_) { rejected = true; } check(rejected, message); };
    const reply = {raw: JSON.stringify(artifact), runId: run.runId, etag: '"' + artifact.contentHash.slice(7) + '"'};
    const decoded = api.decode(run, reply);
    const candidate = decoded.content.states.find(state => state.generationSequences.length > 0);
    const selected = api.selection(decoded, candidate.stateId);
    check(selected.observation.schema === 'regelsuche.search-state-replay/v2'
        && selected.observation.scoringRevision === 'regelsuche.expression-score/v2'
        && selected.observation.score.scoringRevision === selected.observation.scoringRevision,
        'native score lost its producer-bound revision');
    for (const mutate of [
        value => { value.schema = 'regelsuche.search-state-replay/v1'; },
        value => { delete value.scoringRevision; },
        value => { value.scoringRevision = 'foreign-score/v1'; },
        value => { delete value.score.scoringRevision; },
        value => { value.score.scoringRevision = 'foreign-score/v1'; }
    ]) {
        const incompatible = JSON.parse(JSON.stringify(artifact));
        const state = incompatible.content.states[0];
        const observation = window.RegelsucheRunWorkspace.parseExactJson(state.canonicalStateJson);
        mutate(observation);
        state.canonicalStateJson = JSON.stringify(observation);
        reject(() => api.decode(run, {...reply, raw: JSON.stringify(incompatible)}),
            'native dossier accepted incompatible scoring provenance');
    }
    check(selected.native && selected.candidate.stateId === candidate.stateId, 'native candidate selection lost its identity');
    check(selected.generations.length === candidate.generationSequences.length, 'ordered execution lost actual generation links');
    check(selected.observation.applicationKeys.every((value, index, values) => index === 0 || values[index - 1] < value), 'application keys are not a canonical set');
    const edge = selected.edges[0];
    check(api.selection(decoded, candidate.stateId, String(edge.sequence)).selectedEdge === edge, 'graph selection substituted another edge');
    const selectionStore = window.RegelsucheRunWorkspace.createStore(async () => ({raw: JSON.stringify(run), etag: '"' + run.runId.slice(7) + '"'}));
    await selectionStore.open(run.runId.slice(7));
    selectionStore.selectCandidate(candidate.stateId, String(edge.sequence));
    check(selectionStore.state().role === 'SEARCH_GRAPH', 'graph selection did not reach the existing run consumer');
    check(Object.isFrozen(selected.generations), 'source occurrences remained mutable');
    check(decoded.content.goalStatus === 'UNTARGETED' && decoded.content.stageAssessment === 'NOT_EVALUATED', 'native status or missing stage evidence changed');
    reject(() => api.selection(decoded, 'sha256:' + 'f'.repeat(64)), 'unknown candidate accepted');
    reject(() => api.selection(decoded, candidate.stateId, '999999'), 'foreign edge accepted');
    reject(() => api.decode(run, {...reply, runId: 'sha256:' + 'f'.repeat(64)}), 'foreign run accepted');
    const altered = JSON.parse(JSON.stringify(artifact)); altered.content.input.displayText = 'unbound source';
    reject(() => api.decode(run, {...reply, raw: JSON.stringify(altered)}), 'source mismatch accepted');
    let finish;
    const store = api.createStore(() => new Promise(resolve => { finish = resolve; }));
    const slow = store.open(run);
    await store.open(run, async () => reply);
    const current = store.state(); finish({...reply, runId: 'sha256:' + 'f'.repeat(64)}); await slow;
    check(store.state() === current, 'late native artifact overwrote current selection');
    return 'native trace, occurrence, graph/replay and stale-response controls passed';
};
