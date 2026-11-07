'use strict';

const element = id => document.getElementById(id);
const form = element('pairForm');
const result = element('pairResult');
const status = element('pairStatus');
const submit = element('pairSubmit');
const presets = {
    distance: { columns: 'x', summand: '(x_i-x_j)^2', rows: '2\n-1\n5' },
    weighted: { columns: 'x, w', summand: 'w_i*w_j*(x_i-x_j)^2', rows: '2 3\n-1 -2\n5 1' },
    product: { columns: 'x', summand: 'x_i*x_j', rows: '2\n-1\n5' }
};
const reasons = {
    ASYMMETRIC_SUMMAND: 'Der Summand muss beim Vertauschen der beiden Zeilen gleich bleiben.',
    UNBOUND_SYMBOL: 'Ein Symbol lässt sich keiner Spalte zuordnen. Verwende die Endungen _i und _j.',
    UNSUPPORTED_OPERATOR: 'Verwende Addition, Subtraktion, Multiplikation oder begrenzte Potenzen.',
    UNSUPPORTED_EXPRESSION: 'Der Ausdruck muss ein Polynom sein.',
    UNSUPPORTED_POWER: 'Potenzen brauchen einen ganzzahligen Exponenten zwischen 0 und 8.',
    FRACTIONAL_SOURCE: 'Verwende ganzzahlige Koeffizienten.',
    DEGREE_LIMIT: 'Der Ausdruck überschreitet den unterstützten Polynomgrad.',
    WORK_LIMIT: 'Die Herleitung hat ihr Arbeitsbudget ausgeschöpft.'
};

function clearResult() {
    result.hidden = true;
    status.textContent = '';
}

form.addEventListener('input', clearResult);
element('pairExample').addEventListener('change', event => {
    const preset = presets[event.target.value];
    element('pairColumns').value = preset.columns;
    element('pairSummand').value = preset.summand;
    element('pairRows').value = preset.rows;
    clearResult();
});

function formatted(value) {
    return BigInt(value).toLocaleString('de-DE');
}

function showResult(data) {
    element('pairOriginal').textContent = formatted(data.originalPairs);
    element('pairLinear').textContent = formatted(data.rowCount);
    element('pairExpanded').textContent = data.expandedSummand;
    element('pairFormula').textContent = data.formula;
    const sums = data.accumulators.map(({ name, rowProduct }) => {
        const item = document.createElement('li');
        item.textContent = name + ' = Summe von ' + rowProduct + ' über alle Zeilen';
        return item;
    });
    if (sums.length === 0) {
        const item = document.createElement('li');
        item.textContent = 'Der Plan braucht keine Zwischensummen.';
        sums.push(item);
    }
    element('pairAccumulators').replaceChildren(...sums);
    const sample = element('pairSample');
    sample.hidden = data.sampleResult === undefined && data.sampleUnavailable === undefined;
    if (data.sampleUnavailable) {
        sample.textContent = 'Die Beispielwerte konnten innerhalb der Auswertungsgrenzen nicht berechnet werden.';
    } else {
        sample.textContent = sample.hidden ? '' :
            'Exaktes Ergebnis für ' + formatted(data.sampleRows) + ' Beispielzeilen: ' + formatted(data.sampleResult);
    }
    element('pairWork').textContent =
        'Gezählte Arbeitseinheiten: ' + formatted(data.synthesisWork) + ' für die Herleitung, '
        + formatted(data.verificationWork) + ' für die Prüfung.';
    result.hidden = false;
}

form.addEventListener('submit', async event => {
    event.preventDefault();
    const input = {
        columns: element('pairColumns').value.split(',').map(name => name.trim()),
        summand: element('pairSummand').value,
        rowCount: element('pairCount').valueAsNumber
    };
    const rows = element('pairRows').value.trim();
    if (rows) {
        input.rows = rows.split(/\r?\n/).map(line => line.trim()).filter(Boolean)
            .map(line => line.split(/\s+/));
    }
    clearResult();
    status.textContent = 'Algorithmus wird hergeleitet und geprüft …';
    // Keep the displayed form and response bound to the same input.
    const controls = Array.from(form.elements);
    controls.forEach(control => { control.disabled = true; });
    try {
        const response = await fetch('/api/pair-reductions', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(input)
        });
        if (!response.ok) {
            throw new Error(await response.text());
        }
        const data = await response.json();
        if (data.status === 'VERIFIED') {
            showResult(data);
            status.textContent = 'Der Algorithmus ist symbolisch geprüft.';
        } else {
            status.textContent = reasons[data.reason] ?? ('Kein Plan innerhalb der unterstützten Grenzen (' + data.reason + ').');
        }
    } catch (error) {
        status.textContent = 'Die Berechnung konnte nicht geprüft werden. ' + error.message;
    } finally {
        controls.forEach(control => { control.disabled = false; });
        submit.focus();
    }
});
