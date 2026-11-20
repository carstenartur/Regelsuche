(() => {
    'use strict';
    const form = document.getElementById('searchForm');
    const input = form.elements.expression;
    const type = form.elements.type;
    const profile = form.elements.runtimeProfile;
    const tools = document.getElementById('formulaTools');
    const output = document.getElementById('formulaOutput');
    const status = document.getElementById('formulaStatus');
    const editor = document.getElementById('formulaEditor');
    const selector = document.getElementById('formulaPart');
    const replacement = document.getElementById('formulaReplacement');
    const apply = document.getElementById('applyFormulaEdit');
    const parts = new Map();
    let snapshot = null;
    let selected = null;
    let activeEdit = null;

    function showStatus(message, tone = '') {
        status.className = tone ? 'status ' + tone : 'status';
        status.textContent = message;
    }

    function cancelEdit() {
        activeEdit = null;
        apply.removeAttribute('aria-disabled');
    }

    function selectPart(key, focusReplacement = false) {
        cancelEdit();
        selected?.element.classList.remove('is-selected');
        selected = parts.get(key) || null;
        selected?.element.classList.add('is-selected');
        selector.value = key;
        replacement.value = selected?.source || '';
        replacement.disabled = apply.disabled = !selected;
        showStatus('');
        if (selected && focusReplacement) {
            replacement.focus();
            replacement.select();
        }
    }

    function invalidate() {
        snapshot = null;
        parts.clear();
        selector.replaceChildren();
        selectPart('');
        editor.hidden = true;
        output.replaceChildren();
    }

    function captureSource() {
        return {expression: input.value, type: type.value, profile: profile.value};
    }

    function isCurrent(source) {
        return source === snapshot && input.value === source.expression
            && type.value === source.type && profile.value === source.profile;
    }

    async function inspect(expression, type) {
        const response = await fetch('/api/inspect/formula', {method: 'POST',
            headers: {'Content-Type': 'application/json'}, body: JSON.stringify({expression, type})});
        if (response.status === 422) return {diagnostic: await response.json()};
        if (!response.ok) throw new Error(await response.text());
        return {tree: await response.json()};
    }

    function math(tag, ...children) {
        const element = document.createElementNS('http://www.w3.org/1998/Math/MathML', tag);
        element.append(...children);
        return element;
    }

    function grouped(value) {
        return math('mrow', math('mo', '('), value, math('mo', ')'));
    }

    function typeset(node, path = []) {
        const range = node.sourceRange;
        const key = range && range.startInclusive + ':' + range.endExclusive;
        if (range) parts.set(key, {
            range, path, source: snapshot.expression.slice(range.startInclusive, range.endExclusive)
        });
        const children = node.children.map((child, index) => typeset(child, [...path, index]));
        const element = typesetNode(node, children);
        if (range) {
            element.setAttribute('data-formula-part', key);
            parts.get(key).element = element;
        }
        return element;
    }

    function typesetNode(node, children) {
        switch (node.kind) {
            case 'NUMBER': {
                const [numerator, denominator] = node.value.split('/');
                return denominator ? math('mfrac', math('mn', numerator), math('mn', denominator)) : math('mn', numerator);
            }
            case 'VARIABLE': return math('mi', node.value);
            case 'FUNCTION': {
                const argumentsRow = math('mrow');
                children.forEach((part, index) => {
                    if (index) argumentsRow.append(math('mo', ','));
                    argumentsRow.append(part);
                });
                return math('mrow', math('mi', node.value), grouped(argumentsRow));
            }
            case 'DIV': return math('mfrac', ...children);
            case 'POW': return math('msup', node.children[0].kind === 'POW' ? grouped(children[0]) : children[0], children[1]);
            case 'EQUATION': return math('mrow', children[0], math('mo', '='), children[1]);
            case 'SUB':
                if (!node.children[0].sourceRange) return grouped(math('mrow', math('mo', '−'), children[1]));
                return grouped(math('mrow', children[0], math('mo', '−'), children[1]));
            default: return grouped(math('mrow', children[0], math('mo', node.value), children[1]));
        }
    }

    function describe(node) {
        if (node.kind === 'NUMBER' || node.kind === 'VARIABLE') return node.value;
        const label = node.kind === 'FUNCTION' ? node.value : node.kind;
        return label + '(' + node.children.map(describe).join(', ') + ')';
    }

    function renderPreview(tree) {
        const display = document.createElement('div');
        display.className = 'formula-display';
        display.tabIndex = 0;
        display.setAttribute('role', 'region');
        display.setAttribute('aria-label', 'Gesetzte Formel');
        display.append(math('math', typeset(tree)));
        display.addEventListener('click', event => {
            const occurrence = event.target.closest('[data-formula-part]');
            if (occurrence) selectPart(occurrence.getAttribute('data-formula-part'), true);
        });
        const ast = document.createElement('code');
        ast.textContent = describe(tree);
        const details = document.createElement('details');
        const title = document.createElement('summary');
        title.textContent = 'Interpretierter Syntaxbaum';
        details.append(title, ast);
        output.replaceChildren(display, details);
        selector.append(new Option('Bitte wählen', ''));
        for (const [key, part] of parts) {
            selector.append(new Option(part.source + ' · Zeichen ' + (part.range.startInclusive + 1), key));
        }
        editor.hidden = false;
    }

    function showDiagnostic(diagnostic) {
        const select = document.createElement('button');
        select.type = 'button';
        select.textContent = 'Fehlerstelle im Ausdruck auswählen';
        select.addEventListener('click', () => {
            input.focus();
            input.setSelectionRange(diagnostic.startInclusive, diagnostic.endExclusive);
        });
        const range = '[' + diagnostic.startInclusive + ', ' + diagnostic.endExclusive + ')';
        showStatus(diagnostic.message + ' · UTF-16 ' + range, 'error');
        output.replaceChildren(select);
    }

    async function applyEdit() {
        if (!selected || activeEdit || !snapshot || !isCurrent(snapshot)) return;
        const draft = replacement.value.trim();
        if (!draft || draft === selected.source) {
            showStatus(draft ? 'Der Teilausdruck ist unverändert.' : 'Gib einen Teilausdruck ein.');
            return;
        }
        const source = snapshot;
        const {range, path} = selected;
        const token = activeEdit = {};
        const current = () => activeEdit === token && isCurrent(source);
        const {startInclusive: start, endExclusive: end} = range;
        const inserted = path.length ? '(' + draft + ')' : draft;
        const proposal = source.expression.slice(0, start) + inserted + source.expression.slice(end);
        apply.setAttribute('aria-disabled', 'true');
        showStatus('Änderung wird geprüft …');
        try {
            // Verify the draft before wrapping it. Inserted parentheses can balance an invalid draft.
            if (path.length) {
                const {diagnostic} = await inspect(draft, 'TERM');
                if (!current()) return;
                if (diagnostic) throw new Error('Gib einen einzelnen Teilausdruck ein. ' + diagnostic.message);
            }
            const {tree, diagnostic} = await inspect(proposal, source.type);
            if (!current()) return;
            if (diagnostic) throw new Error(diagnostic.message);
            // The same AST position must own the whole insertion in the accepted source.
            const newRange = path.reduce((part, index) => part?.children[index], tree)?.sourceRange;
            if (newRange?.startInclusive !== start || newRange.endExclusive !== start + inserted.length) {
                throw new Error('Gib einen einzelnen Teilausdruck ein.');
            }
            input.value = proposal;
            input.dispatchEvent(new Event('input', {bubbles: true}));
            snapshot = captureSource();
            renderPreview(tree);
            showStatus('Änderung übernommen.', 'ok');
            selector.focus();
        } catch (error) {
            if (current()) showStatus('Änderung nicht übernommen. ' + error.message, 'error');
        } finally {
            if (activeEdit === token) cancelEdit();
        }
    }

    input.addEventListener('input', invalidate);
    type.addEventListener('change', invalidate);
    profile.addEventListener('change', () => {
        tools.hidden = Boolean(profile.value);
        invalidate();
    });
    tools.hidden = Boolean(profile.value);
    selector.addEventListener('change', () => selectPart(selector.value));
    replacement.addEventListener('input', () => {
        cancelEdit();
        showStatus('');
    });
    replacement.addEventListener('keydown', event => {
        if (event.key === 'Enter' && !event.isComposing) {
            event.preventDefault();
            applyEdit();
        }
    });
    apply.addEventListener('click', applyEdit);

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
        if (profile.value) return;
        const source = snapshot = captureSource();
        showStatus('Formel wird gelesen …');
        try {
            const {tree, diagnostic} = await inspect(source.expression, source.type);
            if (!isCurrent(source)) return;
            if (diagnostic) {
                showDiagnostic(diagnostic);
                return;
            }
            showStatus('');
            renderPreview(tree);
        } catch (error) {
            if (isCurrent(source)) showStatus(error.message, 'error');
        }
    });
})();
