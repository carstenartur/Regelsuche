/* Bounded dossier renderer and operator actions bound to the active retained run. */
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

    function renderLiterature(parent, runId, candidateId) {
        const panel = text('details', undefined, parent);
        panel.className = 'candidate-literature';
        text('summary', 'Private Literaturprüfung', panel);
        const status = text('p', undefined, panel);
        status.setAttribute('role', 'status');
        const view = text('div', undefined, panel);
        const url = '/api/discovery-runs/' + runId.slice(7) + '/literature/' + candidateId.slice(7);
        const labels = {
            PRIOR_ART_FOUND: 'Bekannte Vorarbeit gefunden',
            NO_PRIOR_ART_FOUND_WITHIN_SCOPE: 'Im dokumentierten Umfang keine Vorarbeit gefunden',
            UNRESOLVED: 'Literaturprüfung offen',
            SUCCESS: 'Abruf abgeschlossen',
            RATE_LIMITED: 'Abruf begrenzt',
            FAILED: 'Abruf fehlgeschlagen'
        };
        const access = {
            SEARCHED: 'Durchsucht', PARTIAL: 'Teilweise zugänglich', INACCESSIBLE: 'Kein Zugang'
        };
        const relations = {
            EXACT: 'gleiche Aussage',
            EQUIVALENT: 'äquivalent',
            SOURCE_SPECIAL_CASE: 'Quelle ist ein Spezialfall',
            SOURCE_GENERALIZATION: 'Quelle ist eine Verallgemeinerung',
            RELATED: 'verwandt'
        };
        let opened = false;
        let busy = false;

        async function request(method, body) {
            const response = await fetch(url, {
                method, body, cache: 'no-store',
                headers: {'X-Regelsuche-Literature': 'workbench', 'Content-Type': 'application/json'}
            });
            const value = await response.json();
            if (!response.ok && !value.result) {
                throw new Error(response.status === 403 ? 'Aktion im privaten Arbeitsbereich nicht freigegeben' : (value.code || response.status));
            }
            return value;
        }

        function sourceLink(parent, label, href) {
            if (typeof href === 'string' && /^https?:\/\//i.test(href)) {
                const link = text('a', label, parent);
                link.href = href;
                link.rel = 'noreferrer';
            } else {
                text('span', label, parent);
            }
        }

        async function refresh() {
            const data = await request('GET');
            if (!panel.isConnected) return;
            view.replaceChildren();
            text('h4', 'Geprüfte Literaturbezüge', view);
            if (!data.assessments.length) text('p', 'Literaturstand ungeprüft.', view);
            data.assessments.forEach(assessment => {
                const detail = text('details', undefined, view);
                text('summary', labels[assessment.status] + ' · ' + assessment.reviewedOn, detail);
                text('p', 'Geprüft durch ' + assessment.reviewedBy, detail);
                if (assessment.schema === 'regelsuche.literature-assessment/v1') {
                    text('p', 'Historische Bewertung ohne Angaben zur Zugangsabdeckung.', detail);
                }
                assessment.scope.forEach(scope => {
                    text('p', scope.source + ' · ' + scope.versionOrDate, detail);
                    if (scope.coverage) {
                        text('p', access[scope.coverage.status] + ' · ' + scope.coverage.limitations, detail);
                        scope.coverage.unresolvedHits.forEach(hit =>
                            text('p', 'Ungeprüfter Treffer · ' + hit.identifier + ' · ' + hit.reason, detail));
                    }
                });
                assessment.queries.forEach(query => text('p', 'Suchanfrage · ' + query, detail));
                assessment.matches.forEach(match => {
                    const row = text('p', undefined, detail);
                    sourceLink(row, match.source.title + ' · ' + match.source.locator, match.source.url);
                    text('span', ' · ' + relations[match.relation] + ' · ' + match.rationale, row);
                    if (Number.isInteger(match.scopeIndex)) {
                        text('p', 'Gefunden in · ' + assessment.scope[match.scopeIndex].source, detail);
                    }
                    if (match.assumptions.length) text('p', 'Voraussetzungen · ' + match.assumptions.join(', '), detail);
                    if (match.entailmentRationale) text('p', match.entailmentRationale, detail);
                });
            });
            const template = text('button', 'Bewertungsvorlage herunterladen', view);
            template.type = 'button';
            template.addEventListener('click', () => {
                const templateData = {
                    schema: 'regelsuche.literature-assessment/v2', subject: data.subject, reviewedOn: '',
                    reviewedBy: '', scope: [{source: '', versionOrDate: '',
                        coverage: {status: '', limitations: '', unresolvedHits: []}}],
                    queries: [], status: 'UNRESOLVED', matches: []
                };
                const blob = new Blob([JSON.stringify(templateData, null, 2) + '\n'], {type: 'application/json'});
                const link = document.createElement('a');
                link.href = URL.createObjectURL(blob);
                link.download = 'literaturbewertung.json';
                link.click();
                setTimeout(() => URL.revokeObjectURL(link.href), 1000);
            });
            const label = text('label', ' Bewertung importieren ', view);
            const upload = text('input', undefined, label);
            upload.type = 'file';
            upload.accept = '.json,application/json';
            upload.addEventListener('change', async () => {
                const file = upload.files[0];
                if (!file || busy || !panel.isConnected) return;
                if (file.size > 128 * 1024) { status.textContent = 'Die Bewertung überschreitet 128 KiB.'; return; }
                busy = true;
                upload.disabled = true;
                try {
                    await request('PUT', await file.text());
                    await refresh();
                    status.textContent = 'Literaturbewertung gespeichert.';
                } catch (error) { status.textContent = 'Import fehlgeschlagen · ' + error.message; }
                finally { busy = false; upload.disabled = false; }
            });

            text('h4', 'TheoremSearch', view);
            if (!data.onlineEnabled) {
                text('p', 'Der externe Abruf ist serverseitig ausgeschaltet.', view);
            } else {
                text('p', 'TheoremSearch protokolliert den abgesendeten Suchtext. Die Anfrage wird erst nach deiner Bestätigung gesendet.', view);
                const field = text('label', 'Suchtext für TheoremSearch ', view);
                const query = text('textarea', undefined, field);
                query.maxLength = data.maxQueryChars;
                const prepare = text('button', 'Anfrage prüfen', view);
                prepare.type = 'button';
                const confirmation = text('div', undefined, view);
                query.addEventListener('input', () => confirmation.replaceChildren());
                prepare.addEventListener('click', () => {
                    if (busy || !query.value.trim()) return;
                    const exact = query.value;
                    confirmation.replaceChildren();
                    text('p', 'Dieser Text wird an ' + data.endpoint + ' übertragen.', confirmation);
                    text('pre', exact, confirmation);
                    const submit = text('button', 'An TheoremSearch senden', confirmation);
                    submit.type = 'button';
                    submit.addEventListener('click', async () => {
                        if (busy || !panel.isConnected || query.value !== exact) return;
                        busy = true;
                        submit.disabled = query.disabled = prepare.disabled = true;
                        try {
                            const receipt = await request('POST', JSON.stringify({query: exact, confirmed: true}));
                            await refresh();
                            status.textContent = labels[receipt.result.status]
                                + (receipt.result.retryAfterSeconds ? ' · Wartezeit ' + receipt.result.retryAfterSeconds + ' s' : '');
                        } catch (error) { status.textContent = 'Abruf fehlgeschlagen · ' + error.message; }
                        finally { busy = false; submit.disabled = query.disabled = prepare.disabled = false; }
                    });
                });
            }
            data.receipts.forEach(receipt => {
                const detail = text('details', undefined, view);
                const result = receipt.result;
                text('summary', receipt.startedAt + ' · ' + (result ? labels[result.status] : 'Abruf ohne gespeicherten Abschluss'), detail);
                text('pre', receipt.query, detail);
                if (!result || result.status !== 'SUCCESS') return;
                text('p', 'Ungeprüfte Treffer · ' + result.hits.length, detail);
                result.hits.forEach(hit => {
                    const row = text('p', undefined, detail);
                    sourceLink(row, [hit.name, hit.title, hit.source].filter(Boolean).join(' · '), hit.link);
                });
            });
        }
        panel.addEventListener('toggle', () => {
            if (!panel.open || opened) return;
            opened = true;
            refresh().catch(error => {
                status.textContent = 'Literaturprüfung nicht verfügbar · ' + error.message;
                opened = false;
            });
        });
    }

    function renderNative(parent, run, artifact, selected, select, edge) {
        const data = artifact.content, candidate = selected.candidate, state = selected.observation;
        const detail = text('article', undefined, parent);
        detail.id = 'candidateDossierDetail'; detail.dataset.candidateId = candidate.stateId;
        text('h3', 'Nativer target-free Lauf · Kandidat und Ausführung', detail);
        table(detail, [['Run-ID', run.runId], ['Suchartefakt', artifact.contentHash], ['Zustands-ID', candidate.stateId],
            ['Quelle', run.input.displayText], ['Besuchter Ausdruck', state.expression], ['Tiefe', state.depth],
            ['Nativer Ergebnisstatus', data.goalStatus], ['Restfrontier beim Suchende', data.events[data.events.length - 1].frontierSize],
            ['Ausführungswurzel', candidate.executionHash], ['Annahmen', state.assumptions]]);
        text('p', 'UNTARGETED ist der Status des nativen Runners. Daraus folgt keine vollständige Closure oder Zielerreichung.', detail);
        const work = section(detail, 'Gespeicherte Arbeit und native Diagnose');
        table(work, [['Engine-Aufrufe', data.work.engineCalls], ['Zurückgegebene Transformationskandidaten', data.work.generatedTransformations],
            ['Primitive Rewrites dieser Erzeugungen', data.work.returnedCandidateWork.primitiveRewrites],
            ['Exakte Theorie-Schritte dieser Erzeugungen', data.work.returnedCandidateWork.exactTheorySteps],
            ['Exakte Theorie-Arbeit dieser Erzeugungen', data.work.returnedCandidateWork.exactTheoryWorkUnits],
            ...Object.entries(data.metrics)]);
        text('p', 'configuredWork/consumedWork des Run-Manifests zählen erzeugte Transformationskandidaten. Pfadpräfixe werden nicht nochmals summiert.', work);
        text('p', data.recordingOverhead, work);
        const identity = section(detail, 'Applikationsidentitäten und beobachtete Linie');
        table(identity, [['Kanonische Applikationsidentitätsmenge', state.applicationKeys], ['Aufgezeichneter Ausdruckspfad', state.path],
            ['Aufgezeichneter Regelpfad', state.appliedRuleIds], ['Guard-/Proof-Validierung', 'NOT_EVALUATED · Konstruktionsmetadaten sind kein formaler Beweis']]);
        text('p', 'Die Applikationsidentitäten sind eine sortierte Menge. Die folgende Reihenfolge stammt ausschließlich aus der gespeicherten Ausführung.', identity);
        const replay = section(detail, 'Replay-Ansicht der gespeicherten Schritte');
        replay.id = 'nativeExecutionReplay';
        function chooseReplay(step, role = 'PATH_REPLAY') {
            select(candidate.stateId, String(step.edge.sequence), role, String(step.generation.sequence));
            if (role === 'SEARCH_GRAPH') document.getElementById('candidateGraphEvidence')?.focus();
            else document.querySelector('button[data-replay-generation="' + step.generation.sequence + '"]')?.focus();
        }
        function generationDetails(parent, generation) {
            table(parent, [['Erzeugung im Lauf', generation.sequence], ['Quellwurzel', generation.sourceExpression],
                ['Tatsächliche Quellposition', generation.occurrencePath.length ? generation.occurrencePath.join('.') : 'root'],
                ['Quellvorkommen', generation.sourceOccurrenceExpression], ['Ersetztes Vorkommen', generation.transformedOccurrenceExpression],
                ['Ergebniswurzel', generation.transformedExpression], ['Applikationsidentität', generation.applicationKey],
                ['Ausführungswurzel des Schritts', generation.executionHash]]);
        }
        if (!selected.replaySteps.length) text('p', 'Startzustand: keine Transformation im Pfad.', replay);
        else text('p', 'Schritt auswählen: Quellvorkommen und Graphkante gehören zum gespeicherten Pfad. Der Endkandidat bleibt ausgewählt.', replay);
        const steps = text('div', undefined, replay); steps.className = 'native-replay-controls';
        steps.setAttribute('aria-label', 'Gespeicherte Replay-Schritte');
        selected.replaySteps.forEach(step => {
            const button = text('button', 'Schritt ' + step.position + ' · ' + step.generation.ruleId, steps);
            button.type = 'button'; button.dataset.replayGeneration = String(step.generation.sequence);
            button.setAttribute('aria-current', selected.selectedReplay === step ? 'step' : 'false');
            button.addEventListener('click', () => chooseReplay(step));
        });
        if (selected.selectedReplay) {
            const step = selected.selectedReplay, generation = step.generation;
            const context = section(replay, 'Ausgewählter Replay-Schritt · ' + step.position + ' von ' + selected.replaySteps.length);
            context.id = 'nativeReplaySelection'; context.dataset.generationSequence = String(generation.sequence);
            text('p', 'Ausgewählter Endkandidat: ' + state.expression, context);
            const position = text('p', 'Tatsächliche Quellposition: ', context);
            text('code', generation.occurrencePath.length ? generation.occurrencePath.join('.') : 'root', position).dataset.replayOccurrence = '';
            const navigation = text('div', undefined, context); navigation.className = 'native-replay-controls';
            for (const [id, label, adjacent] of [['nativeReplayPrevious', 'Vorheriger Schritt', selected.previousReplay],
                ['nativeReplayNext', 'Nächster Schritt', selected.nextReplay]]) {
                const button = text('button', label, navigation); button.type = 'button'; button.id = id; button.disabled = !adjacent;
                if (adjacent) button.addEventListener('click', () => chooseReplay(adjacent));
            }
            const graphButton = text('button', 'Gespeicherte Graphkante öffnen', navigation);
            graphButton.type = 'button'; graphButton.id = 'nativeReplayGraph';
            graphButton.addEventListener('click', () => chooseReplay(step, 'SEARCH_GRAPH'));
            generationDetails(context, generation);
        }
        const allSteps = text('details', undefined, replay);
        text('summary', 'Vollständige gespeicherte Schrittabfolge', allSteps);
        selected.replaySteps.forEach(step => {
            const entry = section(allSteps, 'Schritt ' + step.position + ' · ' + step.generation.ruleId);
            entry.dataset.generationSequence = String(step.generation.sequence);
            generationDetails(entry, step.generation);
        });
        text('p', 'Diese Ansicht öffnet die gespeicherte Schrittabfolge. Sie führt keinen neuen Lauf und keine neue Beweisprüfung aus.', replay);
        const graph = section(detail, 'Gebundene Graphübergänge');
        text('p', 'Erzeugungen, Enqueues und tatsächlich besuchte Zustände bleiben getrennt. Die Kanten hier führen zu besuchten Zuständen.', graph);
        selected.replaySteps.forEach(step => {
            const transition = step.edge;
            const button = text('button', 'Graphkante ' + transition.sequence + ' · Schritt ' + step.position, graph);
            button.type = 'button'; button.dataset.dossierEdge = String(transition.sequence);
            button.setAttribute('aria-current', String(selected.selectedEdge === transition));
            button.addEventListener('click', () => chooseReplay(step, 'SEARCH_GRAPH'));
        });
        if (selected.selectedEdge) {
            const bound = section(graph, 'Exakte gespeicherte Graphkante'); bound.id = 'candidateGraphEvidence'; bound.tabIndex = -1;
            const generation = data.generations[selected.selectedEdge.generationSequence];
            table(bound, [['Run-ID', run.runId], ...Object.entries(selected.selectedEdge), ['applicationKey', generation.applicationKey],
                ['Quellposition', generation.occurrencePath.length ? generation.occurrencePath.join('.') : 'root'],
                ['Ausführungswurzel', generation.executionHash]]);
            const step = selected.replaySteps.find(item => item.edge === selected.selectedEdge);
            if (step) {
                const back = text('button', 'Replay-Schritt öffnen', bound); back.type = 'button'; back.id = 'nativeGraphReplay';
                back.addEventListener('click', () => chooseReplay(step));
            }
        }
        const trace = text('details', undefined, detail);
        text('summary', 'Vollständiger nativer Ereignistrace einschließlich verworfener Erzeugungen', trace);
        text('pre', JSON.stringify(data.events, null, 2), trace);
        table(detail, [['Stufenbewertung nach #865', data.stageAssessment], ['Recognition / Ranking / Expert Review', 'NOT_EVALUATED'],
            ...run.artifacts.filter(a => ['PROOF_OBLIGATIONS', 'RULE_RADAR'].includes(a.role)).map(a => [a.role, a.status])]);
        text('p', data.claimBoundary, detail);
    }

    function renderDossier(parent, run, artifact, selected, select, edge) {
        if (selected.native) { renderNative(parent, run, artifact, selected, select, edge); return; }
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
            if (reference.status !== 'AVAILABLE' || !api.supports(reference.artifactSchema)) {
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
            if (state.status === 'NOT_RETAINED' && state.runId === active.workspace.runId) {
                text('span', 'Das im Manifest gebundene Kandidatenartefakt ist lokal noch nicht gespeichert. Importiere die Datei mit dem Hash ', status);
                text('code', reference.targetContentHash, status);
                text('span', '.', status);
                return;
            }
            if (state.status !== 'READY' || state.runId !== active.workspace.runId) {
                status.textContent = state.status === 'ERROR' ? 'Dossier konnte nicht geöffnet werden: ' + state.error : 'Gebundenes Kandidatenartefakt laden …';
                return;
            }
            status.textContent = 'Gespeicherter Beleg geöffnet · ' + state.runId;
            const native = state.artifact.content.schema === api.nativeSchema;
            const candidateId = active.candidate || (native
                ? (state.artifact.content.states.find(s => s.generationSequences.length)?.stateId || state.artifact.content.states[0].stateId)
                : state.artifact.content.discoveredBridge.stateHash);
            let selected;
            try { selected = api.selection(state.artifact, candidateId, active.edge || '', active.generation || ''); }
            catch (error) { status.textContent = error.message; status.className = 'run-error'; return; }
            if (!active.candidate) { select(candidateId, '', active.role || 'CANDIDATE_DOSSIERS'); return; }
            const list = text('div', undefined, host); list.className = 'dossier-candidate-list'; list.setAttribute('aria-label', 'Gespeicherte Kandidaten');
            const candidates = native ? state.artifact.content.states.filter(s => s.generationSequences.length).map(s => ({stateHash: s.stateId,
                expression: window.RegelsucheRunWorkspace.parseExactJson(s.canonicalStateJson).expression}))
                : state.artifact.content.search.states.filter(s => s.depth > 0);
            candidates.forEach(candidate => {
                const button = text('button', candidate.expression, list);
                button.type = 'button'; button.dataset.candidateId = candidate.stateHash;
                button.setAttribute('aria-pressed', String(candidate.stateHash === candidateId));
                button.addEventListener('click', () => {
                    select(candidate.stateHash, '');
                    document.querySelector('button[data-candidate-id="' + candidate.stateHash + '"]')?.focus();
                });
            });
            renderDossier(host, active.workspace, state.artifact, selected, select, active.edge || '');
            if (native ? selected.candidate.generationSequences.length : selected.bridge) {
                renderLiterature(host.querySelector('#candidateDossierDetail'), active.workspace.runId, candidateId);
            }
        }
        return Object.freeze({
            clear: () => { host = active = workspace = null; store.clear(); },
            render: (parent, state, selection) => {
                host = parent; active = state; select = selection;
                const changed = workspace !== state.workspace;
                workspace = state.workspace;
                const reference = workspace.artifacts.find(a => a.role === 'CANDIDATE_DOSSIERS');
                if (changed && reference.status === 'AVAILABLE' && api.supports(reference.artifactSchema)) store.open(workspace);
                else paint();
            }
        });
    }
    window.RegelsucheCandidateDossier = Object.freeze({createController});
})();
