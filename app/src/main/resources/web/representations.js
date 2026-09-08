(() => {
    'use strict';
    const byId = id => document.getElementById(id);
    const text = (tag, value, className) => {
        const node = document.createElement(tag);
        if (value !== undefined) node.textContent = value;
        if (className) node.className = className;
        return node;
    };
    const relationNames = {
        SOLUTION_SET_EQUIVALENCE: 'Dieselbe Lösungsmenge',
        LINEAR_MAP_REPRESENTATION_EQUIVALENCE: 'Dieselbe lineare Abbildung',
        SPECTRAL_OR_SIMILARITY_RELATION: 'Spektrale Beziehung',
        BASIS_CHANGE_EQUIVALENCE: 'Expliziter Basiswechsel',
        MODEL_INTERPRETATION_CANDIDATE: 'Deklarierte Modellinterpretation'
    };
    const originNames = {
        DIRECT_MATRIX_REPRESENTATION: 'Exakte Matrixgleichung',
        REPEATED_SOURCE_LINEAR_FORMS: 'Wiederverwendete lineare Teilausdrücke',
        SOURCE_IDENTITY_PLUS_OPERATOR: 'Identität plus Operator aus der Quelle',
        CERTIFIED_BLOCK_DIAGONAL_EXPOSURE: 'Unabhängige Blöcke',
        DECLARED_MATRIX_EQUATION: 'Eingegebene Matrixgleichung',
        VISIBLE_ORDERED_FACTORS: 'Geordnete Faktoren aus dem Katalog',
        VISIBLE_CATALOG_MATRIX: 'Matrix aus dem Katalog',
        VISIBLE_IDENTITY_PLUS_OPERATOR: 'Identität plus bekannter Operator',
        DECLARED_OPERATOR_EXPRESSION: 'Deklarierter Operator-Ausdruck',
        LEFT_DISTRIBUTIVITY: 'Linke Distributivität', RIGHT_DISTRIBUTIVITY: 'Rechte Distributivität',
        ORDERED_ASSOCIATIVITY: 'Geordnete Assoziativität',
        LEFT_IDENTITY: 'Linke Identität', RIGHT_IDENTITY: 'Rechte Identität',
        VERIFIED_INVERSE_CANCELLATION: 'Kürzung mit geprüfter inverser Matrix'
    };
    const capabilityNames = {
        STAGED_MATRIX_VECTOR_APPLICATION: 'Faktoren schrittweise auf den Vektor anwenden',
        IDENTITY_PLUS_OPERATOR_APPLICATION: 'Identität und Operator getrennt anwenden',
        ORDERED_DISTRIBUTIVE_APPLICATION: 'Geordnete Produkte getrennt auswerten',
        EXACT_INVERSE_APPLICATION: 'Exakt geprüfte inverse Matrix anwenden',
        INDEPENDENT_BLOCK_APPLICATION: 'Unabhängige Blöcke getrennt anwenden'
    };
    let artifact;
    let busy = false;

    function inputKind() {
        const matrix = byId('representationInputKind').value === 'matrix';
        byId('representationRhsField').hidden = !matrix;
        byId('representationSourceLabel').textContent = matrix
            ? 'Matrix-Ausdruck aus dem Katalog (z. B. P*Q)' : 'Gleichungen, getrennt durch Semikolon oder Zeilenumbruch';
    }

    function request() {
        const matrix = byId('representationInputKind').value === 'matrix';
        return {
            schema: 'regelsuche.matrix-preparation-request/v1',
            equations: matrix ? '' : byId('representationSource').value,
            matrixExpression: matrix ? byId('representationSource').value : '',
            rightHandSide: matrix ? byId('representationRhs').value.split(';').map(s => s.trim()) : [],
            unknowns: byId('representationCoordinates').value.split(',').map(s => s.trim()),
            profile: byId('representationProfile').value,
            maxWorkUnits: Number(byId('representationBudget').value),
            catalog: JSON.parse(byId('representationCatalog').value),
            operatorExpressions: byId('representationOperators').value.split('\n').map(s => s.trim()).filter(Boolean),
            eigenvalueParameter: byId('representationEigenvalue').value.trim(),
            nonZeroVector: byId('representationNonzero').checked
        };
    }

    async function post(path, body) {
        const response = await fetch(path, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
        if (!response.ok) throw new Error(await response.text());
        return response.json();
    }

    async function run(action) {
        if (busy) return;
        busy = true; controls();
        byId('representationStatus').textContent = 'Exakte Nachweise werden berechnet …';
        try { await action(); }
        catch (error) { byId('representationStatus').textContent = 'Prüfung fehlgeschlagen: ' + error.message; }
        finally { busy = false; controls(); }
    }

    function controls() {
        byId('representationAnalyze').disabled = busy;
        byId('representationImport').disabled = busy;
        byId('representationReplay').disabled = busy || !artifact;
        byId('representationExport').disabled = busy || !artifact;
    }

    function facts(parent, entries) {
        const list = text('dl');
        entries.forEach(([name, value]) => list.append(text('dt', name), text('dd', value)));
        parent.append(list);
    }

    function matrix(parent, title, entries) {
        const wrapper = text('div', undefined, 'representation-matrix');
        const table = text('table'); table.append(text('caption', title));
        const body = text('tbody');
        entries.forEach(row => {
            const tr = text('tr'); row.forEach(value => tr.append(text('td', value))); body.append(tr);
        });
        table.append(body); wrapper.append(table); parent.append(wrapper);
    }

    function expression(parent, value) {
        if (value.entries) matrix(parent, value.name, value.entries);
        if (value.kind === 'IDENTITY') matrix(parent, 'Identität', Array.from({length: value.dimension}, (_, row) =>
            Array.from({length: value.dimension}, (_, column) => row === column ? '1' : '0')));
        if (value.inverseWitness) matrix(parent, 'Geprüfter Inversenkandidat', value.inverseWitness);
        ['left', 'right', 'operand', 'expression'].forEach(key => { if (value[key]) expression(parent, value[key]); });
        (value.blocks || []).forEach(block => expression(parent, block));
        if (value.rows) facts(parent, [['Zeilen zurück zur Quelle', value.rows.join(', ')], ['Spalten zurück zur Quelle', value.columns.join(', ')]]);
    }

    function rowMapping(parent, formation, leftHandSides) {
        const wrapper = text('div', undefined, 'representation-mapping');
        const table = text('table'); const head = text('tr');
        ['Quellzeile', 'Ursprüngliche skalare Gleichung', 'Exakter Replay'].forEach(value => head.append(text('th', value)));
        const thead = text('thead'); thead.append(head); table.append(thead);
        const tbody = text('tbody');
        formation.sourceRows.forEach(row => {
            const tr = text('tr');
            tr.append(text('td', String(row.sourceIndex)), text('td', row.equation), text('td', leftHandSides
                ? leftHandSides[row.sourceIndex] + ' = ' + formation.rightHandSide[row.sourceIndex] : 'Quellzeile exakt rekonstruiert'));
            tbody.append(tr);
        });
        table.append(tbody); wrapper.append(table); parent.append(wrapper);
    }

    function candidate(parent, value, formation) {
        const card = text('article', undefined, 'card representation-card representation-alternative'); card.dataset.origin = value.origin;
        card.append(text('span', 'Matrix und Replay verifiziert', 'representation-badge'), text('h3', originNames[value.origin] || value.origin));
        card.append(text('p', value.expression.display + ' · [' + formation.variableOrder.join(', ') + '] = [' + formation.rightHandSide.join(', ') + ']', 'formula'));
        facts(card, [['Beziehung', relationNames[value.formation.relation] || value.formation.relation],
            ['Variablenreihenfolge', formation.variableOrder.join(', ')], ['Dimension', formation.rowCount + ' × ' + formation.columnCount],
            ['Zusätzliche Annahmen', 'Keine; inverse Matrizen benötigen einen exakt geprüften Zeugen.'],
            ['Neu zugängliche Fähigkeiten', (value.newlyUnlockedCapabilities || []).map(name => capabilityNames[name] || name).join(', ')
                || 'Keine zusätzlich zur direkten Matrixanwendung'],
            ['Arbeitsaufwand', value.work.consumed + ' kanonische Einheiten']]);
        const buttons = text('div', undefined, 'representation-actions');
        const matrixButton = text('button', 'Matrixdarstellung'); matrixButton.type = 'button';
        const rowsButton = text('button', 'Skalare Zeilen und Replay'); rowsButton.type = 'button';
        buttons.append(matrixButton, rowsButton); card.append(buttons);
        const matrixPanel = text('div', undefined, 'representation-matrix-panel'); expression(matrixPanel, value.expression);
        const rowsPanel = text('div', undefined, 'representation-row-panel'); rowsPanel.hidden = true;
        rowMapping(rowsPanel, formation, value.replay.leftHandSides);
        matrixButton.addEventListener('click', () => { matrixPanel.hidden = false; rowsPanel.hidden = true; });
        rowsButton.addEventListener('click', () => { rowsPanel.hidden = false; matrixPanel.hidden = true; });
        card.append(matrixPanel, rowsPanel);
        const details = text('details'); details.append(text('summary', 'Herkunft und Zertifikate'));
        facts(details, [['Herkunft', value.provenance.join('\n')], ['Matrixzertifikat', value.formation.certificate], ['Replay-Zertifikat', value.replay.certificate]]);
        card.append(details); parent.append(card);
    }

    function render(value, replayed) {
        artifact = value;
        const evidence = value.evidence;
        const results = byId('representationResults'); results.replaceChildren();
        const accepted = evidence.candidates.filter(candidate => candidate.accepted);
        byId('representationStatus').textContent = (replayed ? 'Gesamter Replay bestätigt. ' : '')
            + (evidence.status === 'BUDGET_INCONCLUSIVE' ? 'Budget ausgeschöpft; die Suche bleibt unentschieden. ' : evidence.status + '. ')
            + accepted.length + ' Darstellungen mit geprüftem Replay.';
        const overview = text('article', undefined, 'card representation-card'); overview.append(text('h2', 'Original und erkannte Struktur'));
        overview.append(text('pre', evidence.request.equations || evidence.request.matrixExpression + ' · ['
            + evidence.request.unknowns.join(', ') + '] = [' + evidence.request.rightHandSide.join(', ') + ']'));
        const formation = evidence.formation;
        if (formation && formation.status === 'REPRESENTED') {
            facts(overview, [['Beziehung', relationNames[formation.relation] || formation.relation],
                ['Variablenreihenfolge', formation.variableOrder.join(', ')], ['Skalare Parameter', formation.parameters.join(', ') || 'Keine'],
                ['Zertifikat', formation.certificate], ['Physikalische Interpretation', 'Keine']]);
            matrix(overview, 'A', formation.coefficients); rowMapping(overview, formation);
        } else overview.append(text('p', formation ? formation.detailCode : evidence.detailCode));
        results.append(overview); accepted.forEach(candidateValue => candidate(results, candidateValue, formation));
        renderEigen(results, evidence.eigenproblem); renderSolving(results, evidence.solving);
        const rejected = evidence.candidates.filter(candidate => !candidate.accepted);
        if (rejected.length) {
            const details = text('details', undefined, 'card representation-card'); details.append(text('summary', rejected.length + ' nicht bestätigte Vorschläge'));
            rejected.forEach(candidateValue => details.append(text('p', candidateValue.expression.display + ': ' + candidateValue.detailCode))); results.append(details);
        }
    }

    function renderEigen(parent, eigen) {
        if (!eigen) return;
        const card = text('article', undefined, 'card representation-card representation-eigen'); card.append(text('h2', 'Eigenproblem erkennen'));
        if (eigen.status !== 'REPRESENTED') card.append(text('p', eigen.status + ': ' + eigen.detailCode));
        else {
            card.append(text('p', 'A · v = ' + eigen.eigenvalueParameter + ' · v', 'formula'));
            facts(card, [['Beziehung', relationNames[eigen.relation] || eigen.relation], ['Annahmen', eigen.assumptions.join(', ')],
                ['Variablenreihenfolge', eigen.variableOrder.join(', ')], ['Zertifikat', eigen.certificate], ['Modellinterpretation', 'Keine']]);
            matrix(card, 'Eigenproblem-Operator A', eigen.operator);
        }
        parent.append(card);
    }

    function renderSolving(parent, solving) {
        const card = text('article', undefined, 'card representation-card representation-solving'); card.append(text('h2', 'Anschließend ausgeführte Berechnungen'));
        if (!solving.rref && !solving.characteristicPolynomial) card.append(text('p', 'Für diesen Lauf wurde keine weiterführende Lösung berechnet.'));
        if (solving.rref) {
            const rref = solving.rref;
            card.append(text('h3', 'Exakte Zeilenreduktion'));
            facts(card, [['Status', rref.status], ['Beziehung', relationNames[rref.relation] || rref.relation],
                ['Klassifikation', rref.classification || rref.detailCode], ['Variablenreihenfolge', rref.variableOrder.join(', ')]]);
            if (rref.augmentedRows) matrix(card, 'Reduzierte erweiterte Matrix [A|b]', rref.augmentedRows);
            if (rref.particularSolution) card.append(text('p', 'Partikuläre Lösung: [' + rref.particularSolution.join(', ') + ']'));
            if (rref.nullspaceBasis && rref.nullspaceBasis.length) matrix(card, 'Nullraumbasis (je Zeile ein Basisvektor)', rref.nullspaceBasis);
            const operations = text('details'); operations.append(text('summary', 'Elementare Zeilenoperationen'));
            (rref.rowOperations || []).forEach(operation => operations.append(text('pre', operation))); card.append(operations);
        }
        if (solving.characteristicPolynomial) {
            card.append(text('h3', 'Charakteristisches Polynom'), text('p', solving.characteristicPolynomial.equation
                || solving.characteristicPolynomial.status + ': ' + solving.characteristicPolynomial.detailCode, 'formula'));
            facts(card, [['Beziehung', relationNames[solving.characteristicPolynomial.relation] || solving.characteristicPolynomial.relation]]);
        }
        parent.append(card);
    }

    const presets = {
        hidden: {source: '2*(x+y)+3*(x-y)=5;\n(x+y)+4*(x-y)=6'},
        blocks: {source: 'x+y=2;\nz=3;\nx-y=0', coordinates: 'z, y, x'},
        identity: {source: 'x+(2*x+y)=4;\ny+(x+3*y)=5'},
        eigen: {source: 'a*x+b*y=lambda*x;\nc*x+d*y=lambda*y', eigenvalue: 'lambda', nonzero: true},
        matrix: {source: 'P*Q', kind: 'matrix', catalog: [{name: 'P', entries: [['2','3'],['1','4']]}, {name: 'Q', entries: [['1','1'],['1','-1']]}]},
        operator: {source: '3*x+2*y=5;\nx+2*y=3', profile: 'EXPERIMENTAL_OPERATOR_V1', operators: 'A*(B+I(2))',
            catalog: [{name: 'A', entries: [['1','1'],['0','1']]}, {name: 'B', entries: [['1','0'],['1','1']]}]}
    };
    byId('representationExample').addEventListener('change', event => {
        const preset = presets[event.target.value];
        byId('representationSource').value = preset.source; byId('representationCoordinates').value = preset.coordinates || 'x, y';
        byId('representationProfile').value = preset.profile || 'SAFE_PREPARED_REPRESENTATION_V1'; byId('representationInputKind').value = preset.kind || 'scalar';
        byId('representationCatalog').value = JSON.stringify(preset.catalog || [], null, 2); byId('representationOperators').value = preset.operators || '';
        byId('representationEigenvalue').value = preset.eigenvalue || ''; byId('representationNonzero').checked = Boolean(preset.nonzero);
        byId('representationDeclarations').open = Boolean(preset.catalog || preset.eigenvalue); inputKind();
    });
    byId('representationInputKind').addEventListener('change', inputKind);
    byId('representationForm').addEventListener('submit', event => {event.preventDefault(); run(async () => render(await post('/api/representations', request()), false));});
    byId('representationReplay').addEventListener('click', () => run(async () => render(await post('/api/representations/replay', artifact), true)));
    byId('representationImport').addEventListener('change', event => run(async () => {
        const file = event.target.files[0]; if (!file) return;
        if (file.size > 4 * 1024 * 1024) throw new Error('Datei ist größer als 4 MiB.');
        render(await post('/api/representations/replay', JSON.parse(await file.text())), true);
    }));
    byId('representationExport').addEventListener('click', () => {
        const url = URL.createObjectURL(new Blob([JSON.stringify(artifact, null, 2) + '\n'], {type: 'application/json'}));
        const link = text('a'); link.href = url; link.download = 'matrix-representation-' + artifact.contentHash.slice(0, 12) + '.json';
        link.click(); setTimeout(() => URL.revokeObjectURL(url), 0);
    });
    inputKind();
})();
