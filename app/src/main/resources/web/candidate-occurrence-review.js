/* Presentation only. Candidate, edge and provenance correlation is owned by the dossier state adapter. */
(() => {
    'use strict';
    const labels = {RULE_RADAR: 'Regelradar', PROOF_OBLIGATIONS: 'Beweisverpflichtung', SEARCH_GRAPH: 'Graphkante', PATH_REPLAY: 'Replay'};
    function add(parent, tag, value) {
        const node = document.createElement(tag);
        if (value !== undefined) node.textContent = value;
        parent.appendChild(node);
        return node;
    }
    function button(parent, label, action) {
        const item = add(parent, 'button', label);
        item.type = 'button';
        item.addEventListener('click', action);
        return item;
    }
    function guards(parent, evaluations) {
        for (const value of evaluations) {
            const row = add(parent, 'p', value.requirement + ' · ' + value.result + (value.conflicting ? ' · Konflikt' : ''));
            row.dataset.reviewGuard = value.conflicting ? 'CONFLICT' : value.result;
            for (const witness of value.evidence) {
                add(parent, 'p', witness.evaluatorId + ' · ' + witness.result + ' · ' + witness.explanation);
            }
        }
    }
    function show(parent, data, role, raw) {
        const step = data.selectedStep, move = data.radar;
        add(parent, 'h4', labels[role] + ' · ' + step.rule);
        add(parent, 'p', step.source + ' → ' + step.target);
        add(parent, 'p', 'Vorkommen ' + (data.occurrence.length ? data.occurrence.join('.') : 'root') + ' · '
            + data.subtreeBefore + ' → ' + data.subtreeAfter);
        add(parent, 'p', 'Kandidat ' + data.candidateId + ' · Kante ' + data.edgeSequence);
        if (role === 'RULE_RADAR') {
            add(parent, 'p', data.radarStatus + (move ? ' · ' + move.candidateId + ' · ' + move.sourceReference : ''));
            add(parent, 'p', 'Schrittvergleich · AST-Knoten ' + data.sourceMetrics.astNodeCount + ' → ' + data.targetMetrics.astNodeCount
                + ' · Verschiedene Teilwerte ' + data.sourceMetrics.distinctSemanticValues + ' → ' + data.targetMetrics.distinctSemanticValues);
            const comparison = data.candidateComparison;
            const frontier = add(parent, 'details');
            add(frontier, 'summary', 'Quelle und Endkandidat · Rohmaße, bekannte Formen und ausgeführte Nachfolger');
            add(frontier, 'p', 'Beobachtete neue unbedingte Regelanwendungen · ' + (comparison.observedGainedRules.join(', ') || 'keine'));
            add(frontier, 'p', 'Nur an der Quelle beobachtet · ' + (comparison.observedLostRules.join(', ') || 'keine'));
            for (const [name, observation] of [['Quelle', comparison.source], ['Endkandidat', comparison.candidate]]) {
                add(frontier, 'h5', name + ' · ' + observation.expression);
                add(frontier, 'p', 'Syntax-ID ' + observation.syntaxHash + ' · Werte-DAG ' + observation.semanticValueDigest);
                add(frontier, 'p', 'Ausgeführte Nachfolger ' + observation.oneStepSuccessors.length + (observation.successorsTruncated ? ' · Budget ausgeschöpft' : ' · Inventarabfrage abgeschlossen'));
                add(frontier, 'p', 'Katalogtreffer ' + observation.recognition.matches.length + ' · ' + (observation.recognition.complete ? 'Scan abgeschlossen' : 'Scan unvollständig'));
                for (const match of observation.recognition.matches) add(frontier, 'p', match.structureId + ' · '
                    + (match.occurrencePath.childIndexes.join('.') || 'root') + ' · ' + match.provenance);
            }
            if (!data.guards.length) add(parent, 'p', 'Die angewandte Regel deklariert an diesem Vorkommen keine Voraussetzungen.');
            guards(parent, data.guards);
        } else if (role === 'PROOF_OBLIGATIONS') {
            const verification = add(parent, 'p', 'Gleichheitsprüfung · ' + data.verificationStatus);
            verification.dataset.verificationStatus = data.verificationStatus;
            if (data.verificationStatus === 'OUTSIDE_BOUNDED_POLYNOMIAL_FRAGMENT') {
                add(parent, 'p', 'Der Polynomprüfer unterstützt diesen Ausdruck nicht. Die gebundene Gleichheitsverpflichtung bleibt offen.');
            } else if (data.verificationStatus === 'UNSUPPORTED_TYPED_OBLIGATION') {
                add(parent, 'p', 'Für diesen Schritt konnte keine typisierte Gleichheitsverpflichtung erstellt werden.');
            }
            if (data.obligation) add(parent, 'p', 'Verpflichtung ' + data.obligation.contentHash + ' · Annahmen '
                + (data.obligationAssumptions.join(', ') || 'keine'));
            if (data.verification) add(parent, 'p', 'Backend ' + data.verification.backendId + ' · ' + data.verification.resultHash);
        }
        if (data.supplementalGuardReview) {
            add(parent, 'h4', 'Ergänzender Prüfkontext');
            guards(parent, [data.supplementalGuardReview]);
        }
        const more = add(parent, 'details');
        add(more, 'summary', 'Vollständigen Beleg mit Provenienz ansehen');
        add(more, 'pre', raw);
        const url = '/api/discovery-runs/' + data.runId.slice(7) + '/candidate-evidence/' + data.contentHash.slice(7);
        const reopen = add(more, 'a', 'Gespeicherten Originalbeleg erneut prüfen und öffnen');
        reopen.href = url;
        button(more, 'Beleg als JSON herunterladen', () => {
            const link = document.createElement('a');
            const url = URL.createObjectURL(new Blob([raw], {type: 'application/json'}));
            link.href = url;
            link.download = 'occurrence-' + data.contentHash.slice(7) + '.json';
            link.click();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
        });
    }
    function render(host, active, artifact, selection, select) {
        const panel = add(host, 'section');
        panel.id = 'candidateOccurrenceReview';
        panel.tabIndex = -1;
        add(panel, 'h3', 'Ausgewähltes Vorkommen prüfen');
        add(panel, 'p', 'Eine neue Prüfung verwendet den gespeicherten Schritt und die gebundene Regelrevision.');
        const navigation = add(panel, 'div');
        navigation.className = 'native-replay-controls';
        for (const [role, label] of Object.entries(labels)) {
            const control = button(navigation, label, () => {
                select(selection.candidateId, String(selection.edge), role, selection.generation);
                document.querySelector('#candidateOccurrenceReview button[data-review-role="' + role + '"]')?.focus();
            });
            control.dataset.reviewRole = role;
            control.setAttribute('aria-pressed', String(active.role === role));
        }
        if (!Object.hasOwn(labels, active.role) || !active.edge) return;
        const status = add(panel, 'p', 'Quellgebundenen Prüfbeleg laden …');
        status.setAttribute('role', 'status');
        const body = add(panel, 'div');
        let epoch = 0;
        async function review(guard = '', premises = []) {
            const token = ++epoch;
            body.replaceChildren();
            try {
                const response = await fetch('/api/discovery-runs/' + active.digest + '/candidate-evidence', {method: 'POST',
                    headers: {'Content-Type': 'application/json'}, body: JSON.stringify({candidateId: selection.candidateId, edge: selection.edge, guard, premises})});
                const raw = await response.text();
                if (token !== epoch || !panel.isConnected) return;
                if (!response.ok) throw new Error('HTTP ' + response.status + ' · ' + JSON.parse(raw).message);
                const data = window.RegelsucheCandidateDossierState.acceptReview(active.workspace, artifact, selection, JSON.parse(raw), response);
                status.textContent = 'Prüfbeleg ' + data.contentHash;
                show(body, data, active.role, raw);
            } catch (error) {
                if (token === epoch && panel.isConnected) status.textContent = 'Prüfung fehlgeschlagen · ' + error.message;
            }
        }
        review();
        if (active.role !== 'RULE_RADAR') return;
        const extra = add(panel, 'details');
        add(extra, 'summary', 'Zusätzlichen Annahmekontext untersuchen');
        add(extra, 'p', 'Der zusätzliche Kontext gilt nur für diese Guard-Abfrage. Die Gleichheitsprüfung verwendet die ursprünglichen Voraussetzungen.');
        const form = add(extra, 'form');
        const guard = add(add(form, 'label', 'Zu prüfende Voraussetzung '), 'input');
        guard.required = true;
        guard.maxLength = 128;
        guard.placeholder = 'x != 0';
        const premises = add(add(form, 'label', 'Prämissen · eine je Zeile '), 'textarea');
        premises.maxLength = 1032;
        const submit = add(form, 'button', 'Voraussetzung prüfen');
        submit.type = 'submit';
        form.addEventListener('submit', event => {
            event.preventDefault();
            review(guard.value, premises.value.split(/\r?\n/).map(s => s.trim()).filter(Boolean));
        });
    }
    window.RegelsucheOccurrenceReview = Object.freeze({render});
})();
