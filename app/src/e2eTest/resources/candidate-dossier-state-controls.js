/* Exercises the real correlation adapter in the checkout-owned browser suite. */
window.candidateDossierStateControls = async function (run, artifact) {
    const api = window.RegelsucheCandidateDossierState;
    const check = (condition, message) => { if (!condition) throw new Error(message); };
    const reject = (call, message) => {
        let rejected = false; try { call(); } catch (_) { rejected = true; }
        check(rejected, message);
    };
    const reply = {raw: JSON.stringify(artifact), runId: run.runId, etag: '"' + artifact.contentHash.slice(7) + '"'};
    const decoded = api.decode(run, reply);
    const bridgeId = decoded.content.discoveredBridge.stateHash;
    const initial = api.selection(decoded, bridgeId);
    check(initial.capabilityChange.executed, 'verified bridge lost its executed follow-on');
    check(initial.capabilityChange.consequence === decoded.content.discoveredBridge.consequenceId,
        'consequence must belong to the selected bridge');
    const altered = JSON.parse(reply.raw);
    const candidate = altered.content.search.states.find(state => state.stateHash === bridgeId);
    candidate.metrics.tokenCount = altered.content.search.states[0].metrics.tokenCount + 2;
    check(api.selection(altered, bridgeId).capabilityChange.tokenDelta === 2,
        'a longer candidate must retain both its measured cost and its earned capability');
    altered.content.followOnExecution.disabledTargetSuccessors = ['already available'];
    check(!api.selection(altered, bridgeId).capabilityChange.executed,
        'enabled result was already available to the disabled control');
    const other = decoded.content.search.states.find(state => state.depth > 0 && state.stateHash !== bridgeId);
    check(api.selection(decoded, other.stateHash).capabilityChange === null, 'another candidate inherited an unlock');
    check(api.selection(decoded, other.stateHash).bridge === null, 'another candidate inherited bridge evidence');
    check(api.selection(decoded, bridgeId).bridge.candidateProofStatus === 'SYMBOLICALLY_VERIFIED', 'retained validation status changed');
    check(Object.isFrozen(decoded.content.search.states), 'candidate artifact remained mutable');
    reject(() => api.decode(run, {...reply, runId: 'sha256:' + 'f'.repeat(64)}), 'foreign Run ID accepted');
    reject(() => api.decode(run, {...reply, etag: '"' + 'f'.repeat(64) + '"'}), 'foreign artifact ETag accepted');
    reject(() => api.decode(run, {...reply, raw: JSON.stringify({...artifact, contentHash: 'sha256:' + 'f'.repeat(64)})}), 'foreign artifact hash accepted');
    reject(() => api.decode(run, {...reply, raw: JSON.stringify({...artifact, content: {...artifact.content,
        search: {...artifact.content.search, sourceExpression: 'foreign source'}}})}), 'foreign source accepted');
    reject(() => api.selection(decoded, 'sha256:' + 'f'.repeat(64)), 'missing candidate silently replaced');
    const foreignEdge = decoded.content.search.transitions.find(edge => edge.toStateHash !== bridgeId);
    reject(() => api.selection(decoded, bridgeId, String(foreignEdge.sequence)), 'edge from another candidate accepted');

    let finishSlow, failSlow;
    const store = api.createStore(() => new Promise(resolve => { finishSlow = resolve; }));
    const slow = store.open(run);
    await store.open(run, async () => reply);
    const selected = store.state();
    finishSlow({...reply, runId: 'sha256:' + 'f'.repeat(64)}); await slow;
    check(store.state() === selected, 'late artifact response overwrote newer evidence');
    const error = store.open(run, () => new Promise((resolve, reject) => { failSlow = reject; }));
    store.clear(); failSlow(new Error('late error')); await error;
    check(store.state().status === 'EMPTY', 'late artifact failure revived cleared run');

    const runReply = {raw: JSON.stringify(run), etag: '"' + run.runId.slice(7) + '"'};
    const selectionStore = window.RegelsucheRunWorkspace.createStore(async () => runReply);
    await selectionStore.open(run.runId.slice(7), 'CANDIDATE_DOSSIERS', bridgeId);
    selectionStore.selectRole('PROOF_OBLIGATIONS');
    check(selectionStore.state().candidate === bridgeId, 'role navigation lost selected candidate');
    const raw = selectionStore.state().raw;
    selectionStore.selectCandidate(other.stateHash);
    check(selectionStore.state().raw === raw && selectionStore.state().workspace.runId === run.runId, 'selection changed historical manifest');
    return 'candidate correlation and stale-response controls passed';
};
