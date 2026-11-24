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
    const validationPanel = element('retainedDomainValidation');
    const validateButton = element('validateDomainExport');
    const validationStatus = element('retainedDomainValidationStatus');
    const receiptLink = element('downloadDomainValidationReceipt');
    const proofPanel = element('retainedDomainModelProof');
    const proofButton = element('proveDomainExportModel');
    const proofStatus = element('retainedDomainModelProofStatus');
    const proofResult = element('domainModelProofResult');
    const proofReceiptLink = element('downloadDomainModelProofReceipt');
    const proofMessages = {
        PROVED_MODEL: 'Modellgesetz bewiesen: Die Rekurrenz gilt für das unten definierte affine Modell.',
        INCONCLUSIVE: 'Die begrenzte Modellprüfung liefert kein abschließendes Ergebnis.',
        UNSUPPORTED: 'Dieses Modell wird von der begrenzten Modellprüfung nicht unterstützt.',
        NOT_EVALUATED_NO_SELECTED_CANDIDATE: 'Die Quelle enthält keinen ausgewählten Kandidaten für eine Modellprüfung.',
        FINITE_CHECK_NOT_CONFIRMED: 'Die unabhängige Prüfung der endlichen Daten hat den Kandidaten nicht bestätigt. Es wurde kein Modellgesetz bewiesen.',
        REPLAY_MISMATCH: 'Die erneute Ausführung reproduziert die gebundene Quelle nicht vollständig. Es wurde kein Modellgesetz bewiesen.',
        TECHNICAL_FAILURE: 'Ein technischer Fehler hat die Modellprüfung abgebrochen.'
    };
    const validationMessages = {
        CONFIRMED_FINITE_DATA: 'Der Kandidat stimmt mit den gespeicherten endlichen Daten überein.',
        REFUTED_FINITE_DATA: 'Ein gespeichertes Folgenglied widerlegt den Kandidaten.',
        INVALID_WITNESS: 'Der gespeicherte Zeuge ist ungültig.',
        INCONCLUSIVE: 'Die begrenzte Prüfung liefert kein abschließendes Ergebnis.',
        NOT_EVALUATED_NO_SELECTED_CANDIDATE: 'Die Quelle enthält keinen ausgewählten Kandidaten zur Prüfung.',
        UNSUPPORTED: 'Für diese Daten gibt es keine unterstützte unabhängige Prüfung.',
        REPLAY_MISMATCH: 'Die erneute Ausführung reproduziert die gebundene Quelle nicht vollständig.',
        TECHNICAL_FAILURE: 'Ein technischer Fehler hat die Prüfung abgebrochen.'
    };
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
    function parseResponse(response, text) {
        let value;
        try { value = JSON.parse(text); } catch { throw new Error(`Ungültige Serverantwort (${response.status}).`); }
        if (!response.ok) throw new Error(value.message || value.code || `HTTP ${response.status}`);
        return value;
    }
    async function readResponse(response) {
        return parseResponse(response, await response.text());
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
    function clearValidation(message) {
        receiptLink.hidden = true;
        receiptLink.removeAttribute('href');
        validationStatus.textContent = message;
        delete validationStatus.dataset.status;
    }
    function clearProof(message) {
        proofReceiptLink.hidden = true;
        proofReceiptLink.removeAttribute('href');
        proofResult.hidden = true;
        proofResult.replaceChildren();
        proofStatus.textContent = message;
        delete proofStatus.dataset.status;
    }
    function beginSelection() {
        const ticket = ++selectionEpoch;
        selected = null; detail.hidden = true; detail.replaceChildren(); delete detail.dataset.runId;
        element('retainedDomainReplay').hidden = true;
        element('replayDomainExport').disabled = true;
        replayStatus.textContent = ''; delete replayStatus.dataset.status;
        element('domainAssessment').hidden = true;
        element('domainAssessmentResult').replaceChildren();
        element('domainAssessmentStatus').textContent = '';
        delete element('domainAssessmentStatus').dataset.status;
        element('downloadDomainAssessment').hidden = true;
        element('downloadDomainAssessment').removeAttribute('href');
        validationPanel.hidden = true;
        validateButton.disabled = true;
        clearValidation('');
        proofPanel.hidden = true;
        proofButton.disabled = true;
        clearProof('');
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
        const scopedLogic = view.domainId === 'scoped-logic' && view.domainRevision === 'v1';
        const sequence = view.domainRevision === 'v1' &&
            ['integer-sequence-finite-difference', 'integer-sequence-linear-recurrence'].includes(view.domainId);
        detail.append(node('h2', `${source.campaignId} · ${source.outcome}`));
        detail.append(node('p', `${view.domainId} · ${view.domainRevision}`));
        const identity = node('dl');
        for (const [label, value] of [
            ['Ursprüngliche Exportidentität', view.runId], ['Startdaten', source.seed.payload],
            ['Quellenangabe', source.seed.sourceReference], ['Eingabe-Hash', view.inputHash]
        ]) identity.append(node('dt', label), node('dd', value));
        detail.append(identity, node('h3', sequence
            ? 'Beobachtete Folge und zurückgehaltene Prüfdaten' : scopedLogic
                ? 'Logischer Kontext und gespeicherte Belegangaben' : 'Formel und Umschreibungsnachweis'));
        if (scopedLogic) detail.append(node('p', 'Kontext, Ergebnisstatus und Zertifikate sind gespeicherte Angaben der Quelle. '
            + 'Der Import prüft ihre Quellenbindung und führt keinen Beweisprüfer aus.'));
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
            if (scopedLogic) {
                const button = node('button', `Kandidat ${attempt.sequence} erneut prüfen`);
                button.type = 'button'; button.dataset.scopedCandidate = attempt.candidateHash;
                const message = node('p'); message.setAttribute('role', 'status');
                const result = node('div');
                button.addEventListener('click', () => recheckScopedCandidate(view, attempt.candidateHash, button, message, result));
                item.append(button, message, result);
            }
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
            : scopedLogic ? 'Eine vollständige Wiederholung des historischen Laufs ist nicht verfügbar. Der Beweisprüfer kann gespeicherte Kandidaten einzeln neu prüfen.'
            : 'Die Quelle überschreitet das begrenzte Replay-Budget. Die gespeicherten Daten bleiben lesbar.';
        element('domainAssessment').hidden = scopedLogic;
        element('assessDomainExport').disabled = scopedLogic;
        validationPanel.hidden = !sequence;
        validateButton.disabled = !sequence;
        proofPanel.hidden = !sequence;
        proofButton.disabled = !sequence;
        proofStatus.textContent = 'Noch kein Modellgesetz für diese Auswahl geprüft.';
        history.replaceState(null, '', `#export=${view.runId.slice(7)}`);
        status.textContent = 'Originalexport geladen. Die gespeicherte Quellenidentität bleibt unverändert.';
        markSelection();
    }
    async function recheckScopedCandidate(view, candidateHash, button, message, output) {
        if (selected !== view || button.disabled || !hashPattern.test(candidateHash)) return;
        button.disabled = true;
        output.replaceChildren(); delete message.dataset.status;
        message.textContent = 'Gespeicherter Kandidat wird rekonstruiert und geprüft …';
        try {
            const response = await fetch(`${base}/${view.runId.slice(7)}/scoped-check`, {method: 'POST', redirect: 'error',
                headers: {'Content-Type': 'application/json'}, body: JSON.stringify({expectedWorkspaceHash: view.contentHash,
                    expectedEvidenceHash: view.evidence.contentHash, candidateHash})});
            const report = await readResponse(response), source = report.source;
            if (selected !== view) return;
            if (report.schema !== 'regelsuche.scoped-workspace-candidate-check/v1' || source?.runId !== view.runId
                || source.workspaceHash !== view.contentHash || source.evidenceHash !== view.evidence.contentHash
                || source.seedHash !== view.inputHash || source.candidateHash !== candidateHash
                || !view.evidence.states.some(state => state.candidateReady && state.stateHash === source.stateHash)
                || response.headers.get('X-Regelsuche-Run-Id') !== view.runId) throw new Error('Fremde Prüfquelle.');
            const {counterexample, proof, retainedWitness} = report;
            const counterexamples = {
                FOUND: 'Gegenbeispiel gefunden.', NONE_FOUND: 'Bei diesen Versuchen wurde kein Gegenbeispiel gefunden.',
                INCONCLUSIVE: 'Die Interpretationen liefern keine Entscheidung.', UNSUPPORTED: 'Die Gegenbeispielprüfung unterstützt diese Aussage nicht.'
            };
            const proofs = {
                CONFIRMED: 'Aussage im Lean-Kernel geprüft und in einem zweiten Projekt rekonstruiert.',
                REFUTED: 'Die formale Prüfung hat die Aussage widerlegt.',
                INCONCLUSIVE: 'Die Lean-Prüfung hat keinen bestätigten Beleg geliefert.',
                UNSUPPORTED: 'Die Lean-Prüfung unterstützt diese Aussage nicht.', NOT_RUN: 'Die formale Prüfung wurde nicht ausgeführt.'
            };
            if (!hashPattern.test(report.obligationHash) || !hashPattern.test(report.proposalHash)
                || !Object.hasOwn(counterexamples, counterexample.status) || !Number.isInteger(counterexample.attempts)
                || counterexample.attempts < 0 || counterexample.attempts > 256 || !Object.hasOwn(proofs, proof.status)
                || (proof.status === 'CONFIRMED' && (!proof.backendConfigured || !hashPattern.test(proof.kernelHash)
                    || !hashPattern.test(proof.rebuildHash)))) throw new Error('Ungültiger Prüfbeleg.');
            const proofMessage = proof.backendConfigured ? proofs[proof.status] : 'Für eine formale Prüfung sind zwei Lean-Projekte einzurichten.';
            message.textContent = proofMessage;
            message.dataset.status = proof.status;
            output.append(node('p', `${counterexamples[counterexample.status]} Versuche: ${counterexample.attempts}.`));
            if (counterexample.status === 'FOUND') output.append(node('pre', counterexample.witness));
            if (retainedWitness === 'DIFFERS') output.append(node('p', 'Der gespeicherte Zeuge konnte nicht reproduziert werden.'));
            output.append(disclosure('Neuer Prüfbeleg mit Quellenbindung', report));
        } catch (error) {
            if (selected === view) message.textContent = `Kandidatenprüfung fehlgeschlagen: ${error.message}`;
        } finally {
            if (selected === view) button.disabled = false;
        }
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
    element('assessDomainExport').addEventListener('click', async () => {
        const view = selected, ticket = selectionEpoch;
        if (!view || (view.domainId === 'scoped-logic' && view.domainRevision === 'v1')) return;
        const button = element('assessDomainExport'), message = element('domainAssessmentStatus');
        const result = element('domainAssessmentResult'), link = element('downloadDomainAssessment');
        const path = `${base}/${view.runId.slice(7)}/assessment`;
        button.disabled = true;
        result.replaceChildren(); link.hidden = true; link.removeAttribute('href');
        delete message.dataset.status;
        message.textContent = 'Quelllauf, Kandidat und Vergleichslauf werden geprüft …';
        try {
            const response = await fetch(path, {method: 'POST', redirect: 'error', headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({expectedWorkspaceHash: view.contentHash, expectedEvidenceHash: view.evidence.contentHash})});
            const original = await response.text();
            if (ticket !== selectionEpoch) return;
            const report = parseResponse(response, original), source = report.source;
            if (report.schema !== 'regelsuche.domain-snapshot-assessment/v1' || !hashPattern.test(report.contentHash)
                    || source?.runId !== view.runId || source.workspaceHash !== view.contentHash
                    || source.evidenceHash !== view.evidence.contentHash || source.handoffHash !== view.manifest.lifecycleHandoffHash
                    || response.headers.get('X-Regelsuche-Run-Id') !== view.runId) throw new Error('Fremde Prüfquelle.');
            const retainedPath = `${path}/${report.contentHash.slice(7)}`;
            if (response.headers.get('ETag') !== `"${report.contentHash.slice(7)}"`
                    || response.headers.get('Location') !== retainedPath) throw new Error('Fremder Prüfbeleg.');
            const retained = await fetch(retainedPath, {redirect: 'error', cache: 'no-store'});
            const bytes = await retained.text();
            if (ticket !== selectionEpoch) return;
            if (!retained.ok || original !== bytes || retained.headers.get('ETag') !== response.headers.get('ETag')
                    || retained.headers.get('X-Regelsuche-Run-Id') !== view.runId) throw new Error('Der gespeicherte Beleg weicht ab.');
            result.append(table(['Prüfung', 'Ergebnis'], [
                ['Quelllauf', report.replayStatus], ['Validierung', report.validation.status], ['Gegenbeispiele', report.counterexamples.status],
                ['Beweis', report.proof.status], ['Lebenszyklus', report.lifecycle.status], ['Externe Neuheit', report.externalNovelty.status], ['Promotion', report.promotion.status],
                ['Öffentlicher Nachweis', report.publicEvidence.status], ['Vergleich mit Suchtiefe null', report.ablation.control || report.ablation.status]
            ]));
            if (report.ablation.resources) {
                result.append(table(['Ressource', 'Quelllauf ausgeführt', 'Ohne Suchschritte ausgeführt'],
                    report.resources.map(row => [row.resource, row.executed, report.ablation.resources.find(item => item.resource === row.resource).executed])));
            }
            result.append(disclosure('Annahmen, Zertifikate und Gegenbeispiele', {proof: report.proof, counterexamples: report.counterexamples}));
            if (report.externalNovelty.subject) {
                window.RegelsucheCandidateDossier.renderLiterature(result, view.runId, report.externalNovelty.subject.candidateId);
            }
            message.textContent = 'Prüfbeleg an der gespeicherten Quelle bestätigt.';
            message.dataset.status = report.proof.status;
            link.href = retainedPath; link.hidden = false;
        } catch (error) {
            if (ticket === selectionEpoch) message.textContent = `Prüfung fehlgeschlagen: ${error.message}`;
        } finally {
            if (ticket === selectionEpoch) button.disabled = view.domainId === 'scoped-logic' && view.domainRevision === 'v1';
        }
    });
    validateButton.addEventListener('click', async () => {
        const view = selected, ticket = selectionEpoch;
        if (!view || (view.domainId === 'scoped-logic' && view.domainRevision === 'v1')) return;
        const actionPath = `${base}/${view.runId.slice(7)}/validate`;
        validateButton.disabled = true;
        clearValidation('Gespeicherte Folgenglieder werden geprüft …');
        try {
            const response = await fetch(actionPath, {method: 'POST', redirect: 'error', headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({expectedWorkspaceHash: view.contentHash, expectedEvidenceHash: view.evidence.contentHash})});
            const receipt = await readResponse(response);
            if (ticket !== selectionEpoch) return;
            const source = receipt.source;
            if (receipt.schema !== 'regelsuche.domain-downstream-validation/v1' || !hashPattern.test(receipt.contentHash)
                || receipt.consumerContract !== 'regelsuche.builtin-sequence-export-validation/v1'
                || !Object.hasOwn(validationMessages, receipt.status) || source?.runId !== view.runId
                || source.workspaceHash !== view.contentHash || source.evidenceHash !== view.evidence.contentHash
                || source.domainId !== view.domainId || source.domainRevision !== view.domainRevision
                || source.manifestHash !== view.manifest.contentHash || source.manifestByteHash !== view.verification.manifestByteHash
                || source.descriptorHash !== view.manifest.domainDescriptorHash || source.seedHash !== view.inputHash
                || source.handoffHash !== view.manifest.lifecycleHandoffHash
                || source.verificationHash !== view.verification.contentHash || receipt.sourceOutcome !== view.evidence.outcome
                || response.headers.get('X-Regelsuche-Run-Id') !== view.runId
                || ['proofStatus', 'externalNoveltyStatus', 'promotionStatus', 'publicEvidenceStatus']
                    .some(field => receipt[field] !== 'NOT_EVALUATED') || receipt.universalProofStatus !== 'NOT_PRODUCED') {
                throw new Error('Der Prüfbeleg gehört nicht zur ausgewählten Quelle.');
            }
            const digest = receipt.contentHash.slice(7);
            const receiptPath = `${actionPath}/${digest}`;
            if (response.headers.get('ETag') !== `"${digest}"` || response.headers.get('Location') !== receiptPath) {
                throw new Error('Der Download gehört nicht zur Identität des Prüfbelegs.');
            }
            const index = receipt.check?.firstCounterexampleIndex;
            const counterexample = receipt.counterexampleStatus === 'FOUND' && Number.isSafeInteger(index) && index >= 0
                ? ` Erstes Gegenbeispiel an Index ${index} (Startindex 0).` : '';
            validationStatus.textContent = validationMessages[receipt.status] + counterexample;
            validationStatus.dataset.status = receipt.status;
            receiptLink.href = receiptPath;
            receiptLink.hidden = false;
        } catch (error) {
            if (ticket === selectionEpoch) validationStatus.textContent = `Prüfung fehlgeschlagen: ${error.message}`;
        } finally {
            if (ticket === selectionEpoch) validateButton.disabled = false;
        }
    });
    function matchesProofSource(source, view) {
        return source?.runId === view.runId && source.workspaceHash === view.contentHash
            && source.evidenceHash === view.evidence.contentHash && source.domainId === view.domainId
            && source.domainRevision === view.domainRevision && source.manifestHash === view.manifest.contentHash
            && source.manifestByteHash === view.verification.manifestByteHash
            && source.descriptorHash === view.manifest.domainDescriptorHash && source.seedHash === view.inputHash
            && source.handoffHash === view.manifest.lifecycleHandoffHash && source.verificationHash === view.verification.contentHash
            && source.selectedCandidateHash === (view.evidence.selectedCandidateHash || null)
            && source.certificateObjectHash === (view.evidence.certificate?.certificateObjectHash ?? null)
            && source.renderedCertificateHash === (view.evidence.certificate?.contentHash ?? null)
            && JSON.stringify(source.artifactBytes) === JSON.stringify(view.manifest.artifacts);
    }
    function checkModelProof(receipt, response, view, actionPath) {
        if (receipt.schema !== 'regelsuche.domain-model-proof/v1'
            || receipt.consumerContract !== 'regelsuche.builtin-sequence-affine-model-proof/v1'
            || !hashPattern.test(receipt.contentHash) || !Object.hasOwn(proofMessages, receipt.status)
            || !matchesProofSource(receipt.source, view) || receipt.sourceOutcome !== view.evidence.outcome
            || response.headers.get('X-Regelsuche-Run-Id') !== view.runId
            || receipt.originalSequenceProofStatus !== 'NOT_ESTABLISHED'
            || ['formalProofStatus', 'externalNoveltyStatus', 'promotionStatus', 'publicEvidenceStatus']
                .some(field => receipt[field] !== 'NOT_EVALUATED')) {
            throw new Error('Der Modellbeleg gehört nicht zur ausgewählten Quelle oder überschreitet seinen Gültigkeitsbereich.');
        }
        const digest = receipt.contentHash.slice(7), receiptPath = `${actionPath}/${digest}`;
        if (response.headers.get('ETag') !== `"${digest}"` || response.headers.get('Location') !== receiptPath) {
            throw new Error('Der Download gehört nicht zur Identität des Modellbelegs.');
        }
        const model = receipt.model, proof = receipt.proof;
        const rational = value => typeof value === 'string' && /^-?(0|[1-9][0-9]*)(\/[1-9][0-9]*)?$/.test(value);
        if (model) {
            const size = model.dimension, vector = values => Array.isArray(values) && values.length === size && values.every(rational);
            if (model.schema !== 'regelsuche.sequence-affine-model/v1' || !hashPattern.test(model.contentHash)
                || !Number.isInteger(size) || size < 1 || size > 3 || model.initialIndex !== 0 || model.observation !== 'x0'
                || !Array.isArray(model.coordinates) || model.coordinates.length !== size
                || model.coordinates.some((name, index) => name !== `x${index}`)
                || !Array.isArray(model.linear) || model.linear.length !== size || !model.linear.every(vector)
                || !vector(model.offset) || !vector(model.initialState)
                || !Array.isArray(model.assumptions) || !model.assumptions.length || !model.assumptions.every(value => typeof value === 'string')) {
                throw new Error('Der Modellbeleg enthält kein unterstütztes affines Modell.');
            }
        }
        if (receipt.status === 'PROVED_MODEL') {
            if (!model || !proof || proof.checkerContract !== 'regelsuche.recurrence-observation-krylov-model-proof/v1'
                || proof.scope !== 'EXPLICIT_AFFINE_MODEL' || proof.modelHash !== model.contentHash
                || !hashPattern.test(proof.contentHash) || !Number.isInteger(proof.recurrenceOrder)
                || proof.recurrenceOrder < 1 || proof.recurrenceOrder > model.dimension + 1
                || !Array.isArray(proof.coefficientsOldestFirst) || proof.coefficientsOldestFirst.length !== proof.recurrenceOrder
                || !proof.coefficientsOldestFirst.every(rational) || !Array.isArray(proof.queries) || !proof.queries.length
                || proof.queries.some(query => !Number.isSafeInteger(query.index) || query.index < 0 || !rational(query.value)
                    || !(query.role === 'RETAINED_MODEL_TERM' && query.matchesRetained === true
                        || query.role === 'MODEL_PREDICTION' && query.matchesRetained === null))
                || receipt.finiteCheck?.status !== 'CONFIRMED_FINITE_DATA' || receipt.finiteCheck.complete !== true
                || receipt.replay?.status !== 'IDENTICAL_CANONICAL_EVIDENCE' || receipt.replay.actualEvidenceHash !== view.evidence.contentHash
                || ['preparation', 'binding', 'queries'].some(phase => receipt.work?.[phase]?.status !== 'COMPLETE')) {
                throw new Error('Der Modellbeleg enthält keinen vollständig geprüften Modellnachweis.');
            }
        } else if (proof) throw new Error('Ein unbestätigtes Ergebnis enthält einen widersprüchlichen Modellnachweis.');
        return receiptPath;
    }
    // Format exact backend coefficients without converting them to floating-point numbers.
    function linearExpression(coefficients, variables, offset = '0') {
        const terms = coefficients.map((value, index) => ({value, variable: variables[index]}));
        terms.push({value: offset, variable: ''});
        let result = '';
        for (const {value, variable} of terms) {
            if (value === '0') continue;
            const negative = value.startsWith('-'), magnitude = negative ? value.slice(1) : value;
            const term = variable ? (magnitude === '1' ? variable : `${magnitude} × ${variable}`) : magnitude;
            result += result ? `${negative ? ' − ' : ' + '}${term}` : `${negative ? '−' : ''}${term}`;
        }
        return result || '0';
    }
    function workCounts(line, executedLabel = 'ausgeführt') {
        const count = value => Number.isSafeInteger(value) && value >= 0 ? String(value) : 'nicht verfügbar';
        return `Budget ${count(line?.configured)} · ${executedLabel} ${count(line?.executed)} · übersprungen ${count(line?.skipped)} · verbleibend ${count(line?.remaining)}`;
    }
    function renderProofWork(receipt) {
        const work = node('details'); work.id = 'domainModelProofWork';
        work.append(node('summary', 'Zusätzliche Arbeit je Prüfphase'));
        const replay = node('div'); replay.dataset.proofPhase = 'replay';
        replay.append(node('h4', 'Replay der Quelle'), node('p', `Quellaufrufe: ${workCounts(receipt.replay?.admission)}`));
        if (Array.isArray(receipt.replay?.resources)) {
            const resources = node('ul');
            for (const line of receipt.replay.resources) resources.append(node('li', `${line.resource}: ${workCounts(line)}`));
            replay.append(resources);
        }
        work.append(replay);
        const finite = node('div'); finite.dataset.proofPhase = 'finite-check';
        finite.append(node('h4', 'Unabhängige Prüfung der endlichen Daten'));
        if (receipt.finiteCheck) {
            for (const [key, label] of [['inputItems', 'Eingabeelemente'], ['termChecks', 'Termprüfungen'], ['scalarOperations', 'Freigegebene skalare Operationen']]) {
                finite.append(node('p', `${label}: ${workCounts(receipt.finiteCheck.work?.[key])}`));
            }
        } else finite.append(node('p', 'In diesem Modelllauf nicht ausgeführt.'));
        work.append(finite);
        const phaseNames = [['preparation', 'Modellgesetz vorbereiten und prüfen'], ['binding', 'Anfangszustand binden'], ['queries', 'Exakte Modellwerte abfragen']];
        const phaseStatuses = {COMPLETE: 'Abgeschlossen', NOT_RUN: 'Nicht ausgeführt',
            BUDGET_INCONCLUSIVE: 'Budget ausgeschöpft; kein abschließendes Ergebnis',
            ARITHMETIC_LIMIT: 'Grenze der exakten Arithmetik erreicht', TECHNICAL_FAILURE: 'Technischer Fehler'};
        for (const [key, label] of phaseNames) {
            const phase = node('div'); phase.dataset.proofPhase = key;
            const line = receipt.work?.[key];
            phase.append(node('h4', label), node('p', `${phaseStatuses[line?.status] || line?.status || 'Status nicht verfügbar'}. ${workCounts(line, 'verbraucht')}`));
            work.append(phase);
        }
        work.append(node('p', 'Jede Phase und jede Ressource behält ihre eigene Einheit. Die gespeicherten Zähler der Quelle bleiben unverändert.'),
            node('p', 'Die drei Modellphasen zählen logische Budgeteinheiten des Prüfers. Bei einer Budgetverweigerung kann auch der ausgeschöpfte Rest eines Arbeitspakets als verbraucht gebucht sein.'),
            node('p', 'Keine Gesamtarbeit ausgewiesen: Interne Arithmetik, Modellprojektion sowie Kodierung und Hashberechnung sind nicht vollständig erfasst.'));
        return work;
    }
    function renderModelProof(receipt) {
        const finite = node('p', receipt.finiteCheck
            ? `Unabhängige Prüfung: ${validationMessages[receipt.finiteCheck.status] || 'Kein bestätigtes Ergebnis für die endlichen Daten.'}`
            : 'Für diesen Modelllauf wurden die endlichen Daten nicht unabhängig geprüft.');
        finite.id = 'domainModelProofFiniteCheck'; proofResult.append(finite);
        if (receipt.status === 'UNSUPPORTED' && receipt.detail === 'MODEL_DIMENSION_EXCEEDS_THREE') {
            proofResult.append(node('p', 'Das ausgewählte Modell benötigt mehr als drei Zustandskoordinaten. Diese Modellprüfung unterstützt ein bis drei Koordinaten.'));
        }
        if (receipt.model) {
            const model = receipt.model;
            const modelView = node('section'); modelView.id = 'domainModelProofModel';
            modelView.append(node('h3', 'Ausgewähltes affines Modell'),
                node('p', `${model.dimension} Zustandskoordinaten · Startindex 0 · Alle Werte sind exakt rational.`));
            const equations = model.linear.map((row, index) => `${model.coordinates[index]}(n + 1) = ${linearExpression(row, model.coordinates.map(name => `${name}(n)`), model.offset[index])}`);
            equations.push('', ...model.initialState.map((value, index) => `${model.coordinates[index]}(0) = ${value}`), '', 'a(n) = x0(n)');
            const rendered = node('pre', equations.join('\n')); rendered.className = 'output'; modelView.append(rendered);
            modelView.append(node('p', 'Modellannahme: Diese Anfangswerte und Übergänge definieren die Folge für alle n ≥ 0.'));
            proofResult.append(modelView);
        }
        if (receipt.status === 'PROVED_MODEL') {
            const proof = receipt.proof, recurrence = node('section'); recurrence.id = 'domainModelProofRecurrence';
            recurrence.append(node('h3', 'Bewiesenes Gesetz dieses Modells'));
            const terms = proof.coefficientsOldestFirst.map((value, index) => index === 0 ? 'a(n)' : `a(n + ${index})`);
            const equation = node('pre', `a(n + ${proof.recurrenceOrder}) = ${linearExpression(proof.coefficientsOldestFirst, terms)}\nfür alle n ≥ 0 unter der Modellannahme`);
            equation.className = 'output'; recurrence.append(equation); proofResult.append(recurrence);
            proofResult.append(node('h3', 'Exakte Modellwerte'),
                node('p', 'Modellvorhersagen außerhalb der gespeicherten Daten sind bedingte Rechenergebnisse dieses Modells.'));
            const values = node('pre'); values.className = 'output';
            for (const query of proof.queries) {
                const row = node('span'); row.dataset.modelIndex = String(query.index);
                const value = node('span', query.value); value.dataset.modelValue = '';
                row.append(document.createTextNode(`a(${query.index}) = `), value,
                    document.createTextNode(query.role === 'MODEL_PREDICTION' ? ' · Modellvorhersage\n' : ' · gespeichertes Glied bestätigt\n'));
                values.append(row);
            }
            proofResult.append(values);
        }
        proofResult.append(renderProofWork(receipt));
        const identity = disclosure('Quellenbindung und Belegidentität', receipt); identity.id = 'domainModelProofIdentity';
        proofResult.append(identity, node('p', 'Externe Neuheit, Promotion und Public Evidence wurden nicht bewertet. Die Status der ursprünglichen Evidence bleiben unverändert.'));
        proofResult.hidden = false;
    }
    proofButton.addEventListener('click', async () => {
        const view = selected, ticket = selectionEpoch;
        if (!view || (view.domainId === 'scoped-logic' && view.domainRevision === 'v1')) return;
        const actionPath = `${base}/${view.runId.slice(7)}/model-proof`;
        proofButton.disabled = true;
        clearProof('Quelle, endliche Daten und Modellgesetz werden geprüft …');
        try {
            const response = await fetch(actionPath, {method: 'POST', redirect: 'error', headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({expectedWorkspaceHash: view.contentHash, expectedEvidenceHash: view.evidence.contentHash})});
            const receiptText = await response.text();
            if (ticket !== selectionEpoch) return;
            const receipt = parseResponse(response, receiptText);
            const receiptPath = checkModelProof(receipt, response, view, actionPath);
            proofStatus.textContent = 'Der gespeicherte Modellbeleg wird erneut an seiner Quelle geprüft …';
            const retainedResponse = await fetch(receiptPath, {redirect: 'error', cache: 'no-store'});
            const retainedText = await retainedResponse.text();
            if (ticket !== selectionEpoch) return;
            if (!retainedResponse.ok) throw new Error(`Der gespeicherte Modellbeleg konnte nicht überprüft werden (HTTP ${retainedResponse.status}).`);
            // GET reproduces the retained proof on the server. Compare original response text,
            // never a JSON serialization that could round large numeric source values.
            if (retainedResponse.headers.get('X-Regelsuche-Run-Id') !== view.runId
                || retainedResponse.headers.get('ETag') !== response.headers.get('ETag')
                || retainedText !== receiptText) {
                throw new Error('Der gespeicherte Modellbeleg stimmt nicht mit der Antwort für die ausgewählte Quelle überein.');
            }
            renderModelProof(receipt);
            proofStatus.textContent = proofMessages[receipt.status];
            proofStatus.dataset.status = receipt.status;
            proofReceiptLink.href = receiptPath;
            proofReceiptLink.hidden = false;
        } catch (error) {
            if (ticket === selectionEpoch) clearProof(`Modellprüfung fehlgeschlagen: ${error.message}`);
        } finally {
            if (ticket === selectionEpoch) proofButton.disabled = false;
        }
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
