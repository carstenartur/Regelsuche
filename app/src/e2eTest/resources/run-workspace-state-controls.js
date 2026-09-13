/* Run in the checkout-owned Java Playwright suite; no Node build dependency. */
window.runWorkspaceStateControls = async function (sample) {
    const api = window.RegelsucheRunWorkspace;
    const check = (condition, message) => { if (!condition) throw new Error(message); };
    check(api && typeof api.createStore === 'function', 'Retained run state controller is missing');
    const first = sample.runId.slice(7);
    const second = (first[0] === 'a' ? 'b' : 'a') + first.slice(1);
    const fixture = (digest) => JSON.stringify({...sample, runId: 'sha256:' + digest, contentHash: 'sha256:' + digest});
    const reply = (digest) => ({raw: fixture(digest), etag: '"' + digest + '"'});
    const pending = new Map();
    const snapshots = [];
    const store = api.createStore((digest) => new Promise((resolve, reject) => pending.set(digest, {resolve, reject})),
        (state) => snapshots.push(state));
    const slow = store.open(first, 'SEARCH_GRAPH');
    const fast = store.open(second, 'PATH_REPLAY');
    pending.get(second).resolve(reply(second));
    await fast;
    const selected = store.state();
    check(selected.status === 'READY' && selected.workspace.runId === 'sha256:' + second, 'newer run did not load');
    check(selected.role === 'PATH_REPLAY', 'artifact selection was lost');
    check(Object.isFrozen(selected) && Object.isFrozen(selected.workspace.artifacts), 'active state is mutable');
    pending.get(first).resolve(reply(first));
    await slow;
    check(store.state() === selected, 'late success overwrote the newer run');

    const lateFailure = store.open(first);
    store.clear();
    pending.get(first).reject(new Error('delayed network error'));
    await lateFailure;
    check(store.state().status === 'EMPTY', 'late failure overwrote a cleared selection');

    const bad = api.createStore(async () => reply(second), () => {});
    await bad.open(first);
    check(bad.state().status === 'ERROR' && !bad.state().workspace, 'foreign run was accepted');
    const tag = api.createStore(async () => ({...reply(first), etag: '"' + second + '"'}), () => {});
    await tag.open(first);
    check(tag.state().status === 'ERROR', 'foreign ETag was accepted');

    let calls = 0;
    const invalid = api.createStore(async () => { calls++; return reply(first); }, () => {});
    await invalid.open('../global-state');
    check(calls === 0 && invalid.state().status === 'ERROR', 'invalid digest reached the transport');
    await invalid.open(first, 'UNBOUND_ROLE');
    check(calls === 0 && invalid.state().status === 'ERROR', 'unbound selection reached the transport');

    const raw = '{"seed":9223372036854775807,"work":9007199254740993,"small":42,"negative":-9223372036854775808,"text":"9223372036854775807 \\\" quoted"}';
    const exact = api.parseExactJson(raw);
    check(exact.seed === '9223372036854775807' && exact.work === '9007199254740993', 'large positive long was rounded');
    check(exact.negative === '-9223372036854775808' && exact.small === 42, 'long signs or small values changed');
    check(exact.text === '9223372036854775807 " quoted', 'quoted text changed');
    check(api.remainingWork('9223372036854775807', '9223372036854775806') === '1', 'work subtraction lost precision');

    const duplicate = {...sample, artifacts: [...sample.artifacts.slice(1), sample.artifacts[1]]};
    const roles = api.createStore(async () => ({raw: JSON.stringify(duplicate), etag: '"' + first + '"'}), () => {});
    await roles.open(first);
    check(roles.state().status === 'ERROR', 'duplicate/missing artifact role was accepted');

    const version = api.createStore(async () => ({raw: JSON.stringify({...sample, schema: 'future/v99'}), etag: '"' + first + '"'}), () => {});
    await version.open(first);
    check(version.state().status === 'ERROR', 'unknown workspace schema was accepted');
    const good = api.createStore(async () => reply(first), () => {});
    await good.open(first);
    const retained = good.state().raw;
    good.selectRole('PROOF_OBLIGATIONS');
    check(good.state().raw === retained && good.state().workspace.runId === sample.runId, 'role selection mutated retained evidence');
    check(good.state().role === 'PROOF_OBLIGATIONS', 'role selection was not published');
    return '11 state, identity, role, race and integer controls passed';
};
