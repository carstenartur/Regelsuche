/* Bounded dossier renderer. All evidence comes from the active retained run. */
(() => {
    'use strict';
    const api = window.RegelsucheCandidateDossierState;
    const missing = 'Nicht im Artefakt gespeichert';
    const text = (tag, value, parent) => {
        const node = document.createElement(tag);
        if (value !== undefined) node.textContent = String(value);
        if (parent) parent.appendChild(node);
        return node;
    };
    const display = value => Array.isArray(value) ? (value.length ? value.join('\n') : 'Keine aufgezeichnet') : String(value ?? missing);
    function table(parent, rows, headings) {
        const table = text('table', undefined, parent);
        if (headings) {
            const row = text('tr', undefined, text('thead', undefined, table));
            headings.forEach(heading => { text('th', heading, row).scope = 'col'; });
        }
        const body = text('tbody', undefined, table);
        rows.forEach(values => {
            const row = text('tr', undefined, body);
            values.forEach((value, index) => {
                const cell = text(index ? 'td' : 'th', display(value), row);
                if (!index) cell.scope = 'row';
            });
        });
    }
    function section(parent, title) {
        const node = text('section', undefined, parent);
        text('h4', title, node);
        return node;
    }
    const fact = value => value ? 'TRUE' : 'FALSE';

    function renderDossier(parent, run, artifact, selected, select, edge) {
        const data = artifact.content, candidate = selected.candidate, bridge = selected.bridge;
        const detail = text('article', undefined, parent);
        detail.id = 'candidateDossierDetail'; detail.dataset.candidateId = candidate.stateHash;
        text('h3', 'Kandidatendossier', detail);
        table(detail, [['Run-ID', run.runId], ['Kandidaten-ID / Suchzustand', candidate.stateHash],
            ['Informationsgrenze', run.plan.informationTrack + '\n' + run.plan.informationBoundaryHash],
            ['Quelle', selected.source.expression], ['Kandidat', candidate.expression],
            ['Umfang / Kontext', 'Vollständiger Ausdruck im gespeicherten Suchzustand'],
            ['Exakte lokale AST-Position', missing + '; applicationKey der Kante bleibt unverändert sichtbar.'],
            ['Kandidatentypen', bridge ? 'Aufgezeichnete Katalogbrücke (discoveredBridge)' : missing],
            ['Syntax-ID / semantische ID', missing + '; stateHash ist eine Suchzustandsidentität.']]);

        const compression = section(detail, 'Rohe Beschreibung und Sharing');
        text('p', 'Die Dimensionen bleiben getrennt. Ein längerer Ausdruck kann eine belegte Fähigkeit erschließen.', compression);
        const dimensions = ['tokenCount', 'astNodeCount', 'operatorCount', 'numericBitLength', 'semanticValueOccurrences',
            'distinctSemanticValues', 'repeatedSemanticValueSavings'];
        table(compression, dimensions.map(key => [key, selected.source.metrics[key], candidate.metrics[key],
            candidate.metrics[key] - selected.source.metrics[key]]), ['Dimension', 'Quelle', 'Kandidat', 'Δ Kandidat − Quelle']);
        table(compression, [['Variablen', selected.source.metrics.variableSymbols, candidate.metrics.variableSymbols],
            ['Funktionen', selected.source.metrics.functionSymbols, candidate.metrics.functionSymbols]], ['Symbole', 'Quelle', 'Kandidat']);
        text('p', 'Sharing-Zähler sind gespeichert; ein rekonstruierbarer DAG und lokale Vorher-/Nachher-Metriken: ' + missing + '.', compression);

        const recognition = section(detail, 'Bekannte Form und Evidenzschwelle');
        recognition.className = 'dossier-recognition';
        if (bridge) {
            const classification = data.classification;
            table(recognition, [['Struktur', bridge.structureId], ['Erkennung', bridge.recognitionMode], ['Mindestbeleg', bridge.minimumEvidence]]);
            table(recognition, [
                ['Katalog deaktiviert', classification.disabledMatches, 'Kein Unlock-Beleg in dieser Klassifikation'],
                ['Vorläufige Klassifikation', classification.provisionalMatches, classification.provisionalUnlocks],
                ['Verifizierte Klassifikation', classification.verifiedMatches, classification.verifiedUnlocks]
            ], ['Klassifikation', 'Treffer', 'Aufgezeichnete Konsequenzen']);
            text('p', 'Ein Formtreffer allein ist keine ausführbare Fähigkeit.', recognition);
            table(recognition, [['Vorläufige Warnungen', classification.provisionalWarnings], ['Verifizierte Warnungen', classification.verifiedWarnings]]);
        } else text('p', 'Für diesen Kandidaten wurde keine Einzelklassifikation gespeichert. Die Brückenklassifikation eines anderen Kandidaten wird nicht übertragen.', recognition);

        const capability = section(detail, 'Ausführbare Folgefähigkeit');
        capability.className = 'dossier-capability';
        if (bridge) {
            const follow = data.followOnExecution;
            table(capability, [['Konsequenz-ID', bridge.consequenceId],
                ['Regel in Formation / deaktiviert / aktiviert', [follow.formationTargetRulePresent, follow.disabledTargetRulePresent, follow.enabledTargetRulePresent].map(fact).join(' / ')],
                ['Nachfolger aus Formation', follow.formationTargetSuccessors], ['Nachfolger bei deaktiviertem Pack', follow.disabledTargetSuccessors],
                ['Ausgeführte Nachfolger bei aktiviertem Pack', follow.enabledTargetSuccessors],
                ['Regelinventar deaktiviert', follow.disabledRuleInventoryHash], ['Regelinventar aktiviert', follow.enabledRuleInventoryHash]]);
        } else text('p', 'Ausführbare Folgefähigkeit: ' + missing + ' für diesen Kandidaten.', capability);
        text('p', 'Vollständige Fähigkeitsfront vor/nach der Transformation und verlorene Fähigkeiten: ' + missing + '.', capability);

        const guards = section(detail, 'Annahmen und Guard-Evidenz');
        table(guards, [['Deklarierte Run-Annahmen', run.input.assumptions], ['Aufgezeichnete Pfad-Annahmen', candidate.assumptions],
            ['Guard-Auswertung', 'UNKNOWN · ' + missing], ['Erfüllte / widerlegte Annahmen', missing], ['Konfliktstatus', 'UNKNOWN · Keine Guard-Auswertungen gespeichert']]);
        text('p', 'Statuslegende: TRUE = erfüllt; FALSE = widerlegt; UNKNOWN = unbelegt; Konflikt = widersprüchliche Belege. Diese Legende ist keine Auswertung.', guards);

        const validation = section(detail, 'Validierung und Beweis');
        table(validation, [['Aufgezeichneter Kandidatenstatus', bridge ? bridge.candidateProofStatus : missing],
            ['equivalencePreserving im Suchzustand', fact(candidate.equivalencePreserving) + ' · Konstruktionsmetadatum; kein formaler Beweis'],
            ['Exaktes Prüfzertifikat / Solver-Transkript', missing], ['Gegenbeispielstatus', 'UNKNOWN · ' + missing]]);
        text('p', 'Das Öffnen dieses Dossiers führt keine neue Validierung aus.', validation);

        const lineage = section(detail, 'Primitive Lineage und gebundene Navigation');
        table(lineage, [['Elternzustand', candidate.parentStateHash], ['Tiefe', candidate.depth], ['Regelpfad', candidate.pathRuleIds],
            ['Primitive Regel-IDs / Makro-Expansion', candidate.primitiveRuleIds], ['Knowledge Packs im Pfad', candidate.packIds]]);
        selected.edges.forEach(transition => {
            const button = text('button', 'Graphkante ' + transition.sequence + ' · ' + transition.ruleId, lineage);
            button.type = 'button'; button.dataset.dossierEdge = String(transition.sequence);
            button.setAttribute('aria-current', String(edge === String(transition.sequence)));
            button.addEventListener('click', () => {
                select(candidate.stateHash, String(transition.sequence));
                document.getElementById('candidateGraphEvidence')?.focus();
            });
        });
        if (!selected.edges.length) text('p', 'Keine gebundene eingehende Graphkante gespeichert.', lineage);
        for (const role of ['PATH_REPLAY', 'RULE_RADAR', 'PROOF_OBLIGATIONS']) {
            const reference = run.artifacts.find(a => a.role === role);
            text('p', role + ': ' + reference.status + ' · ' + (reference.detail || 'Exakte Kandidatenkorrelation: ' + missing), lineage);
        }
        if (selected.selectedEdge) {
            const graph = section(lineage, 'Exakte gespeicherte Graphkante');
            graph.id = 'candidateGraphEvidence'; graph.tabIndex = -1;
            table(graph, [['Run-ID', run.runId], ['Suchartefakt', data.searchContentHash],
                ...Object.entries(selected.selectedEdge)]);
            const back = text('button', 'Zur Kandidatenauswahl', graph); back.type = 'button';
            back.addEventListener('click', () => {
                select(candidate.stateHash, '');
                document.querySelector('button[data-candidate-id="' + candidate.stateHash + '"]')?.focus();
            });
        }
        const provenance = section(detail, 'Provenienz und Identitäten');
        table(provenance, [['Dossier-Hash', artifact.contentHash], ['Suchartefakt-Hash', data.searchContentHash],
            ['Kandidatenmenge / Freeze', data.freeze.candidateSetHash], ['Freeze Receipt', data.freeze.enabledFreezeReceiptHash],
            ['Regelinventar', run.plan.ruleInventoryHash], ['Strukturkatalog', run.plan.knownStructureCatalogHash],
            ['Backend-Identitäten', run.plan.backendIdentities], ['Repository-Revision', run.revisions.repositoryCommit],
            ['Anwendungsrevision', run.revisions.applicationRevision]]);
        if (bridge) table(provenance, [['Katalogquelle', bridge.sourceProject], ['Quellreferenz', bridge.sourceReference], ['Lizenz', bridge.license]]);
        text('p', data.claimBoundary, provenance);
    }

    function createController(load, importArtifact) {
        let host = null, active = null, workspace = null, select = null;
        const store = api.createStore(load, paint);
        function paint() {
            if (!host || !active || active.status !== 'READY') return;
            host.replaceChildren();
            const reference = active.workspace.artifacts.find(a => a.role === 'CANDIDATE_DOSSIERS');
            text('h3', 'Discovery-Kandidaten', host);
            if (reference.status !== 'AVAILABLE' || reference.artifactSchema !== api.schema) {
                text('p', 'Kandidatendossier: ' + reference.status + ' · ' + (reference.detail || 'Nicht unterstütztes Schema: ' + reference.artifactSchema), host);
                return;
            }
            const label = text('label', 'Gebundenes Kandidatenartefakt importieren (max. 1 MiB) ', host);
            const input = text('input', undefined, label); input.id = 'importCandidateDossier'; input.type = 'file'; input.accept = '.json,application/json';
            input.addEventListener('change', () => {
                const file = input.files[0]; if (!file) return;
                const run = active.workspace;
                store.open(run, async () => {
                    if (file.size > 1048576) throw new Error('Kandidatenartefakt überschreitet 1 MiB');
                    return importArtifact(run, await file.text());
                });
            });
            const status = text('p', undefined, host); status.id = 'candidateDossierStatus'; status.setAttribute('role', 'status');
            const state = store.state();
            if (state.status !== 'READY' || state.runId !== active.workspace.runId) {
                status.textContent = state.status === 'ERROR' ? 'Dossier konnte nicht geöffnet werden: ' + state.error : 'Gebundenes Kandidatenartefakt laden …';
                return;
            }
            status.textContent = 'Gespeicherter Beleg geöffnet · ' + state.runId;
            const candidateId = active.candidate || state.artifact.content.discoveredBridge.stateHash;
            let selected;
            try { selected = api.selection(state.artifact, candidateId, active.edge || ''); }
            catch (error) { status.textContent = error.message; status.className = 'run-error'; return; }
            if (!active.candidate) { select(candidateId, '', active.role || 'CANDIDATE_DOSSIERS'); return; }
            const list = text('div', undefined, host); list.className = 'dossier-candidate-list'; list.setAttribute('aria-label', 'Gespeicherte Kandidaten');
            state.artifact.content.search.states.filter(s => s.depth > 0).forEach(candidate => {
                const button = text('button', candidate.expression, list);
                button.type = 'button'; button.dataset.candidateId = candidate.stateHash;
                button.setAttribute('aria-pressed', String(candidate.stateHash === candidateId));
                button.addEventListener('click', () => {
                    select(candidate.stateHash, '');
                    document.querySelector('button[data-candidate-id="' + candidate.stateHash + '"]')?.focus();
                });
            });
            renderDossier(host, active.workspace, state.artifact, selected, select, active.edge || '');
        }
        return Object.freeze({
            clear: () => { host = active = workspace = null; store.clear(); },
            render: (parent, state, selection) => {
                host = parent; active = state; select = selection;
                const changed = workspace !== state.workspace;
                workspace = state.workspace;
                const reference = workspace.artifacts.find(a => a.role === 'CANDIDATE_DOSSIERS');
                if (changed && reference.status === 'AVAILABLE' && reference.artifactSchema === api.schema) store.open(workspace);
                else paint();
            }
        });
    }
    window.RegelsucheCandidateDossier = Object.freeze({createController});
})();
