const element = id => document.getElementById(id);
const write = (id, value) => { element(id).textContent = value; };
const number = value => BigInt(value).toLocaleString('de-DE').replace('-', '−');
const interval = bounds => '[' + bounds.map(number).join(', ') + ']';
const subscript = value => String(value).replace(/\d/g, digit => '₀₁₂₃₄₅₆₇₈₉'[digit]);
const vertex = (side, index) => side + subscript(index + 1);
const vertices = (side, indices) => '{' + indices.map(index => vertex(side, index)).join(', ') + '}';

function showHall(data) {
    const rows = Array.from({ length: data.leftCount }, (_, left) => {
        const neighbors = data.edges.filter(edge => edge[0] === left).map(edge => vertex('B', edge[1]));
        const row = document.createElement('tr');
        const candidate = document.createElement('th');
        candidate.scope = 'row';
        candidate.textContent = vertex('A', left);
        const blockers = document.createElement('td');
        blockers.textContent = neighbors.join(', ') || '∅';
        row.append(candidate, blockers);
        return row;
    });
    element('hallGraph').replaceChildren(...rows);
    write('hallCountDeficit', number(data.countDeficit));
    write('hallMatchingSize', number(data.matching.length));
    write('hallDeficit', number(data.hallDeficit));
    write('hallMatching', data.matching.map(([left, right]) => vertex('A', left) + '–' + vertex('B', right)).join(', '));
    write('hallWitness', vertices('A', data.witness));
    write('hallNeighbors', vertices('B', data.neighbors));
    write('hallWitnessDeficit', '|S| − |N(S)| = ' + number(data.witness.length)
        + ' − ' + number(data.neighbors.length) + ' = ' + number(data.hallDeficit));
}

function showMontgomery(data) {
    write('montgomerySourceReductions', number(data.sourceReductions));
    write('montgomeryTargetReductions', number(data.targetReductions));
    write('montgomeryProducts', number(data.productCount));
    write('montgomeryModulus', number(data.modulus));
    write('montgomeryRadix', '2^' + data.radixBits + ' = ' + number(2n ** BigInt(data.radixBits)));
    write('montgomeryInverse', number(data.inverse));
    const residue = ' · ' + data.residueBits + ' Bit';
    const accumulator = ' · ' + data.accumulatorBits + ' Bit';
    write('montgomeryLeftRange', interval(data.leftRange) + residue);
    write('montgomeryRightRange', interval(data.rightRange) + residue);
    write('montgomeryAccumulator', interval(data.targetAccumulatorRange) + accumulator);
    write('montgomerySourceOutput', interval(data.sourceOutputRange) + accumulator);
    write('montgomeryTargetOutput', interval(data.targetOutputRange) + residue);
    if (data.observation !== 'CANONICAL_RESIDUE') throw new Error('Unexpected observation');
    write('montgomeryObservation', 'Gleichheit nach floorMod mit q');
}

function showPairReduction(data) {
    write('pairLargeRows', number(data.evaluatedRows));
    write('pairLargeValue', number(data.value));
    write('pairLargeBaseline', number(data.baselinePairs));
    write('pairLargePrefixSize', number(data.prefixRows));
    write('pairLargePrefixPairs', number(data.prefixPairs));
    write('pairLargePrefixDirect', number(data.prefixDirectValue));
    write('pairLargePrefixReduced', number(data.prefixReducedValue));
}

const renderers = {
    hall: showHall,
    montgomery: showMontgomery,
    'pair-reduction': showPairReduction
};

for (const section of document.querySelectorAll('[data-certificate-demo]')) {
    const demo = section.dataset.certificateDemo;
    const button = section.querySelector('[data-demo-run]');
    const status = section.querySelector('[data-demo-status]');
    const output = section.querySelector('[data-demo-output]');
    const title = output.querySelector('[data-demo-title]');
    button.addEventListener('click', async () => {
        button.disabled = true;
        output.hidden = true;
        status.classList.remove('error');
        status.textContent = 'Wird berechnet und geprüft …';
        let focus = button;
        try {
            const response = await fetch('/api/certificate-demos/' + demo, { cache: 'no-store' });
            if (!response.ok) throw new Error('HTTP ' + response.status);
            const data = await response.json();
            if (data.status === 'VERIFIED') {
                renderers[demo](data);
                status.textContent = '';
                output.hidden = false;
                focus = title;
            } else {
                status.classList.add('error');
                status.textContent = 'Kein geprüfter Nachweis (' + data.reason + ').';
            }
        } catch {
            status.classList.add('error');
            status.textContent = 'Kein geprüfter Nachweis. Die Demo konnte nicht geladen werden.';
        } finally {
            button.disabled = false;
            focus.focus();
        }
    });
}
