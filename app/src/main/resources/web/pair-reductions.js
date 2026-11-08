'use strict';

const element = id => document.getElementById(id);
const form = element('pairForm');
const result = element('pairResult');
const indexedResult = element('pairIndexedResult');
const status = element('pairStatus');
const submit = element('pairSubmit');
const presets = {
    distance: { columns: 'x', summand: '(x_i-x_j)^2', rows: '2\n-1\n5' },
    weighted: { columns: 'x, w', summand: 'w_i*w_j*(x_i-x_j)^2', rows: '2 3\n-1 -2\n5 1' },
    product: { columns: 'x', summand: 'x_i*x_j', rows: '2\n-1\n5' },
    band: {
        mode: 'windows', columns: 'x, u, v', summand: 'u_0*v_1*(x_0-x_1)^2',
        predicates: 'x - x in (0, 4]', width: 3, rows: '4 2 1\n2 1 3\n0 -3 2\n1 6 2'
    },
    chain: {
        mode: 'ordered', columns: 'x, w', summand: 'w_0*(x_1-x_0)*(x_2-x_1)',
        predicates: 'x - x in (, 0)\nx - x in (, 0)', rows: '1 2\n4 3\n2 5\n6 2'
    }
};
const intervalPattern = /^([_\p{L}][\p{L}\p{N}_]*)\s*-\s*([_\p{L}][\p{L}\p{N}_]*)\s+(nicht\s+)?in\s+(\[|\()\s*(-?[0-9]{1,256})?\s*,\s*(-?[0-9]{1,256})?\s*(\]|\))$/u;
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
    indexedResult.hidden = true;
    status.textContent = '';
}

function syncMode() {
    const mode = element('pairMode').value;
    element('pairRestrictions').hidden = mode === 'symmetric';
    element('pairWidthField').hidden = mode !== 'windows';
    element('pairWidth').disabled = mode !== 'windows';
    element('pairSyntax').hidden = mode !== 'symmetric';
    element('pairOrderedSyntax').hidden = mode === 'symmetric';
    element('pairSummand').setAttribute(
        'aria-describedby', mode === 'symmetric' ? 'pairSyntax' : 'pairOrderedSyntax');
}

form.addEventListener('input', clearResult);
element('pairMode').addEventListener('change', syncMode);
syncMode();
element('pairExample').addEventListener('change', event => {
    const preset = presets[event.target.value];
    element('pairColumns').value = preset.columns;
    element('pairSummand').value = preset.summand;
    element('pairRows').value = preset.rows;
    element('pairMode').value = preset.mode ?? 'symmetric';
    if (preset.predicates) element('pairPredicates').value = preset.predicates;
    if (preset.width) element('pairWidth').value = preset.width;
    syncMode();
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

function restrictions(text) {
    return text.split(/\r?\n/).map(line => line.trim()).filter(Boolean).map(line => {
        if (line === '*') return {};
        const match = intervalPattern.exec(line);
        if (!match || (!match[5] && !match[6])) {
            throw new Error('Ungültige Bedingung. Beispiel x - x in (0, 4].');
        }
        const [, left, right, outside, open, lower, upper, close] = match;
        const condition = { left, right, complement: Boolean(outside) };
        if (lower !== undefined) Object.assign(condition, { lower, lowerInclusive: open === '[' });
        if (upper !== undefined) Object.assign(condition, { upper, upperInclusive: close === ']' });
        return condition;
    });
}

function items(id, values) {
    element(id).replaceChildren(...values.map(value => {
        const item = document.createElement('li');
        item.textContent = value;
        return item;
    }));
}

function describeLookup(lookup) {
    if (lookup.access === 'SCALAR') return 'Laufende Summe über alle früheren Beiträge';
    const bounds = (lookup.lowerInclusive ? '[' : '(') + (lookup.lower ?? '−∞') + ', '
        + (lookup.upper ?? '+∞') + (lookup.upperInclusive ? ']' : ')');
    const structure = lookup.access === 'HASH' ? 'Hash-Index' : 'Fenwick-Baum';
    return structure + ' für ' + lookup.left + ' − ' + lookup.right
        + (lookup.complement ? ' außerhalb ' : ' in ') + bounds;
}

function showIndexedResult(data) {
    element('pairIndexedCandidates').textContent = formatted(data.originalCandidates);
    items('pairFactors', data.terms.length === 0 ? ['Der Summand ist null.'] : data.terms.map(term =>
        term.coefficient + ' · ' + term.factors.map((factor, slot) => '(' + factor + ') für Zeile ' + slot).join(' · ')));
    const lookups = data.lookups.map((lookup, slot) => 'Von Zeile ' + slot + ' zu Zeile ' + (slot + 1)
        + ' mit ' + describeLookup(lookup));
    if (data.eviction) lookups.push('Beim Entfernen der ältesten Zeile mit ' + describeLookup(data.eviction));
    items('pairLookups', lookups);
    element('pairSchedule').textContent = data.eviction
        ? 'Die älteste Zeile verlässt die Zusammenfassungen vor dem Abzug ihrer Paare. Die neue Zeile wird nach der Abfrage ihrer Paare eingetragen.'
        : 'Jede Präfixsumme enthält nur frühere Zeilen. Die aktuelle Zeile wird nach allen Abfragen eingetragen.';
    const sample = element('pairIndexedSample');
    sample.hidden = data.sampleSums === undefined && data.sampleUnavailable === undefined;
    if (data.sampleUnavailable) {
        sample.textContent = 'Die Beispielwerte überschreiten die Auswertungsgrenzen.';
    } else if (data.sampleSums) {
        sample.textContent = data.sampleSums.length === 0 ? 'Die Beispieldaten enthalten kein vollständiges Fenster.'
            : 'Exakte Beispielergebnisse ' + data.sampleSums.map(formatted).join(', ')
                + '. Gelesene Zeilen ' + formatted(data.sampleRows) + '. Indexzellen ' + formatted(data.indexAccesses) + '.';
    }
    element('pairIndexedWork').textContent = formatted(data.synthesisWork) + ' Arbeitseinheiten für die Herleitung und '
        + formatted(data.verificationWork) + ' für die Prüfung.';
    indexedResult.hidden = false;
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
    const controls = Array.from(form.elements).filter(control => !control.disabled);
    controls.forEach(control => { control.disabled = true; });
    try {
        const mode = element('pairMode').value;
        if (mode !== 'symmetric') input.restrictions = restrictions(element('pairPredicates').value);
        if (mode === 'windows') input.windowWidth = element('pairWidth').valueAsNumber;
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
            if (mode === 'symmetric') showResult(data);
            else showIndexedResult(data);
            status.textContent = 'Der Algorithmus ist symbolisch geprüft.';
        } else {
            status.textContent = data.reason === 'UNBOUND_SYMBOL' && mode !== 'symmetric'
                ? 'Ein Symbol lässt sich keiner Spalte zuordnen. Verwende die Endungen _0, _1 und so weiter.'
                : reasons[data.reason] ?? ('Kein Plan innerhalb der unterstützten Grenzen (' + data.reason + ').');
        }
    } catch (error) {
        status.textContent = 'Die Berechnung konnte nicht geprüft werden. ' + error.message;
    } finally {
        controls.forEach(control => { control.disabled = false; });
        submit.focus();
    }
});
