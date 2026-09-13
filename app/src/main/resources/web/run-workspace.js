/* Retained-run controller for the existing Workbench. No mutable global
 * graph/replay endpoint is used to fill an absent historical artifact. */
(() => {
    'use strict';
    const api = window.RegelsucheRunWorkspace;
    const $ = (id) => document.getElementById(id);
    const base = '/api/discovery-runs';
    const runTab = document.querySelector('[data-tab="runs"]');
    const legacyTabs = [...document.querySelectorAll('.tab')].filter((tab) => !['runs', 'help'].includes(tab.dataset.tab));
    const originalDisabled = new Map(legacyTabs.map((tab) => [tab, tab.disabled]));
    let offset = 0, pageData = null, historyGeneration = 0, historyLoaded = false;
    let comparison = null, previousDigest = '';

    function element(tag, value, parent) {
        const node = document.createElement(tag);
        if (value !== undefined) node.textContent = String(value);
        if (parent) parent.appendChild(node);
        return node;
    }
    const display = (value) => typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value ?? '');
    function table(parent, rows) {
        const body = element('tbody', undefined, element('table', undefined, parent));
        rows.forEach(([name, value]) => {
            const row = element('tr', undefined, body);
            element('th', name, row).scope = 'row';
            element('td', display(value), row);
        });
    }
    async function responseReply(response) {
        const raw = await response.text();
        if (!response.ok) {
            let detail = '';
            try { const error = JSON.parse(raw); detail = [error.code, error.message].filter(Boolean).join(': '); } catch (_) { /* HTTP status remains visible. */ }
            throw new Error('HTTP ' + response.status + (detail ? ' · ' + detail : ''));
        }
        return {raw, etag: response.headers.get('ETag')};
    }
    const load = async (digest) => responseReply(await fetch(base + '/' + digest, {cache: 'no-store'}));
    function link(digest, role = '') {
        const query = new URLSearchParams({run: digest});
        if (role) query.set('artifact', role);
        return '#' + query.toString();
    }
    function setLink(state) {
        const target = link(state.digest, state.role);
        if (location.hash !== target) history.replaceState(null, '', target);
        $('retainedRunPermalink').href = target;
    }
    function activateRun() { runTab.click(); }

    function render(state) {
        const active = state.status !== 'EMPTY';
        document.body.classList.toggle('retained-run', active);
        $('retainedRunBanner').hidden = !active;
        $('retainedRunId').textContent = state.digest ? 'sha256:' + state.digest : 'Import wird geprüft';
        legacyTabs.forEach((tab) => { tab.disabled = active || originalDisabled.get(tab); });
        const ready = state.status === 'READY';
        $('downloadRetainedRun').disabled = !ready;
        $('retainedRunPermalink').hidden = !ready;
        $('retainedRunStatus').className = state.status === 'ERROR' ? 'run-error' : '';
        $('retainedRunStatus').textContent = state.status === 'LOADING' ? 'Gespeicherten Run laden …'
            : state.status === 'ERROR' ? 'Run konnte nicht geöffnet werden: ' + state.error
            : ready ? 'Gespeichert: ' + state.workspace.outcome.state + ' · ' + state.workspace.outcome.terminalReason : '';
        if (state.status === 'LOADING' || state.digest !== previousDigest) {
            if (comparison) comparison.clear();
        }
        previousDigest = state.digest;
        const detail = $('retainedRunDetail');
        detail.replaceChildren();
        if (!ready) {
            element('p', active ? 'Kein Run-Inhalt geladen.' : 'Öffne einen Run aus der Historie oder importiere sein kanonisches Manifest.', detail);
            renderHistory();
            return;
        }
        const run = state.workspace, input = run.input, plan = run.plan, outcome = run.outcome;
        $('retainedRunInput').value = run.runId;
        setLink(state);
        table(detail, [['Run-ID', run.runId], ['Quelle', input.displayText], ['Domäne', input.domainId],
            ['Annahmen', input.assumptions.length ? input.assumptions.join('\n') : 'Keine deklariert'],
            ['Informationsgrenze', plan.informationTrack], ['Strategie / Profil', plan.searchStrategyId + ' / ' + plan.searchProfileId],
            ['Ziel', plan.objectiveId], ['Seed', plan.deterministicSeed], ['Backend-Identitäten', plan.backendIdentities],
            ['Gespeicherter Zustand', outcome.state], ['Grund', outcome.terminalReason],
            ['Deklarierte Arbeit', outcome.state === 'CREATED' ? 'Noch nicht deklariert' : outcome.configuredWork],
            ['Verbrauchte Arbeit', outcome.consumedWork], ['Verbleibende Arbeit', outcome.state === 'CREATED' ? 'Unbekannt' : api.remainingWork(outcome.configuredWork, outcome.consumedWork)],
            ['Beziehung', run.relation], ['Eltern-Run', run.parentRunId || 'Keiner'],
            ['Geänderter Parameter', run.changedPlanParameter || 'Keiner']]);
        if (outcome.state === 'RUNNING') element('p', 'Gespeicherter Zwischenstand. Diese Ansicht erhält keine Live-Fortschrittsdaten; spätere Fortsetzungen besitzen eine eigene Run-ID.', detail);
        element('h4', 'Gebundene Artefakte', detail);
        element('p', 'Die folgenden Referenzen stammen aus diesem Manifest. Artefaktinhalte werden hier nicht geladen oder erneut geprüft.', detail);
        run.artifacts.forEach((artifact) => {
            const card = element('section', undefined, detail);
            card.className = 'run-artifact';
            card.dataset.artifactRole = artifact.role;
            card.setAttribute('aria-current', String(state.role === artifact.role));
            const select = element('button', artifact.role + ' · ' + artifact.status, card);
            select.type = 'button';
            select.addEventListener('click', () => {
                store.selectRole(artifact.role);
                detail.querySelector('[data-artifact-role="' + artifact.role + '"] button').focus();
            });
            element('p', artifact.status === 'AVAILABLE' ? 'Im Manifest als verfügbar gebunden; Inhalt in dieser Ansicht nicht geöffnet.' : artifact.detail, card);
            element('p', 'Schema: ' + artifact.artifactSchema, card);
            element('code', artifact.targetContentHash, card);
        });
        const identities = element('details', undefined, detail);
        element('summary', 'Inventare, Arbeitsbelege und Revisionen', identities);
        table(identities, [['Informationsgrenze', plan.informationBoundaryHash], ['Regelinventar', plan.ruleInventoryHash],
            ['Knowledge Packs', plan.knowledgePackSelectionHash], ['Strukturkatalog', plan.knownStructureCatalogHash],
            ['Budgetvertrag', plan.budgetHash], ['Kanonische Arbeit', outcome.canonicalWorkLedgerHash],
            ['Runtime-Diagnostik (separat)', outcome.runtimeDiagnosticsHash], ['Revisionen', run.revisions]]);
        const manifest = element('details', undefined, detail);
        element('summary', 'Vollständiges Original-Manifest', manifest);
        element('pre', state.raw, manifest);
        element('p', run.claimBoundary, detail);
        renderHistory();
    }

    const store = api.createStore(load, render);
    comparison = api.createStore(load, (state) => {
        const panel = $('retainedRunComparison'), detail = $('runComparisonDetail');
        panel.hidden = state.status === 'EMPTY';
        detail.replaceChildren();
        if (state.status === 'EMPTY') return;
        if (state.status !== 'READY') {
            element('p', state.status === 'ERROR' ? 'Vergleich fehlgeschlagen: ' + state.error : 'Vergleichs-Run laden …', detail);
            return;
        }
        const current = store.state();
        if (current.status !== 'READY') return;
        table(detail, [['Aktiver Run', current.workspace.runId], ['Vergleichs-Run', state.workspace.runId]]);
        const rows = [];
        function differences(left, right, path) {
            if (JSON.stringify(left) === JSON.stringify(right)) return;
            if (left && right && typeof left === 'object' && typeof right === 'object' && !Array.isArray(left) && !Array.isArray(right)) {
                [...new Set([...Object.keys(left), ...Object.keys(right)])].sort().forEach((key) => differences(left[key], right[key], path ? path + '.' + key : key));
            } else rows.push([path, 'Aktiv:\n' + display(left) + '\n\nVergleich:\n' + display(right)]);
        }
        differences(current.workspace, state.workspace, '');
        if (rows.length) table(detail, rows);
        else element('p', 'Die vollständigen Manifeste sind identisch.', detail);
        const close = element('button', 'Vergleich schließen', detail);
        close.type = 'button'; close.addEventListener('click', () => comparison.clear());
    });

    function renderHistory() {
        if (!pageData) return;
        const list = $('retainedRunsList'); list.replaceChildren();
        pageData.runs.forEach((run) => {
            const digest = run.runId.slice(7), item = element('li', undefined, list);
            const open = element('button', run.displayText, item);
            open.type = 'button'; open.className = 'run-open'; open.dataset.runDigest = digest;
            open.addEventListener('click', () => { activateRun(); store.open(digest); });
            element('p', run.state + ' · ' + run.reason, item);
            element('code', run.runId, item);
            const compare = element('button', 'Mit aktivem Run vergleichen', item);
            compare.type = 'button'; compare.dataset.compareDigest = digest;
            compare.disabled = store.state().status !== 'READY';
            compare.addEventListener('click', () => comparison.open(digest));
        });
        if (!pageData.runs.length) element('li', 'Keine gespeicherten Runs auf dieser Seite.', list);
    }
    async function loadHistory() {
        const token = ++historyGeneration;
        $('runHistoryStatus').textContent = 'Historie laden …';
        $('previousRetainedRuns').disabled = $('nextRetainedRuns').disabled = true;
        try {
            const reply = await responseReply(await fetch(base + '?offset=' + offset + '&limit=25', {cache: 'no-store'}));
            if (token !== historyGeneration) return;
            const data = api.parseExactJson(reply.raw);
            if (data.schema !== 'regelsuche.representation-discovery-run-index/v1' || !Array.isArray(data.runs)
                    || !Number.isInteger(data.total) || !Number.isInteger(data.offset) || data.offset !== offset
                    || data.limit !== 25 || data.runs.length > 25
                    || data.runs.some((run) => !/^sha256:[0-9a-f]{64}$/.test(run.runId))) throw new Error('Nicht unterstützte Run-Historie');
            pageData = data; historyLoaded = true; renderHistory();
            $('runHistoryStatus').textContent = data.total + ' gespeicherte Runs · ab Position ' + data.offset;
            $('previousRetainedRuns').disabled = offset === 0;
            $('nextRetainedRuns').disabled = offset + data.runs.length >= data.total;
        } catch (error) {
            if (token !== historyGeneration) return;
            pageData = null; $('retainedRunsList').replaceChildren();
            $('runHistoryStatus').textContent = 'Historie fehlgeschlagen: ' + error.message;
        }
    }
    runTab.addEventListener('click', () => { if (!historyLoaded) loadHistory(); });
    $('reloadRetainedRuns').addEventListener('click', loadHistory);
    $('previousRetainedRuns').addEventListener('click', () => { offset = Math.max(0, offset - 25); loadHistory(); });
    $('nextRetainedRuns').addEventListener('click', () => { offset += 25; loadHistory(); });
    $('openRetainedRun').addEventListener('submit', (event) => {
        event.preventDefault(); activateRun(); store.open($('retainedRunInput').value.trim().replace(/^sha256:/, ''));
    });
    $('importRetainedRun').addEventListener('change', async (event) => {
        const file = event.target.files[0]; event.target.value = '';
        if (!file) return;
        activateRun();
        await store.import(async () => {
            if (file.size > 2000000) throw new Error('Manifest überschreitet 2 MB');
            return responseReply(await fetch(base, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: await file.text()}));
        });
        if (store.state().status === 'READY') { offset = 0; loadHistory(); }
    });
    $('downloadRetainedRun').addEventListener('click', () => {
        const state = store.state(); if (state.status !== 'READY') return;
        const url = URL.createObjectURL(new Blob([state.raw], {type: 'application/json'}));
        const anchor = element('a'); anchor.href = url; anchor.download = 'run-' + state.digest + '.json';
        document.body.appendChild(anchor); anchor.click(); anchor.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
    $('leaveRetainedRun').addEventListener('click', () => {
        store.clear(); comparison.clear();
        history.replaceState(null, '', location.pathname + location.search);
        document.querySelector('[data-tab="workbench"]').click();
    });
    function restore() {
        const query = new URLSearchParams(location.hash.slice(1));
        if (!query.has('run')) {
            if (store.state().status !== 'EMPTY') store.clear();
            return;
        }
        activateRun(); store.open(query.get('run'), query.get('artifact') || '');
    }
    window.addEventListener('hashchange', restore);
    restore();
})();
