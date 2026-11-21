/* Operational telemetry is a separate record. Only a completed receipt links to the retained mathematical workspace. */
(() => {
    'use strict';
    const base = '/api/discovery-runs/operations';
    const $ = (id) => document.getElementById(id);
    const uuid = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
    const states = {RUNNING: 'Läuft', CANCELLING: 'Stopp angefordert', PAUSING: 'Pause angefordert', PAUSED: 'Pausiert', COMPLETED: 'Abgeschlossen',
        CANCELLED: 'Abgebrochen', INTERRUPTED: 'Unterbrochen', FAILED: 'Fehlgeschlagen'};

    function attach(openRun, responseReply) {
        let epoch = 0, timer, selected;
        const status = $('nativeSearchStatus'), progress = $('nativeSearchProgress');
        const start = $('startNativeSearch'), stop = $('stopNativeSearch'), pause = $('pauseNativeSearch'), result = $('openNativeResult');
        const historySelect = $('nativeOperationHistory');
        async function request(path, options) {
            return JSON.parse((await responseReply(await fetch(base + path, {cache: 'no-store', ...options}))).raw);
        }
        function begin() {
            clearTimeout(timer);
            result.hidden = true;
            start.disabled = true;
            stop.disabled = pause.disabled = true;
            status.dataset.state = '';
            status.textContent = 'Suchauftrag laden …';
            progress.textContent = '';
            $('nativeSearchMinima').textContent = '';
            selected = undefined;
            return ++epoch;
        }
        function fail(token, error) {
            if (epoch !== token) return;
            status.dataset.state = 'ERROR';
            status.textContent = 'Suchauftrag konnte nicht geladen werden · ' + error.message;
            start.disabled = false;
            stop.disabled = pause.disabled = true;
        }
        async function read(id, token) {
            try {
                present(await request('/' + id), id, token);
            } catch (error) { fail(token, error); }
        }
        function present(envelope, expected, token) {
            if (token !== epoch) return;
            const op = envelope.operation;
            if (envelope.schema !== 'regelsuche.native-search-operation/v1' || !uuid.test(op?.operationId)
                    || (expected && op.operationId !== expected) || !Object.hasOwn(states, op.status)) {
                throw new Error('Unpassendes Ausführungsprotokoll');
            }
            selected = op;
            const live = ['RUNNING', 'CANCELLING', 'PAUSING', 'PAUSED'].includes(op.status);
            start.disabled = live;
            stop.disabled = !live || op.status === 'CANCELLING';
            pause.disabled = !live || op.status === 'CANCELLING';
            pause.textContent = op.status === 'PAUSING' || op.status === 'PAUSED' ? 'Fortsetzen' : 'Pausieren';
            status.dataset.state = op.status;
            const summary = states[op.status] + ' · ' + op.source + (op.terminalReason ? ' · ' + op.terminalReason : '');
            if (status.textContent !== summary) status.textContent = summary;
            const work = op.progress;
            const pruned = Object.entries(work.pruned).map(([reason, count]) => reason + ' ' + count).join(', ');
            progress.textContent = 'Besucht ' + work.visited + ' von ' + op.budget.maxVisitedExpressions
                + ' · Restliches Zustandsbudget ' + Math.max(0, op.budget.maxVisitedExpressions - work.visited)
                + ' · Wartend ' + work.queued + ' · Generiert ' + work.generated + ' · Ereignisse ' + work.events
                + ' · Verworfen ' + (pruned || '0') + (live ? ' · Gesamtarbeit unbekannt' : '');
            const minima = [ ['Token', work.fewestTokens], ['AST-Knoten', work.fewestAstNodes] ]
                .filter(([, minimum]) => minimum).map(([label, minimum]) => label + ' ' + minimum.value + ' · ' + minimum.expression);
            $('nativeSearchMinima').textContent = minima.length ? 'Kleinste besuchte Formen · ' + minima.join(' / ') : '';
            result.hidden = op.status !== 'COMPLETED' || !/^sha256:[0-9a-f]{64}$/.test(op.workspaceRunId);
            const url = new URL(location.href);
            url.searchParams.set('operation', op.operationId);
            if (url.href !== location.href) history.replaceState(null, '', url);
            if (live) timer = setTimeout(() => read(op.operationId, token), 350);
        }
        $('nativeSearchForm').addEventListener('submit', async (event) => {
            event.preventDefault();
            const token = begin();
            try {
                present(await request('', {method: 'POST', headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({source: $('nativeSearchSource').value, profile: $('nativeSearchProfile').value, startPaused: $('nativeStartPaused').checked})}), '', token);
            } catch (error) { fail(token, error); }
        });
        async function control(action) {
            if (!selected) return;
            const id = selected.operationId, token = begin();
            try { present(await request('/' + id + '/' + action, {method: 'POST'}), id, token); }
            catch (error) { fail(token, error); }
        }
        stop.addEventListener('click', () => control('cancel'));
        pause.addEventListener('click', () => control(['PAUSED', 'PAUSING'].includes(selected?.status) ? 'resume' : 'pause'));
        result.addEventListener('click', () => {
            if (selected?.status === 'COMPLETED') openRun(selected.workspaceRunId);
        });
        $('nativeSearchHistory').addEventListener('toggle', async (event) => {
            if (!event.target.open) return;
            try {
                const ids = await request('');
                if (!Array.isArray(ids) || ids.length > 256 || !ids.every((id) => uuid.test(id))) throw new Error('Ungültiges Verzeichnis');
                historySelect.replaceChildren(new Option('Auftrag wählen', ''), ...ids.map((id) => new Option(id, id)));
                historySelect.value = selected?.operationId || '';
            } catch (error) { status.textContent = 'Protokolle konnten nicht geladen werden · ' + error.message; }
        });
        historySelect.addEventListener('change', () => {
            if (uuid.test(historySelect.value)) read(historySelect.value, begin());
        });
        const saved = new URLSearchParams(location.search).get('operation');
        if (uuid.test(saved)) {
            if (!new URLSearchParams(location.hash.slice(1)).has('run')) document.querySelector('[data-tab="runs"]').click();
            read(saved, begin());
        }
    }
    window.RegelsucheNativeSearch = Object.freeze({attach});
})();
