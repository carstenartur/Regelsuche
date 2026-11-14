(() => {
    'use strict';
    const form = document.getElementById('searchForm');
    const input = form.elements.expression;
    const tools = document.getElementById('formulaTools');
    const preview = document.getElementById('formulaPreview');
    let request = 0;

    function invalidate() {
        request++;
        preview.replaceChildren();
    }

    function math(tag, ...children) {
        const element = document.createElementNS('http://www.w3.org/1998/Math/MathML', tag);
        element.append(...children);
        return element;
    }

    function grouped(value) {
        return math('mrow', math('mo', '('), value, math('mo', ')'));
    }

    function typeset(node) {
        const parts = node.children.map(typeset);
        switch (node.kind) {
            case 'NUMBER': {
                const [numerator, denominator] = node.value.split('/');
                return denominator ? math('mfrac', math('mn', numerator), math('mn', denominator)) : math('mn', numerator);
            }
            case 'VARIABLE': return math('mi', node.value);
            case 'FUNCTION': {
                const argumentsRow = math('mrow');
                parts.forEach((part, index) => {
                    if (index) argumentsRow.append(math('mo', ','));
                    argumentsRow.append(part);
                });
                return math('mrow', math('mi', node.value), grouped(argumentsRow));
            }
            case 'DIV': return math('mfrac', ...parts);
            case 'POW': return math('msup', node.children[0].kind === 'POW' ? grouped(parts[0]) : parts[0], parts[1]);
            case 'EQUATION': return math('mrow', parts[0], math('mo', '='), parts[1]);
            default: return grouped(math('mrow', parts[0], math('mo', node.value), parts[1]));
        }
    }

    function describe(node) {
        if (node.kind === 'NUMBER' || node.kind === 'VARIABLE') return node.value;
        const label = node.kind === 'FUNCTION' ? node.value : node.kind;
        return label + '(' + node.children.map(describe).join(', ') + ')';
    }

    input.addEventListener('input', invalidate);
    form.elements.type.addEventListener('change', invalidate);
    form.elements.runtimeProfile.addEventListener('change', () => {
        tools.hidden = Boolean(form.elements.runtimeProfile.value);
        invalidate();
    });

    tools.querySelectorAll('[data-formula]').forEach(button => button.addEventListener('click', () => {
        const start = input.selectionStart;
        const end = input.selectionEnd;
        const selected = input.value.slice(start, end);
        const operand = selected || 'x';
        const kind = button.dataset.formula;
        const replacement = kind === 'square' ? '(' + operand + ')^2'
            : kind === 'fraction' ? '(' + operand + ')/(1)' : '(' + operand + ')';
        input.setRangeText(replacement, start, end, 'end');
        if (kind === 'fraction') input.setSelectionRange(start + replacement.length - 2, start + replacement.length - 1);
        else if (!selected) input.setSelectionRange(start + 1, start + 2);
        input.dispatchEvent(new Event('input', {bubbles: true}));
        input.focus();
    }));

    document.getElementById('previewFormula').addEventListener('click', async () => {
        invalidate();
        const pending = request;
        const expression = input.value;
        const type = form.elements.type.value;
        const current = () => pending === request && input.value === expression && form.elements.type.value === type;
        preview.textContent = 'Formel wird gelesen …';
        try {
            const response = await fetch('/api/inspect/formula', {method: 'POST',
                headers: {'Content-Type': 'application/json'}, body: JSON.stringify({expression, type})});
            if (response.status === 422) {
                const diagnostic = await response.json();
                if (!current()) return;
                const select = document.createElement('button');
                select.type = 'button';
                select.textContent = 'Fehlerstelle im Ausdruck auswählen';
                select.addEventListener('click', () => {
                    input.focus();
                    input.setSelectionRange(diagnostic.startInclusive, diagnostic.endExclusive);
                });
                const range = '[' + diagnostic.startInclusive + ', ' + diagnostic.endExclusive + ')';
                preview.replaceChildren(diagnostic.message + ' · UTF-16 ' + range + ' ', select);
                return;
            }
            if (!response.ok) throw new Error(await response.text());
            const tree = await response.json();
            if (!current()) return;
            const ast = document.createElement('code');
            ast.textContent = describe(tree);
            const details = document.createElement('details');
            const title = document.createElement('summary');
            title.textContent = 'Interpretierter Syntaxbaum';
            details.append(title, ast);
            preview.replaceChildren(math('math', typeset(tree)), details);
        } catch (error) {
            if (current()) preview.textContent = error.message;
        }
    });
})();
