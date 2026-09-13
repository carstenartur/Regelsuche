(() => {
    'use strict';
    const element = id => document.getElementById(id);
    let domains = [];
    let evidenceBytes = '';
    const meanings = {
        CONFIRMED: 'Der unabhängige Evaluator der Domäne hat ein Zertifikat ausgestellt. Dessen angegebener Gültigkeitsbereich ist maßgeblich.',
        REFUTED: 'Ein Kandidat wurde widerlegt; der Lauf veröffentlicht kein bestätigtes Ergebnis.',
        BUDGET_EXHAUSTED: 'Das Suchbudget ist erschöpft. Daraus folgt keine mathematische Entscheidung.',
        INCONCLUSIVE: 'Die vorhandenen Prüfungen erlauben noch keine Entscheidung.',
        UNSUPPORTED: 'Die Domäne unterstützt diese Prüfung nicht.',
        INVALID_SEED: 'Die Startdaten verletzen die Invarianten der Domäne.'
    };
    const selection = () => domains[Number(element('domainChoice').value)];
    element('domainChoice').addEventListener('change', () => {
        const domain = selection();
        element('domainOrigin').textContent = domain ? `${domain.providerId} · Version ${domain.providerVersion}` : '';
    });
    element('domainForm').addEventListener('submit', async event => {
        event.preventDefault();
        const domain = selection();
        if (!domain) return;
        element('startDomain').disabled = true;
        element('domainResult').hidden = true;
        element('domainStatus').textContent = 'Der Suchlauf wird ausgeführt …';
        try {
            const response = await fetch('/api/discovery-domains/run', {
                method: 'POST', headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({providerId: domain.providerId, domainId: domain.domainId,
                    revision: domain.revision, campaignId: element('campaign').value,
                    seed: element('seed').value, budget: element('budget').value})
            });
            const text = await response.text();
            if (!response.ok) throw new Error(text);
            const result = JSON.parse(text);
            evidenceBytes = text;
            element('outcome').textContent = result.outcome;
            element('interpretation').textContent = meanings[result.outcome] || 'Unbekannter Ergebnisstatus.';
            element('certificate').textContent = result.certificate ? 'Ein Zertifikat ist in der Evidence enthalten.' : 'Kein Zertifikat ausgestellt.';
            element('counterexamples').replaceChildren();
            for (const attempt of result.candidateAttempts || []) {
                const witness = attempt.counterexampleWitness;
                if (!witness) continue;
                const item = document.createElement('li');
                item.textContent = witness;
                element('counterexamples').append(item);
            }
            element('evidence').textContent = JSON.stringify(result, null, 2);
            element('domainResult').hidden = false;
            element('domainStatus').textContent = 'Lauf abgeschlossen. Die vollständige Evidence kann gespeichert werden.';
        } catch (error) {
            evidenceBytes = '';
            element('domainStatus').textContent = `Lauf fehlgeschlagen: ${error.message}`;
        } finally { element('startDomain').disabled = domains.length === 0; }
    });
    element('downloadEvidence').addEventListener('click', () => {
        if (!evidenceBytes) return;
        const url = URL.createObjectURL(new Blob([evidenceBytes], {type: 'application/json'}));
        const link = document.createElement('a');
        link.href = url; link.download = 'discovery-evidence.json'; link.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
    fetch('/api/discovery-domains').then(async response => {
        if (!response.ok) throw new Error(await response.text());
        const data = await response.json();
        domains = data.domains;
        domains.forEach((domain, index) => {
            const option = document.createElement('option'); option.value = String(index);
            option.textContent = `${domain.domainId} · ${domain.revision}`;
            element('domainChoice').append(option);
        });
        element('domainChoice').dispatchEvent(new Event('change'));
        element('startDomain').disabled = domains.length === 0;
        element('domainStatus').textContent = domains.length
            ? 'Domäne auswählen und passende Startdaten eingeben.'
            : 'Für diese Workbench wurden noch keine eigenen Domänen aktiviert.';
    }).catch(error => { element('domainStatus').textContent = `Domänen konnten nicht geladen werden: ${error.message}`; });
})();

// An immutable source-export view has its own schema; it does not become an R1 run.
(() => {
    'use strict';
    const base = '/api/discovery-domains/exports';
    const schema = 'regelsuche.domain-export-workspace/v1';
    const fileNames = ['domain.json', 'evidence.json', 'lifecycle-handoff.json', 'export-manifest.json'];
    const hashPattern = /^sha256:[0-9a-f]{64}$/;
    const element = id => document.getElementById(id);
    const detail = element('retainedDomainExportDetail');
    const status = element('retainedDomainExportStatus');
    const replayStatus = element('retainedDomainReplayStatus');
    let selected = null, selectionEpoch = 0, listEpoch = 0, offset = 0;

    function node(tag, text) {
        const result = document.createElement(tag);
        if (text !== undefined) result.textContent = String(text);
        return result;
    }
    function table(headings, rows, resourceRows = false) {
        const result = node('table'), head = node('thead'), heading = node('tr'), body = node('tbody');
        for (const title of headings) { const cell = node('th', title); cell.scope = 'col'; heading.append(cell); }
        head.append(heading);
        for (const values of rows) {
            const row = node('tr');
            if (resourceRows) row.dataset.resource = values[0];
            values.forEach((value, index) => {
                const cell = node(index === 0 ? 'th' : 'td', value);
                if (index === 0) cell.scope = 'row';
                row.append(cell);
            });
            body.append(row);
        }
        result.append(head, body);
        if (resourceRows) result.className = 'domain-resource-table';
        return result;
    }
    function disclosure(title, value) {
        const result = node('details');
        result.append(node('summary', title), node('pre', JSON.stringify(value, null, 2)));
        return result;
    }
    async function readResponse(response) {
        const text = await response.text();
        let value;
        try { value = JSON.parse(text); } catch { throw new Error(`Ungültige Serverantwort (${response.status}).`); }
        if (!response.ok) throw new Error(value.message || value.code || `HTTP ${response.status}`);
        return value;
    }
    function checkView(view, response, requestedId) {
        if (view.schema !== schema || !hashPattern.test(view.runId) || !hashPattern.test(view.contentHash)
            || (requestedId && view.runId !== requestedId)
            || response.headers.get('X-Regelsuche-Run-Id') !== view.runId
            || response.headers.get('ETag') !== `"${view.contentHash.slice(7)}"`
            || view.manifest?.contentHash !== view.runId
            || view.verification?.manifestContentHash !== view.runId
            || view.evidence?.contentHash !== view.manifest?.discoveryEvidenceHash
            || view.evidence?.seed?.contentHash !== view.inputHash
            || view.evidence?.domain?.domainId !== view.domainId
            || view.evidence?.domain?.revision !== view.domainRevision
            || view.lifecycleHandoff?.contentHash !== view.manifest?.lifecycleHandoffHash) {
            throw new Error('Die Antwort gehört nicht zur angeforderten Exportidentität.');
        }
        return view;
    }
    function beginSelection() {
        const ticket = ++selectionEpoch;
        selected = null; detail.hidden = true; detail.replaceChildren(); delete detail.dataset.runId;
        element('retainedDomainReplay').hidden = true;
        element('replayDomainExport').disabled = true;
        replayStatus.textContent = ''; delete replayStatus.dataset.status;
        markSelection();
        return ticket;
    }
    function markSelection() {
        for (const button of element('retainedDomainExportList').querySelectorAll('button')) {
            button.setAttribute('aria-current', String(button.dataset.domainExport === selected?.runId));
        }
    }
    function show(view) {
        selected = view;
        const source = view.evidence;
        detail.append(node('h2', `${source.campaignId} · ${source.outcome}`));
        detail.append(node('p', `${view.domainId} · ${view.domainRevision}`));
        const identity = node('dl');
        for (const [label, value] of [
            ['Ursprüngliche Exportidentität', view.runId], ['Startdaten', source.seed.payload],
            ['Quellenangabe', source.seed.sourceReference], ['Eingabe-Hash', view.inputHash]
        ]) identity.append(node('dt', label), node('dd', value));
        detail.append(identity, node('h3', 'Beobachtete Folge und zurückgehaltene Prüfdaten'));
        detail.append(table(['Gespeichertes Feld', 'Wert'], source.domainEvidence.properties.map(item => [item.key, item.value])));
        detail.append(node('h3', 'Gespeicherte Arbeit je Dimension'));
        detail.append(table(['Ressource', 'Budget', 'Ausgeführt', 'Übersprungen', 'Verbleibend'],
            source.resources.map(line => [line.resource, line.configured, line.executed, line.skipped, line.remaining]), true));
        detail.append(node('p', 'Jede Zeile behält ihre eigene Einheit. Budget = ausgeführt + übersprungen + verbleibend.'));
        detail.append(node('h3', 'Kandidaten und Validierung'));
        if (source.certificate) {
            detail.append(node('p', `${source.certificate.kind} · ${source.certificate.format}`), node('pre', source.certificate.rendered));
        } else detail.append(node('p', 'Die Quelle enthält kein bestätigtes Zertifikat.'));
        const attempts = node('ul');
        for (const attempt of source.candidateAttempts) {
            const item = node('li');
            item.append(node('p', `${attempt.disposition} · ${attempt.evaluationStatus}: ${attempt.evaluationSummary}`));
            if (attempt.counterexampleWitness) item.append(node('pre', attempt.counterexampleWitness));
            item.append(disclosure('Gespeicherter Kandidatenversuch', attempt)); attempts.append(item);
        }
        if (!source.candidateAttempts.length) attempts.append(node('li', 'Keine Kandidatenversuche gespeichert.'));
        detail.append(attempts, disclosure('Suchzustände und Übergänge der Quelle', {states: source.states, transitions: source.transitions}));
        detail.append(node('h3', 'Verfügbare Artefakte'));
        detail.append(table(['Rolle', 'Status', 'Quellwurzel / Grund'], view.artifacts.map(item =>
            [item.role, item.status, item.targetContentHash || item.detail])));
        detail.append(node('p', 'AVAILABLE verweist auf die gespeicherte Domain-Evidence. Fehlende nachgelagerte Nachweise bleiben NOT_PRODUCED.'));
        detail.append(table(['Status der ursprünglichen Evidence', 'Wert'], [
            ['Proof', source.proofStatus], ['Externe Neuheit', source.externalNoveltyStatus],
            ['Promotion', source.promotionStatus], ['Public Evidence', source.publicEvidenceStatus]
        ]));
        detail.append(node('p', 'Die Identitätsprüfung bestätigt die Quellenbindung. Sie stellt keinen formalen Beweis und keine Bewertung externer Neuheit aus.'));
        detail.append(node('h3', 'Originaldateien herunterladen'));
        const downloads = node('div'); downloads.className = 'domain-original-files';
        for (const name of fileNames) {
            const link = node('a', name); link.href = `${base}/${view.runId.slice(7)}/files/${name}`;
            link.download = name; link.dataset.originalFile = name; downloads.append(link);
        }
        detail.append(downloads, disclosure('Manifest und Prüfung der Originalbytes', {manifest: view.manifest, verification: view.verification}),
            disclosure('Vollständige Quellenansicht', view));
        detail.hidden = false; detail.dataset.runId = view.runId;
        element('retainedDomainReplay').hidden = false;
        element('replayDomainExport').disabled = !view.replaySupported;
        replayStatus.textContent = view.replaySupported ? 'Noch kein Replay für diese Auswahl ausgeführt.'
            : 'Die Quelle überschreitet das begrenzte Replay-Budget. Die gespeicherten Daten bleiben lesbar.';
        history.replaceState(null, '', `#export=${view.runId.slice(7)}`);
        status.textContent = 'Originalexport geladen. Die gespeicherte Quellenidentität bleibt unverändert.';
        markSelection();
    }
    async function load(runId) {
        const ticket = beginSelection();
        if (!hashPattern.test(runId)) { status.textContent = 'Ungültige Exportidentität im Link.'; return; }
        status.textContent = 'Gespeicherter Originalexport wird geprüft …';
        try {
            const response = await fetch(`${base}/${runId.slice(7)}`);
            const view = checkView(await readResponse(response), response, runId);
            if (ticket === selectionEpoch) show(view);
        } catch (error) { if (ticket === selectionEpoch) status.textContent = `Export konnte nicht geladen werden: ${error.message}`; }
    }
    async function refreshList() {
        const ticket = ++listEpoch, requestedOffset = offset;
        element('domainExportPage').textContent = 'Liste wird geladen …';
        try {
            const response = await fetch(`${base}?offset=${requestedOffset}&limit=25`);
            const index = await readResponse(response);
            if (ticket !== listEpoch) return;
            if (index.schema !== 'regelsuche.domain-export-workspace-index/v1' || index.offset !== requestedOffset
                || index.limit !== 25 || !Number.isInteger(index.total) || index.total < 0 || index.total > 256
                || !Array.isArray(index.exports) || index.exports.length > 25) throw new Error('Ungültige Exportliste.');
            const list = element('retainedDomainExportList'); list.replaceChildren();
            for (const entry of index.exports) {
                if (!hashPattern.test(entry.runId)) throw new Error('Ungültige Quellenidentität in der Liste.');
                const button = node('button', `${entry.campaignId} · ${entry.outcome} · ${entry.runId.slice(7, 19)}`);
                button.type = 'button'; button.dataset.domainExport = entry.runId; button.title = entry.runId;
                button.addEventListener('click', () => load(entry.runId));
                const item = node('li'); item.append(button); list.append(item);
            }
            element('domainExportPage').textContent = index.total ? `${requestedOffset + 1}–${requestedOffset + index.exports.length} von ${index.total}` : 'Noch keine Exporte gespeichert.';
            element('previousDomainExports').disabled = requestedOffset === 0;
            element('nextDomainExports').disabled = requestedOffset + 25 >= index.total;
            markSelection();
        } catch (error) { if (ticket === listEpoch) element('domainExportPage').textContent = `Liste nicht verfügbar: ${error.message}`; }
    }
    element('importDomainExport').addEventListener('change', async event => {
        const files = Array.from(event.target.files || []);
        if (!files.length) return;
        const ticket = beginSelection();
        status.textContent = 'Originaldateien werden importiert …';
        try {
            if (files.length !== 4 || new Set(files.map(file => file.name)).size !== 4
                || files.some(file => !fileNames.includes(file.name))) throw new Error('Bitte genau die vier genannten Originaldateien auswählen.');
            if (files.reduce((total, file) => total + file.size, 0) > 524288) throw new Error('Der Originalexport überschreitet 512 KiB.');
            const encoded = {};
            for (const file of files) {
                const bytes = new Uint8Array(await file.arrayBuffer());
                let binary = '';
                for (let at = 0; at < bytes.length; at += 8192) binary += String.fromCharCode(...bytes.subarray(at, at + 8192));
                encoded[file.name] = btoa(binary);
            }
            const response = await fetch(base, {method: 'POST', headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({schema: 'regelsuche.domain-export-upload/v1', files: encoded})});
            const view = checkView(await readResponse(response), response);
            if (ticket === selectionEpoch) { show(view); offset = 0; await refreshList(); }
        } catch (error) { if (ticket === selectionEpoch) status.textContent = `Import fehlgeschlagen: ${error.message}`; }
        finally { if (ticket === selectionEpoch) event.target.value = ''; }
    });
    element('replayDomainExport').addEventListener('click', async () => {
        const view = selected, ticket = selectionEpoch;
        if (!view?.replaySupported) return;
        element('replayDomainExport').disabled = true;
        replayStatus.textContent = 'Quelle wird mit ihren gespeicherten Budgets erneut ausgeführt …';
        delete replayStatus.dataset.status;
        try {
            const response = await fetch(`${base}/${view.runId.slice(7)}/replay`, {method: 'POST', headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({expectedWorkspaceHash: view.contentHash, expectedEvidenceHash: view.evidence.contentHash})});
            const result = await readResponse(response);
            if (ticket !== selectionEpoch) return;
            if (result.schema !== 'regelsuche.domain-export-replay/v1' || result.runId !== view.runId
                || response.headers.get('X-Regelsuche-Run-Id') !== view.runId || result.workspaceContentHash !== view.contentHash
                || result.sourceEvidenceHash !== view.evidence.contentHash || result.status !== 'IDENTICAL_CANONICAL_EVIDENCE'
                || JSON.stringify(result.evidence) !== JSON.stringify(view.evidence)) throw new Error('Replay gehört nicht vollständig zur ausgewählten Quelle.');
            replayStatus.textContent = 'Vollständige gespeicherte Evidence reproduziert. Die historische Arbeit wurde nicht verändert.';
            replayStatus.dataset.status = result.status;
        } catch (error) { if (ticket === selectionEpoch) replayStatus.textContent = `Replay fehlgeschlagen: ${error.message}`; }
        finally { if (ticket === selectionEpoch) element('replayDomainExport').disabled = false; }
    });
    element('refreshDomainExports').addEventListener('click', refreshList);
    element('previousDomainExports').addEventListener('click', () => { offset = Math.max(0, offset - 25); refreshList(); });
    element('nextDomainExports').addEventListener('click', () => { offset += 25; refreshList(); });
    function restoreLink() {
        const match = /^#export=([0-9a-f]{64})$/.exec(location.hash);
        if (match) load(`sha256:${match[1]}`);
        else if (location.hash) { beginSelection(); status.textContent = 'Ungültiger Exportlink.'; }
        else { beginSelection(); status.textContent = 'Einen gespeicherten Export auswählen oder vier Originaldateien importieren.'; }
    }
    window.addEventListener('hashchange', restoreLink);
    refreshList(); restoreLink();
})();
