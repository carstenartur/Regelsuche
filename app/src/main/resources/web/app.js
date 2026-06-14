/*
 * Regelsuche workbench — single-page application logic.
 *
 * Wires the tab UI in index.html to the existing REST endpoints:
 *   POST /api/search        — start a search
 *   GET  /api/paths         — list discovered paths
 *   GET  /api/explain/{id}  — render a path in SCHOOL/LATEX/STEPS form
 *   GET  /api/graph         — mermaid graph
 *   GET  /api/candidates    — rule candidates
 *   GET  /api/inventory     — reusable rules
 *
 * Defensive in the face of network errors; renders placeholders so the
 * UI is still usable when an endpoint is missing.
 */
(() => {
    window.__regelsucheDemoReady = false;
    window.__regelsucheMathRendered = false;
    window.__regelsucheGraphRendered = false;
    window.__regelsucheSemanticGraphRendered = false;
    window.__lastGraphRequestUrl = null;
    window.__lastGraphRequestParams = null;
    window.__lastGraphStats = null;
    window.__lastSelectedPathId = null;
    window.__regelsucheReplayReady = false;
    // Optional script loader for the interactive Cytoscape graph view.
    // KaTeX is loaded statically from index.html so cold page loads can
    // typeset math before the UI starts mutating the DOM.
    function loadCdnScript(src) {
        return new Promise((resolve) => {
            const s = document.createElement('script');
            s.src = src;
            s.async = true;
            s.crossOrigin = 'anonymous';
            s.referrerPolicy = 'no-referrer';
            s.onload = () => resolve(true);
            s.onerror = () => { window.__cytoscapeFailed = true; resolve(false); };
            document.head.appendChild(s);
        });
    }
    // Fire-and-forget; functions guard on `typeof cytoscape === 'function'`.
    loadCdnScript('vendor/cytoscape/cytoscape.min.js');

    function mathTargets(root) {
        if (!root) { return []; }
        const selector = '[data-math], .math, .latex';
        const targets = [];
        if (typeof root.matches === 'function' && root.matches(selector)) {
            targets.push(root);
        }
        return targets.concat(Array.from(root.querySelectorAll(selector)));
    }

    /**
     * Central math typesetter. Walks `root` for nodes carrying inline LaTeX
     * (`[data-math]`, `.math`, or the legacy `.latex` class) and renders
     * them via KaTeX when available, finally leaving the raw LaTeX visible inside a `<code>` block tagged
     * `math-fallback` so the formula remains legible without any CDN.
     *
     * All UI surfaces (replay, demo summary, search-graph inspector,
     * matrix preview, hints, proof panel, export preview) must call this
     * helper so the rendering path
     * stays uniform.
     */
    window.renderMath = function renderMath(root) {
        if (!root) { return; }
        window.__regelsucheMathRendered = false;
        const nodes = mathTargets(root);
        if (typeof window.renderMathInElement === 'function') {
            try {
                window.renderMathInElement(root, {
                    delimiters: [
                        { left: '$$', right: '$$', display: true },
                        { left: '$', right: '$', display: false },
                        { left: '\\(', right: '\\)', display: false },
                        { left: '\\[', right: '\\]', display: true }
                    ],
                    trust: false,
                    strict: 'ignore',
                    throwOnError: false
                });
                window.__regelsucheMathRendered = true;
                return;
            } catch (_) {
                // fall through to plain fallback below
            }
        }
        // No renderer available — surface the raw LaTeX in a <code> block so
        // the formula is still legible.
        nodes.forEach((node) => {
            if (node.classList.contains('math-fallback')) { return; }
            const raw = node.getAttribute('data-math') || node.textContent || '';
            node.innerHTML = '';
            const code = document.createElement('code');
            code.textContent = raw;
            node.appendChild(code);
            node.classList.add('math-fallback');
        });
        window.__regelsucheMathRendered = true;
    };

    /**
     * Stage 5 — layout-aware math renderer. Prefers the structured
     * {@code MathLayout} (when present) over the raw LaTeX string so the
     * front-end can apply CSS-grid-based aligned-row rendering, emit
     * diff CSS classes as plain DOM attributes (no KaTeX trust mode
     * required), and inject the AST-derived `aria-label` on the host
     * element for screen-reader accessibility.
     *
     * Falls back to {@link window.renderMath} on the raw LaTeX string
     * when no layout is available, so all existing call sites keep
     * working unchanged.
     */
    function appendMathLayoutLeaf(parent, node) {
        if (!parent || !node) { return; }
        if (node.kind === 'BREAK_HINT') {
            parent.appendChild(document.createTextNode(' '));
            return;
        }
        const span = document.createElement('span');
        const attrs = node.attributes || {};
        Object.entries(attrs).forEach(([key, value]) => {
            if (value != null && value !== '') {
                span.setAttribute(key, String(value));
            }
        });
        if (attrs.class) {
            span.className = attrs.class;
        }
        const text = node.text || '';
        const mathStr = node.kind === 'ARROW_LABEL'
            ? (text ? '$\\xrightarrow{' + text + '}$' : '$\\rightarrow$')
            : '$' + text + '$';
        span.setAttribute('data-math', mathStr);
        span.textContent = mathStr;
        parent.appendChild(span);
    }

    function appendMathLayoutNode(parent, node) {
        if (!parent || !node) { return; }
        if (node.kind === 'ALIGNED_ROW') {
            const row = document.createElement('div');
            row.className = 'math-aligned-row';
            (node.children || []).forEach((child) => appendMathLayoutLeaf(row, child));
            parent.appendChild(row);
            return;
        }
        appendMathLayoutLeaf(parent, node);
    }

    window.renderMathLayout = function renderMathLayout(layout, host) {
        if (!host) { return; }
        if (!layout || typeof layout !== 'object') {
            window.renderMath(host);
            return;
        }
        if (layout.aria) {
            host.setAttribute('aria-label', String(layout.aria));
        }
        const kind = layout.kind || 'INLINE';
        host.innerHTML = '';
        if (kind === 'ALIGNED' && Array.isArray(layout.nodes)) {
            host.classList.add('math-aligned-rows');
            layout.nodes.forEach((row, idx) => {
                if (!row || row.kind !== 'ALIGNED_ROW') { return; }
                appendMathLayoutNode(host, row);
                if (host.lastElementChild) {
                    host.lastElementChild.setAttribute('data-row-index', String(idx));
                }
            });
            window.renderMath(host);
            return;
        }
        host.classList.remove('math-aligned-rows');
        (layout.nodes || []).forEach((node) => appendMathLayoutNode(host, node));
        if (!host.childNodes.length) {
            const text = (layout.nodes || []).map((n) => n && n.text ? n.text : '').join('');
            const wrapped = kind === 'DISPLAY' ? ('$$' + text + '$$') : ('$' + text + '$');
            host.setAttribute('data-math', wrapped);
            host.textContent = wrapped;
        }
        window.renderMath(host);
    };

    const $ = (id) => document.getElementById(id);
    const setStatus = (msg, level = '') => {
        const el = $('searchStatus');
        el.textContent = msg || '';
        el.className = 'status' + (level ? ' ' + level : '');
    };

    /* ─── Tab navigation ─── */
    document.querySelectorAll('.tab').forEach((tab) => {
        tab.addEventListener('click', () => {
            document.querySelectorAll('.tab').forEach((t) => t.classList.remove('active'));
            document.querySelectorAll('.tab-panel').forEach((p) => p.classList.remove('active'));
            tab.classList.add('active');
            $('tab-' + tab.dataset.tab).classList.add('active');
        });
    });

    /* ─── Quick-load examples ─── */
    function loadExample(expression, type) {
        const form = $('searchForm');
        form.expression.value = expression;
        form.type.value = type || 'TERM';
    }
    $('loadExample-trig').addEventListener('click', () => loadExample('sin(x)^2 + cos(x)^2', 'TERM'));
    $('loadExample-log').addEventListener('click', () => loadExample('log(a*b)', 'TERM'));
    $('loadExample-eq').addEventListener('click', () => loadExample('2*x + 3 = 7', 'EQUATION'));

    /* ─── Demo buttons (Killer-App landing flow) ─── */
    /**
     * Reveal the full tab strip the first time the user actually starts a
     * search or clicks a demo. Until then only the workbench entry tab is
     * visible (see body.pre-search rules in style.css) so newcomers see a
     * single obvious flow instead of a feature wall.
     */
    function markSearchStarted() {
        if (document.body.classList.contains('pre-search')) {
            document.body.classList.remove('pre-search');
            document.body.dataset.preSearch = 'false';
        }
    }

    async function runDemo(demoId, btn) {
        window.__regelsucheDemoReady = false;
        window.__regelsucheGraphRendered = false;
        window.__regelsucheReplayReady = false;
        markSearchStarted();
        const status = $('demoStatus');
        const summary = $('demoSummary');
        const buttons = document.querySelectorAll('.demo-button');
        buttons.forEach((b) => (b.disabled = true));
        status.className = 'status';
        status.textContent = 'Starte Demo ' + demoId + ' …';
        summary.innerHTML = '';
        try {
            const response = await fetch('/api/demo/' + encodeURIComponent(demoId), { method: 'POST' });
            const raw = await response.text();
            if (!response.ok) {
                status.className = 'status error';
                status.textContent = 'Demo fehlgeschlagen (HTTP ' + response.status + '): ' + raw;
                return;
            }
            const data = JSON.parse(raw);
            status.className = 'status ok';
            status.textContent = 'Demo "' + data.title + '" abgeschlossen in '
                + (data.metrics && data.metrics.elapsedMillis) + ' ms.';
            renderDemoSummary(data);
            window.__regelsucheDemoReady = true;
            // Refresh the existing panels so users see graph/replay immediately.
            if (typeof loadPaths === 'function') { loadPaths().catch(() => {}); }
            if (typeof loadIdentities === 'function') { loadIdentities().catch(() => {}); }
            const graphBtn = $('reloadGraph');
            if (graphBtn) { graphBtn.click(); }
        } catch (ex) {
            status.className = 'status error';
            status.textContent = 'Netzwerkfehler: ' + ex;
            window.__regelsucheDemoReady = false;
        } finally {
            buttons.forEach((b) => (b.disabled = false));
        }
    }

    function renderMacroLearningSummary(data) {
        const sp = data.speedup || {};
        const stepsHtml = (data.steps || []).map((s) =>
            '<tr><td>' + s.expression + '</td><td>' + s.stepCount + '</td>'
            + '<td>' + s.elapsedMillis + ' ms</td>'
            + '<td>' + (s.confidenceScore || 0).toFixed(2) + '</td>'
            + '<td>' + s.learnedRulesActive + '</td></tr>').join('');
        $('demoSummary').innerHTML =
            '<div class="status ok demo-banner"><strong>System lernt eine Makroregel.</strong>'
            + ' Gelernte Regel wurde im letzten Lauf'
            + (data.usedLearnedRule ? ' angewendet.' : ' nicht angewendet.') + '</div>'
            + '<table class="demo-table"><thead><tr>'
            + '<th>Ausdruck</th><th>Schritte</th><th>Laufzeit</th><th>Konfidenz</th>'
            + '<th>aktive Makros</th></tr></thead><tbody>' + stepsHtml + '</tbody></table>'
            + '<p class="hint">Vorher (' + (sp.firstRunSteps || 0) + ' Schritte / '
            + (sp.firstRunMillis || 0) + ' ms) → nachher ('
            + (sp.lastRunSteps || 0) + ' Schritte / ' + (sp.lastRunMillis || 0) + ' ms).</p>';
    }

    function renderDemoSummary(data) {
        if (data && data.id === 'macro-learning') {
            renderMacroLearningSummary(data);
            return;
        }
        const m = data.metrics || {};
        const best = data.bestPath || {};
        const selected = data.selectedPath || best;
        window.__lastSelectedPathId = selected && selected.id ? selected.id : null;
        const identities = (data.identities || []).slice(0, 5);
        const targetReached = !!data.targetReached;
        const assumptions = data.assumptions || [];
        const stepDetails = Array.isArray(selected.stepDetails)
            ? selected.stepDetails
            : (Array.isArray(selected.steps) ? selected.steps : []);
        const stepCount = typeof selected.steps === 'number'
            ? selected.steps
            : stepDetails.length;
        const proofStatus = selected.proofStatus || selected.validationStatus || '';

        // Honest banner: identity recognised OR "no identity found, best path was…".
        // Stage 4+: the banner is now KaTeX-typeset via `mathSpan(…)` so the
        // headline mathematical statement is rendered as proper math instead
        // of raw ASCII (`^`, `*`, `/`). The plain-text expression is mirrored
        // into the `aria-label` so screen readers keep hearing the structure.
        const banner = targetReached
            ? '<div class="status ok demo-banner">'
                + '<strong>Identität erkannt:</strong> '
                + mathSpan(
                    selected.originalExpressionLatex,
                    selected.originalExpression || data.expression || '')
                + ' <span class="demo-banner-equals" aria-hidden="true">=</span> '
                + mathSpan(
                    selected.improvedExpressionLatex,
                    selected.improvedExpression || '')
                + '</div>'
            : '<div class="status warn demo-banner">'
                + '<strong>Keine Identität gefunden.</strong> Bester gefundener Umformungsweg: '
                + (selected.improvedExpression
                    ? mathSpan(
                        selected.originalExpressionLatex,
                        selected.originalExpression || data.expression || '')
                      + ' <span class="demo-banner-arrow" aria-hidden="true">→</span> '
                      + mathSpan(
                          selected.improvedExpressionLatex,
                          selected.improvedExpression)
                    : '<em>kein Verbesserungsweg im Suchbudget gefunden</em>')
                + '</div>';

        const proofTag = proofStatus
            ? renderProofStatusBadge(proofStatus)
            : '';
        const assumptionsBlock = assumptions.length
            ? '<h4>Annahmen</h4><ul>' + assumptions.map((a) =>
                '<li><code>' + escapeHtml(a) + '</code></li>').join('') + '</ul>'
            : '';
        const bestMove = stepDetails.length
            ? stepDetails[0]
            : null;
        // Stage 4+: best-move now shows a KaTeX-typeset before→after row
        // first, with the raw ASCII expressions retained inside <code>
        // blocks so screen readers / fallback environments and any
        // existing assertions on the textual content keep working.
        const bestMoveBlock = bestMove
            ? '<h4>Best Move</h4>'
                + '<p class="best-move-math">'
                + mathSpan(bestMove.beforeLatex, bestMove.beforeExpression || '')
                + ' <span class="best-move-arrow" aria-hidden="true">→</span> '
                + mathSpan(bestMove.afterLatex, bestMove.afterExpression || '')
                + '</p>'
                + '<p class="best-move-source"><code>' + escapeHtml(bestMove.beforeExpression || '')
                + ' → ' + escapeHtml(bestMove.afterExpression || '')
                + '</code> · Regel <code>' + escapeHtml(bestMove.ruleId || '') + '</code></p>'
            : '';

        // Stage 4+: the "Treffer (selectedPath)" cell is the headline result
        // line. We build it as raw HTML (KaTeX-typeset before/after + the
        // original ASCII inside a <code> block) and emit it via the
        // table-rows pipeline as a {html: …} marker that the renderer
        // below copies verbatim instead of escaping.
        const trefferHtml = selected.improvedExpression
            ? '<span class="treffer-math">'
                + mathSpan(selected.originalExpressionLatex,
                          selected.originalExpression || '')
                + ' <span class="treffer-arrow" aria-hidden="true">→</span> '
                + mathSpan(selected.improvedExpressionLatex,
                          selected.improvedExpression)
                + '</span>'
                + ' <code class="treffer-source">'
                + escapeHtml(selected.originalExpression || '')
                + ' → '
                + escapeHtml(selected.improvedExpression)
                + '</code>'
                + ' (' + stepCount + ' Schritte, Verbesserung '
                + escapeHtml(String(selected.totalImprovement || 0)) + ')'
            : '–';

        // Stage 4+: "Eingabe" cell also carries the user expression which may
        // contain `^`, `*` … so we render it through `mathSpan(…)` and
        // mirror the ASCII into a <code> block, matching the Treffer/best-move
        // pattern.
        const eingabeHtml = data.expression
            ? mathSpan(data.expressionLatex, data.expression)
                + ' <code class="eingabe-source">' + escapeHtml(data.expression) + '</code>'
            : '';

        const rows = [
            ['Eingabe', { html: eingabeHtml }],
            ['Profil', data.profile || ''],
            ['Treffer (selectedPath)', { html: trefferHtml }],
            ['Proof-Status', proofStatus || '–'],
            ['Erwartete Identität', data.expectedHighlight
                ? { html: '<code class="expected-highlight">'
                    + escapeHtml(data.expectedHighlight) + '</code>' }
                : ''],
            ['Knoten / Kanten', (m.nodes || 0) + ' / ' + (m.edges || 0)],
            ['Pfade entdeckt', m.pathsDiscovered || 0],
            ['Identitäten gefunden', m.identitiesFound || 0],
            ['Laufzeit', (m.elapsedMillis || 0) + ' ms']
        ];
        const tableRows = rows.map((r) => {
            const value = r[1];
            const cell = (value && typeof value === 'object' && typeof value.html === 'string')
                ? value.html
                : escapeHtml(String(value));
            return '<tr><th>' + escapeHtml(r[0]) + '</th><td>' + cell + '</td></tr>';
        }).join('');
        // Stage 4+: identities list keeps the <code> block (for screen
        // readers and the visual-regression rule "ASCII `^` only inside
        // <code>"), prefixed by KaTeX-typeset pattern endpoints.
        const idList = identities.length
            ? '<h4>Erkannte Identitäten</h4><ul>' + identities.map((i) =>
                '<li>'
                  + '<span class="identity-math">'
                  + mathSpan(i.leftPatternLatex, i.leftPattern || '')
                  + ' <span class="identity-arrow" aria-hidden="true">→</span> '
                  + mathSpan(i.rightPatternLatex, i.rightPattern || '')
                  + '</span> '
                  + '<code class="identity-source">' + escapeHtml(i.leftPattern || '')
                  + ' → ' + escapeHtml(i.rightPattern || '')
                  + '</code> · ' + renderProofStatusBadge(i.proofStatus) + '</li>').join('') + '</ul>'
            : '';
        const links = data.links || {};
        const linkList = [
            ['Bericht (Markdown)', links.reportMarkdown],
            ['Bericht (LaTeX)', links.reportLatex],
            ['Bericht (JSON)', links.reportJson],
            ['Suchgraph (Mermaid)', links.searchGraphMermaid],
            ['Suchgraph (GraphML)', links.searchGraphGraphMl],
            ['Bundle (.zip)', links.reportBundleZip]
        ].filter((l) => l[1])
            .map((l) => '<a class="export-button" href="' + l[1] + '" target="_blank">' + escapeHtml(l[0]) + '</a>')
            .join(' ');
        $('demoSummary').innerHTML =
            banner
            + renderMathDomainPanel(data)
            + (proofTag ? '<p>Proof-Status des selektierten Pfades: ' + proofTag + '</p>' : '')
            + bestMoveBlock
            + assumptionsBlock
            + '<table>' + tableRows + '</table>'
            + renderProofBridgePanel(data)
            + idList
            + '<div class="demo-actions">' + linkList + '</div>';
        wireProofBridgeButton(data);
        window.renderMath($('demoSummary'));
    }

    /**
     * Math-domain panels rendered inline in the demo summary:
     *  - math-equation: Lösungsweg in Schulform (one row per step),
     *  - math-inequality: Hinweis "Vergleichszeichen wurde gedreht" when comparatorFlipped,
     *  - math-derivative: Regelkarte mit der angewendeten Ableitungsregel,
     *  - math-matrix: bmatrix-Vorschau der LaTeX-Ein- und -Ausgabe.
     */
    function renderMathDomainPanel(data) {
        if (!data || typeof data.id !== 'string' || !data.id.startsWith('math-')) {
            return '';
        }
        const selected = data.selectedPath || data.bestPath || {};
        const steps = selected.stepDetails || [];
        let html = '';
        if (data.id === 'math-equation') {
            const rows = steps.map((s, i) =>
                '<tr><td>' + (i + 1) + '.</td>'
                + '<td><code>' + escapeHtml(s.beforeExpression || '') + '</code></td>'
                + '<td><code>' + escapeHtml(s.afterExpression || '') + '</code></td>'
                + '<td><code>' + escapeHtml(s.ruleId || '') + '</code></td></tr>').join('');
            html += '<div class="math-domain-panel math-equation-panel">'
                + '<h4>Lösungsweg (Schulform)</h4>'
                + '<table class="math-equation-steps"><thead><tr>'
                + '<th>#</th><th>vorher</th><th>nachher</th><th>Regel</th>'
                + '</tr></thead><tbody>' + rows + '</tbody></table></div>';
        }
        if (data.id === 'math-inequality' && data.comparatorFlipped) {
            html += '<div class="status error math-domain-panel math-inequality-panel">'
                + '<strong>⚠️ Vergleichszeichen wurde gedreht.</strong> '
                + 'Multiplikation/Division mit einem negativen Faktor dreht '
                + 'das Vergleichszeichen um.</div>';
        }
        if (data.id === 'math-derivative') {
            const card = steps.map((s) => derivativeRuleLabel(s.ruleId || ''))
                .find((c) => c);
            if (card) {
                html += '<div class="math-domain-panel math-derivative-panel">'
                    + '<h4>Angewandte Regel: ' + escapeHtml(card.title) + '</h4>'
                    + '<p>' + escapeHtml(card.body) + '</p></div>';
            }
        }
        if (data.id === 'math-matrix') {
            const inputLatex = data.inputLatex || '';
            const resultLatex = data.resultLatex || '';
            if (inputLatex || resultLatex) {
                html += '<div class="math-domain-panel math-matrix-panel">'
                    + '<h4>Matrix-Vorschau (bmatrix)</h4>'
                    + '<div class="math" data-math="$' + escapeHtml(inputLatex) + '$">$' + escapeHtml(inputLatex) + '$</div>'
                    + '<div class="hint">→</div>'
                    + '<div class="math" data-math="$' + escapeHtml(resultLatex) + '$">$' + escapeHtml(resultLatex) + '$</div>'
                    + '</div>';
            }
        }
        return html;
    }

    /**
     * "Proof prüfen" button for math-domain demos. It POSTs the selected
     * path's first→last expressions to /api/proof-bridge and renders the
     * full execution result (prover status, stdout, stderr, exit code, generated script).
     */
    function renderProofBridgePanel(data) {
        if (!data || typeof data.id !== 'string' || !data.id.startsWith('math-')) {
            return '';
        }
        const selected = data.selectedPath || data.bestPath || {};
        const left = selected.originalExpression || data.expression || '';
        const right = selected.improvedExpression || '';
        if (!left || !right) {
            return '';
        }
        return '<div class="proof-bridge-panel">'
            + '<h4>Proof-Bridge</h4>'
            + '<p class="hint">Generiert ein Lean-/SMT-Skript und führt es aus, '
            + 'sofern ein Prover installiert ist. <strong>FORMALLY_PROVED</strong> '
            + 'wird ausschließlich gesetzt, wenn der Prover erfolgreich war.</p>'
            + '<button id="proofBridgeRun" class="primary" '
            + 'data-left="' + escapeHtml(left) + '" '
            + 'data-right="' + escapeHtml(right) + '">Proof prüfen</button>'
            + '<div id="proofBridgeResult"></div>'
            + '</div>';
    }

    function wireProofBridgeButton(data) {
        const btn = document.getElementById('proofBridgeRun');
        if (!btn) return;
        btn.addEventListener('click', async () => {
            const target = document.getElementById('proofBridgeResult');
            const left = btn.dataset.left || '';
            const right = btn.dataset.right || '';
            btn.disabled = true;
            target.innerHTML = '<div class="hint">Prover wird aufgerufen …</div>';
            try {
                const assumptions = (data.assumptions || []).map((a) => String(a));
                const response = await fetch('/api/proof-bridge', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        leftPattern: left,
                        rightPattern: right,
                        assumptions: assumptions,
                        tool: 'lean4'
                    })
                });
                const raw = await response.text();
                if (!response.ok) {
                    target.innerHTML = '<div class="status error">HTTP '
                        + response.status + ': ' + escapeHtml(raw) + '</div>';
                    return;
                }
                const result = JSON.parse(raw);
                const formallyProved = result.proofStatus === 'FORMALLY_PROVED';
                const statusClass = formallyProved ? 'ok'
                    : (result.proverStatus === 'PROVER_FAILED' ? 'error' : 'warn');
                target.innerHTML = '<div class="status ' + statusClass + ' proof-bridge-summary">'
                    + '<strong>Proof-Status:</strong> '
                    + escapeHtml(result.proofStatus || '–')
                    + ' · <strong>Prover-Status:</strong> '
                    + escapeHtml(result.proverStatus || '–')
                    + ' · <strong>Exit-Code:</strong> '
                    + (result.exitCode != null ? result.exitCode : '–')
                    + ' · <strong>Laufzeit:</strong> '
                    + (result.elapsedMillis != null ? result.elapsedMillis + ' ms' : '–')
                    + '</div>'
                    + (result.stdout ? '<details open><summary>stdout</summary>'
                        + '<pre>' + escapeHtml(result.stdout) + '</pre></details>' : '')
                    + (result.stderr ? '<details><summary>stderr</summary>'
                        + '<pre>' + escapeHtml(result.stderr) + '</pre></details>' : '')
                    + (result.artifact ? '<details><summary>generiertes Skript ('
                        + escapeHtml(result.artifactTool || result.tool || '')
                        + ')</summary><pre>' + escapeHtml(result.artifact)
                        + '</pre></details>' : '');
            } catch (ex) {
                target.innerHTML = '<div class="status error">Netzwerkfehler: '
                    + escapeHtml(String(ex)) + '</div>';
            } finally {
                btn.disabled = false;
            }
        });
    }

    /* ─── Proof-status legend (loaded lazily, cached) ─── */
    const proofStatusLegend = {};
    let proofStatusLoaded = null;
    function ensureProofStatusLegend() {
        if (proofStatusLoaded) { return proofStatusLoaded; }
        proofStatusLoaded = fetch('/api/proof-status')
            .then((r) => (r.ok ? r.json() : null))
            .then((data) => {
                if (data && Array.isArray(data.statuses)) {
                    data.statuses.forEach((s) => {
                        proofStatusLegend[s.status || s.name] =
                            s.descriptionDe || s.shortDescription || s.descriptionEn
                                || s.description || s.label || (s.status || s.name);
                    });
                }
            })
            .catch(() => {});
        return proofStatusLoaded;
    }
    ensureProofStatusLegend();

    function renderProofStatusBadge(status) {
        if (!status) { return ''; }
        const tooltip = proofStatusLegend[status] || status;
        return '<span class="proof-badge proof-' + status.toLowerCase()
            + '" title="' + escapeHtml(tooltip) + '">' + escapeHtml(status) + '</span>';
    }

    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, (c) => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        }[c]));
    }

    /**
     * Builds the HTML for an inline math span the central
     * {@link window.renderMath} typesetter will pick up via
     * {@code [data-math]}.
     *
     * <p>The returned markup carries an {@code aria-label} with the
     * plain-text expression so screen readers still hear the structure
     * even when KaTeX is unavailable (the {@code .math} node is also the
     * fallback's {@code <code>}-wrapping anchor, so the raw LaTeX stays
     * visible in CDN-less environments).</p>
     *
     * @param {string} latex   the LaTeX source (preferred form, e.g. {@code x^{2}})
     * @param {string} [ascii] the original ASCII expression for accessibility / fallback
     * @returns {string} HTML for a {@code <span class="math">} element
     */
    function mathSpan(latex, ascii) {
        const tex = (latex == null ? '' : String(latex)).trim();
        const plain = (ascii == null ? '' : String(ascii)).trim();
        if (!tex && !plain) { return ''; }
        const body = tex || plain;
        const aria = plain || tex;
        return '<span class="math" data-math="$' + escapeHtml(body) + '$"'
            + ' aria-label="' + escapeHtml(aria) + '">'
            + '$' + escapeHtml(body) + '$</span>';
    }

    document.querySelectorAll('.demo-button').forEach((btn) => {
        btn.addEventListener('click', () => runDemo(btn.dataset.demo, btn));
    });

    /* ─── Search form ─── */
    $('searchForm').addEventListener('submit', async (event) => {
        event.preventDefault();
        markSearchStarted();
        const form = event.target;
        const domains = Array.from(form.querySelectorAll('input[name="domain"]:checked')).map((c) => c.value);
        const payload = {
            expression: form.expression.value,
            type: form.type.value,
            profile: form.profile.value,
            domains: domains
        };
        if (form.goal && form.goal.value) {
            payload.goal = form.goal.value;
        }
        setStatus('Suche läuft …');
        $('searchOutput').textContent = '';
        try {
            const response = await fetch('/api/search', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const text = await response.text();
            $('searchOutput').textContent = text;
            if (response.ok) {
                setStatus('Fertig. ' + text.length + ' Bytes Antwort.', 'ok');
                // Refresh paths/candidates in the background.
                loadPaths().catch(() => {});
            } else {
                setStatus('Fehler (HTTP ' + response.status + ')', 'error');
            }
        } catch (ex) {
            setStatus('Netzwerkfehler: ' + ex, 'error');
        }
    });

    /* ─── Paths tab ─── */
    async function loadPaths() {
        const out = $('pathsList');
        out.innerHTML = '<div class="hint">Lade …</div>';
        try {
            const response = await fetch('/api/paths');
            const text = await response.text();
            renderPaths(text);
        } catch (ex) {
            out.innerHTML = '<div class="status error">Fehler: ' + ex + '</div>';
        }
    }

    function renderPaths(raw) {
        const out = $('pathsList');
        let parsed = null;
        try { parsed = JSON.parse(raw); } catch (ex) { /* fallthrough */ }
        const list = parsed && (parsed.transformations || parsed.paths);
        if (!list || !list.length) {
            out.innerHTML = '<div class="hint">Keine Pfade verfügbar. Starte zuerst eine Suche.</div>';
            return;
        }
        out.innerHTML = '';
        list.forEach((path) => {
            const div = document.createElement('div');
            div.className = 'list-item clickable';
            const title = document.createElement('h4');
            const target = path.improvedExpression || path.simplifiedExpression || '?';
            title.textContent = (path.originalExpression || '?') + ' → ' + target;
            div.appendChild(title);
            const meta = document.createElement('div');
            meta.className = 'meta';
            const depth = (path.steps && path.steps.length) || path.depth || 0;
            const improvement = path.totalImprovement != null ? path.totalImprovement : (path.improvement || 0);
            meta.textContent = 'Tiefe ' + depth
                + ' · Verbesserung ' + improvement
                + (path.id ? ' · id=' + path.id : '');
            div.appendChild(meta);
            if (path.id) {
                div.addEventListener('click', () => explainPath(path.id));
            }
            out.appendChild(div);
        });
    }

    async function explainPath(id) {
        const form = $('pathFormat').value || 'SCHOOL';
        const out = $('pathDetail');
        out.textContent = 'Lade Erklärung (' + form + ') …';
        try {
            const response = await fetch('/api/explain/' + encodeURIComponent(id) + '?form=' + form);
            out.textContent = await response.text();
        } catch (ex) {
            out.textContent = 'Fehler: ' + ex;
        }
    }

    $('reloadPaths').addEventListener('click', loadPaths);

    /* ─── Graph tab ─── */
    $('reloadGraph').addEventListener('click', async () => {
        window.__regelsucheGraphRendered = false;
        window.__regelsucheSemanticGraphRendered = false;
        const out = $('graphOutput');
        const canvas = $('graphCanvas');
        const inspector = $('graphInspector');
        const filter = $('graphFilter') && $('graphFilter').value || '';
        const interactive = $('graphInteractive') && $('graphInteractive').checked;
        const mode = $('graphViewMode') && $('graphViewMode').value || 'semantic';
        const showMacroSteps = $('showMacroSteps') && $('showMacroSteps').value || 'compact';
        const showLowSignal = !!($('showLowSignal') && $('showLowSignal').checked);
        const showAlternatives = !($('showAlternatives') && !$('showAlternatives').checked);
        const showVariants = !!($('showVariants') && $('showVariants').checked);
        const filterQuery = filter ? ('?filter=' + encodeURIComponent(filter)) : '';
        const semanticQuery = '?mode=' + encodeURIComponent(mode)
            + '&showMacroSteps=' + encodeURIComponent(showMacroSteps)
            + '&showLowSignal=' + encodeURIComponent(String(showLowSignal))
            + '&showAlternatives=' + encodeURIComponent(String(showAlternatives))
            + '&showVariants=' + encodeURIComponent(String(showVariants))
            + (window.__lastSelectedPathId
                ? '&pathId=' + encodeURIComponent(window.__lastSelectedPathId)
                : '');
        out.textContent = 'Lade …';
        if (canvas) canvas.style.display = 'none';
        if (inspector) { inspector.style.display = 'none'; inspector.innerHTML = ''; }
        try {
            if (interactive && mode !== 'raw' && typeof cytoscape === 'function' && !window.__cytoscapeFailed) {
                const semanticGraphUrl = '/api/search-graph/semantic' + semanticQuery;
                window.__lastGraphRequestUrl = semanticGraphUrl;
                window.__lastGraphRequestParams = {
                    mode: mode,
                    showMacroSteps: showMacroSteps,
                    showLowSignal: showLowSignal,
                    showAlternatives: showAlternatives,
                    showVariants: showVariants,
                    pathId: window.__lastSelectedPathId || ''
                };
                const response = await fetch(semanticGraphUrl);
                const data = await response.json();
                window.__lastGraphStats = Object.assign(
                    { semanticNodeCount: ((data && data.nodes) || []).length },
                    (data && data.stats) || {});
                renderSemanticGraph(data);
                const mermaidResp = await fetch('/api/exports/search-graph-semantic.mmd' + semanticQuery);
                out.textContent = (await mermaidResp.text());
                return;
            }
            if (interactive && mode === 'raw' && typeof cytoscape === 'function' && !window.__cytoscapeFailed) {
                const response = await fetch('/api/search-graph' + filterQuery);
                const data = await response.json();
                renderCytoscape(data);
                const mermaidResp = await fetch('/api/exports/search-graph.mmd' + filterQuery);
                out.textContent = await mermaidResp.text();
                return;
            }
            const url = mode === 'raw'
                ? ('/api/exports/search-graph.mmd' + filterQuery)
                : ('/api/exports/search-graph-semantic.mmd' + semanticQuery);
            const response = await fetch(url);
            out.textContent = await response.text();
        } catch (ex) {
            out.textContent = 'Fehler: ' + ex;
            window.__regelsucheGraphRendered = false;
        }
    });

    window.renderSemanticGraph = function renderSemanticGraph(graph, options) {
        const rendered = renderCytoscape(graph, options);
        renderSemanticGraphBadge(graph);
        window.__regelsucheSemanticGraphRendered = window.__regelsucheGraphRendered === true;
        return rendered;
    };
    window.expandSemanticNode = function expandSemanticNode(nodeId) {
        const inspector = $('graphInspector');
        if (inspector) {
            inspector.style.display = 'block';
            inspector.innerHTML = '<div><strong>expandSemanticNode:</strong> ' + escapeHtml(String(nodeId)) + '</div>';
        }
    };
    window.expandSemanticEdge = function expandSemanticEdge(edgeId) {
        const inspector = $('graphInspector');
        if (inspector) {
            inspector.style.display = 'block';
            inspector.innerHTML = '<div><strong>expandSemanticEdge:</strong> ' + escapeHtml(String(edgeId)) + '</div>';
        }
    };
    window.toggleLowSignal = function toggleLowSignal(show) {
        if ($('showLowSignal')) {
            $('showLowSignal').checked = !!show;
        }
    };
    window.toggleAlternatives = function toggleAlternatives(show) {
        if ($('showAlternatives')) {
            $('showAlternatives').checked = !!show;
        }
    };

    function renderCytoscape(graph) {
        const canvas = $('graphCanvas');
        const inspector = $('graphInspector');
        if (!canvas || typeof cytoscape !== 'function') {
            return;
        }
        canvas.style.display = 'block';
        configureGraphCanvas(canvas, graph);
        canvas.innerHTML = '';
        canvas.setAttribute('data-graph-math-edges', 'true');
        const elements = [];
        (graph.nodes || []).forEach(n => elements.push({
            data: {
                id: n.id,
                label: n.expression || n.representativeExpression || n.canonicalExpression || n.id,
                payload: n
            }
        }));
        (graph.edges || []).forEach(e => elements.push({
            data: {
                id: e.from + '->' + e.to + ':' + e.ruleId,
                source: e.from,
                target: e.to,
                label: e.ruleId,
                kind: e.kind || '',
                payload: e
            }
        }));
        const cy = cytoscape({
            container: canvas,
            elements: elements,
            style: [
                // Stage 4: node labels are rendered as KaTeX HTML overlays
                // (see graphMathOverlay) so the in-canvas label is hidden
                // by setting its color to transparent. We still keep the
                // text-content available for screen readers via the
                // overlay's aria-label.
                { selector: 'node', style: { 'label': '', 'font-size': 10, 'background-color': '#3b82f6', 'color': '#fff', 'text-valign': 'center', 'text-halign': 'center' } },
                { selector: 'node[?payload.isBest]', style: { 'background-color': '#10b981' } },
                { selector: 'node[?payload.onMainPath]', style: { 'background-color': '#10b981' } },
                { selector: 'node[?payload.isDeadEnd]', style: { 'background-color': '#9ca3af' } },
                { selector: 'edge', style: { 'label': '', 'font-size': 8, 'curve-style': 'bezier', 'target-arrow-shape': 'triangle' } },
                { selector: 'edge[?payload.lowSignal]', style: { 'line-color': '#d1d5db', 'target-arrow-color': '#d1d5db', 'opacity': 0.6 } },
                { selector: 'edge[kind = "MAIN_STEP"]', style: { 'line-color': '#0ea5e9', 'target-arrow-color': '#0ea5e9', 'width': 3 } },
                { selector: 'edge[kind = "MACRO_MOVE"]', style: { 'line-color': '#8b5cf6', 'target-arrow-color': '#8b5cf6', 'width': 3 } }
            ],
            layout: computeGraphLayout(graph)
        });
        cy.on('tap', 'node', evt => showInspector(evt.target.data('payload')));
        cy.on('tap', 'edge', evt => showInspector(evt.target.data('payload')));
        // Stage 4: expose for browser tests; harmless in production.
        window.__cyForTests = cy;
        // Stage 4: install the KaTeX HTML-overlay layer over the cy
        // canvas. The overlay re-projects each node's bounding box back
        // to container coordinates on layoutstop / pan / zoom / position
        // so the math nodes track the underlying Cytoscape positions
        // smoothly via CSS transitions.
        graphMathOverlay.install(cy, canvas);
        window.__regelsucheGraphRendered = true;
        if (inspector) {
            inspector.style.display = 'block';
            inspector.innerHTML = '<em>Klicke auf einen Knoten oder eine Kante, um Details anzuzeigen.</em>';
        }
    }

    function configureGraphCanvas(canvas, graph) {
        const mode = graph && graph.view && graph.view.mode;
        const semanticLayout = mode !== 'RAW';
        canvas.classList.toggle('semantic-graph-canvas', semanticLayout);
        if (semanticLayout) {
            const nodeCount = Math.max(1, ((graph && graph.nodes) || []).length);
            canvas.style.width = '640px';
            canvas.style.maxWidth = '100%';
            canvas.style.height = Math.max(900, 180 + nodeCount * 220) + 'px';
            canvas.style.marginLeft = 'auto';
            canvas.style.marginRight = 'auto';
        } else {
            canvas.style.width = '100%';
            canvas.style.maxWidth = '';
            canvas.style.height = '520px';
            canvas.style.marginLeft = '';
            canvas.style.marginRight = '';
        }
    }

    function renderSemanticGraphBadge(graph) {
        const canvas = $('graphCanvas');
        if (!canvas) return;
        const stats = (graph && graph.stats) || {};
        const semanticNodeCount = ((graph && graph.nodes) || []).length;
        const rawNodeCount = stats.rawNodeCount || semanticNodeCount;
        const badge = document.createElement('div');
        badge.className = 'graph-semantic-watermark';
        badge.setAttribute('data-semantic-node-count', String(semanticNodeCount));
        badge.setAttribute('data-raw-node-count', String(rawNodeCount));
        badge.textContent = 'Semantic Discovery Graph · semanticNodeCount='
            + semanticNodeCount + ' / rawNodeCount=' + rawNodeCount;
        canvas.appendChild(badge);
    }

    function computeGraphLayout(graph) {
        const positions = graph && graph.view && graph.view.layout && graph.view.layout.positions;
        if (positions && typeof positions === 'object' && Object.keys(positions).length > 0) {
            return {
                name: 'preset',
                positions: (node) => {
                    const p = positions[node.id()];
                    if (!p) { return { x: 0, y: 0 }; }
                    return { x: p.x, y: p.y };
                }
            };
        }
        return { name: 'breadthfirst', spacingFactor: 1.2 };
    }

    /**
     * Stage 4 — KaTeX graph-node HTML overlays. Renders each Cytoscape
     * node's expression (preferably via `payload.layout`, else
     * `payload.expressionLatex`) as an absolutely
     * positioned `.graph-node-math` div inside a `.graph-overlay-layer`
     * wrapper that sits over the canvas. The overlay layer is repositioned
     * after `layoutstop` / `pan` / `zoom` / `position` events using each
     * node's rendered bounding box, and CSS transitions keep the motion
     * smooth.
     *
     * Optionally also projects edge `ruleLatex` captions as midpoint
     * labels when the canvas carries `data-graph-math-edges` (so we can
     * ship nodes-only first if the layout layer needs tuning).
     */
    const graphMathOverlay = (() => {
        function ensureLayer(canvas) {
            let layer = canvas.querySelector('.graph-overlay-layer');
            if (!layer) {
                layer = document.createElement('div');
                layer.className = 'graph-overlay-layer';
                layer.style.position = 'absolute';
                layer.style.left = '0';
                layer.style.top = '0';
                layer.style.right = '0';
                layer.style.bottom = '0';
                layer.style.pointerEvents = 'none';
                // The canvas itself must be a positioning context.
                const computed = window.getComputedStyle(canvas).position;
                if (computed === 'static') {
                    canvas.style.position = 'relative';
                }
                canvas.appendChild(layer);
            }
            return layer;
        }
        function projectNode(node) {
            // Returns the rendered bounding box of `node` in the
            // container's coordinate system. Cytoscape's
            // `renderedBoundingBox()` is already in container px after
            // pan/zoom, so no extra math is needed.
            const bb = node.renderedBoundingBox({ includeLabels: false });
            return { x: bb.x1, y: bb.y1, w: bb.w, h: bb.h };
        }
        function syncOverlays(cy, layer) {
            const showEdges = layer.parentElement
                && layer.parentElement.hasAttribute('data-graph-math-edges');
            const nodeIds = new Set();
            cy.nodes().forEach((node) => {
                const id = node.id();
                nodeIds.add(id);
                let host = layer.querySelector('[data-node-id="' + cssEscape(id) + '"]');
                const payload = node.data('payload') || {};
                const latex = payload.expressionLatex
                    || payload.representativeLatex
                    || payload.latex
                    || payload.representativeExpression
                    || payload.expression
                    || id;
                if (!host) {
                    host = document.createElement('div');
                    host.className = 'graph-node-math';
                    host.setAttribute('data-node-id', id);
                    if (payload.expression) {
                        host.setAttribute('aria-label', String(payload.expression));
                    }
                    layer.appendChild(host);
                }
                host.setAttribute('data-math', '$' + latex + '$');
                host.textContent = '$' + latex + '$';
                if (payload.isBest || payload.onMainPath) { host.classList.add('is-best'); } else { host.classList.remove('is-best'); }
                if (payload.isDeadEnd) { host.classList.add('is-dead-end'); } else { host.classList.remove('is-dead-end'); }
                const box = projectNode(node);
                // Use translate3d so the GPU compositor can animate the
                // CSS transition smoothly; the matching `.graph-node-math`
                // CSS rule defines `transition: transform 200ms ease`.
                host.style.transform = 'translate3d(' + (box.x + box.w / 2) + 'px,'
                    + (box.y + box.h / 2) + 'px, 0) translate(-50%, -50%)';
                window.renderMathLayout(payload.layout, host);
            });
            // Optional edge captions.
            if (showEdges) {
                const nodeHosts = Array.from(layer.querySelectorAll('.graph-node-math:not(.graph-edge-math)'));
                cy.edges().forEach((edge) => {
                    const id = 'edge:' + edge.id();
                    nodeIds.add(id);
                    let host = layer.querySelector('[data-node-id="' + cssEscape(id) + '"]');
                    const payload = edge.data('payload') || {};
                    const latex = payload.ruleLatex || payload.ruleId || '';
                    if (!latex) { return; }
                    if (!host) {
                        host = document.createElement('div');
                        host.className = 'graph-node-math graph-edge-math';
                        host.setAttribute('data-node-id', id);
                        host.setAttribute('data-edge-label', 'true');
                        layer.appendChild(host);
                    }
                    host.setAttribute('data-math', '$' + latex + '$');
                    host.textContent = '$' + latex + '$';
                    window.renderMathLayout(payload.layout, host);
                    placeEdgeLabel(edge, host, nodeHosts);
                });
            }
            // Garbage-collect overlays for removed elements.
            layer.querySelectorAll('[data-node-id]').forEach((host) => {
                if (!nodeIds.has(host.getAttribute('data-node-id'))) {
                    host.remove();
                }
            });
        }
        function cssEscape(value) {
            if (typeof CSS !== 'undefined' && typeof CSS.escape === 'function') {
                return CSS.escape(value);
            }
            return String(value).replace(/[^a-zA-Z0-9_-]/g, (c) => '\\' + c);
        }
        function placeEdgeLabel(edge, host, nodeHosts) {
            const source = edge.source().renderedPosition();
            const target = edge.target().renderedPosition();
            const midX = (source.x + target.x) / 2;
            const midY = (source.y + target.y) / 2;
            const dx = target.x - source.x;
            const dy = target.y - source.y;
            const length = Math.max(1, Math.hypot(dx, dy));
            const nx = -dy / length;
            const ny = dx / length;
            const candidates = [
                [midX + nx * 48, midY + ny * 48],
                [midX - nx * 48, midY - ny * 48],
                [midX + 88, midY],
                [midX - 88, midY],
                [midX, midY + 64],
                [midX, midY - 64]
            ];
            for (const candidate of candidates) {
                setOverlayCenter(host, candidate[0], candidate[1]);
                if (!intersectsAny(host, nodeHosts)) {
                    return;
                }
            }
            setOverlayCenter(host, midX + nx * 96, midY + ny * 96);
        }
        function setOverlayCenter(host, x, y) {
            host.style.transform = 'translate3d(' + x + 'px,' + y + 'px, 0) translate(-50%, -50%)';
        }
        function intersectsAny(host, others) {
            const rect = host.getBoundingClientRect();
            return others.some((other) => rectanglesIntersect(rect, other.getBoundingClientRect()));
        }
        function rectanglesIntersect(a, b) {
            return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top;
        }
        function install(cy, canvas) {
            const layer = ensureLayer(canvas);
            // Initial sync; subsequent updates are wired to Cytoscape
            // events so the overlay stays aligned with the canvas.
            const sync = () => syncOverlays(cy, layer);
            cy.on('layoutstop pan zoom position', sync);
            cy.ready(sync);
        }
        return { install, syncOverlays, ensureLayer };
    })();

    window.countGraphLabelOverlaps = function countGraphLabelOverlaps(edgeAgainstNodes) {
        const nodeLabels = Array.from(document.querySelectorAll(
            '#graphCanvas .graph-overlay-layer .graph-node-math:not(.graph-edge-math)'));
        const edgeLabels = Array.from(document.querySelectorAll(
            '#graphCanvas .graph-overlay-layer .graph-edge-math'));
        let overlaps = 0;
        if (edgeAgainstNodes) {
            edgeLabels.forEach((edgeLabel) => {
                const edgeRect = edgeLabel.getBoundingClientRect();
                nodeLabels.forEach((nodeLabel) => {
                    if (rectanglesIntersectForTests(edgeRect, nodeLabel.getBoundingClientRect())) {
                        overlaps++;
                    }
                });
            });
            return overlaps;
        }
        for (let i = 0; i < nodeLabels.length; i++) {
            for (let j = i + 1; j < nodeLabels.length; j++) {
                if (rectanglesIntersectForTests(
                    nodeLabels[i].getBoundingClientRect(),
                    nodeLabels[j].getBoundingClientRect())) {
                    overlaps++;
                }
            }
        }
        return overlaps;
    };

    window.mainPathYPositionsIncrease = function mainPathYPositionsIncrease() {
        const cy = window.__cyForTests;
        if (!cy) return false;
        const mainPath = cy.nodes()
            .filter((node) => node.data('payload') && node.data('payload').onMainPath === true)
            .sort((a, b) => {
                const ap = a.data('payload') || {};
                const bp = b.data('payload') || {};
                return (ap.minDepth || 0) - (bp.minDepth || 0) || String(a.id()).localeCompare(String(b.id()));
            });
        for (let i = 1; i < mainPath.length; i++) {
            if (!(mainPath[i - 1].position('y') < mainPath[i].position('y'))) {
                return false;
            }
        }
        return mainPath.length > 0;
    };

    function rectanglesIntersectForTests(a, b) {
        return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top;
    }

    function showInspector(payload) {
        const inspector = $('graphInspector');
        if (!inspector) return;
        const rows = Object.entries(payload || {}).map(([k, v]) =>
            `<div><strong>${escapeHtml(k)}:</strong> ${escapeHtml(typeof v === 'object' ? JSON.stringify(v) : String(v))}</div>`);
        inspector.innerHTML = rows.join('');
        const latex = payload && (payload.expressionLatex
            || payload.representativeLatex
            || payload.ruleLatex
            || payload.latex);
        if (latex) {
            inspector.innerHTML += '<div class="graph-inspector-math" data-math="$'
                + escapeHtml(latex) + '$">$' + escapeHtml(latex) + '$</div>';
            window.renderMathLayout(payload && payload.layout, inspector.lastElementChild);
        }
    }

    /* ─── Compare tab ─── */
    if ($('compareLoad')) {
        $('compareLoad').addEventListener('click', compareLoad);
        populateCompareSelects();
    }
    async function populateCompareSelects() {
        try {
            const response = await fetch('/api/paths');
            const data = await response.json();
            const left = $('compareLeftSelect');
            const right = $('compareRightSelect');
            if (!left || !right) return;
            left.innerHTML = '';
            right.innerHTML = '';
            (data.transformations || []).forEach(p => {
                const optL = document.createElement('option');
                optL.value = p.id; optL.textContent = p.id + ' (' + p.originalExpression + ')';
                left.appendChild(optL);
                right.appendChild(optL.cloneNode(true));
            });
        } catch (ex) {
            // ignore
        }
    }
    async function compareLoad() {
        const left = $('compareLeftSelect').value;
        const right = $('compareRightSelect').value;
        const out = $('compareOutput');
        if (!left || !right) { out.textContent = 'Bitte zwei Pfade wählen.'; return; }
        try {
            const response = await fetch('/api/paths/compare?left=' + encodeURIComponent(left) + '&right=' + encodeURIComponent(right));
            const data = await response.json();
            out.innerHTML = renderComparison(data);
        } catch (ex) {
            out.textContent = 'Fehler: ' + ex;
        }
    }
    function renderComparison(c) {
        return '<table class="compare"><thead><tr><th></th><th>' + escapeHtml(c.leftPathId) + '</th><th>' + escapeHtml(c.rightPathId) + '</th></tr></thead>'
            + '<tbody>'
            + row('Teaching-Score', c.leftTeachingScore.toFixed(3), c.rightTeachingScore.toFixed(3))
            + row('Annahmen-Schritte', c.leftAssumptionSteps, c.rightAssumptionSteps)
            + row('Proof-Status', c.leftProofStatus, c.rightProofStatus)
            + row('Score-Reihe', c.leftScoreSeries.join(' → '), c.rightScoreSeries.join(' → '))
            + '</tbody></table>'
            + '<p><strong>Kürzer:</strong> ' + escapeHtml(c.shorterPath || '—') + ' · '
            + '<strong>Didaktisch:</strong> ' + escapeHtml(c.teachingPreferredPath || '—') + ' · '
            + '<strong>Weniger Annahmen:</strong> ' + escapeHtml(c.fewerAssumptionsPath || '—') + '</p>'
            + '<h4>Gemeinsame Regeln</h4><ul>' + (c.sharedRules || []).map(r => '<li>' + escapeHtml(r) + '</li>').join('') + '</ul>'
            + '<h4>Nur links</h4><ul>' + (c.leftOnlySteps || []).map(s => '<li>' + escapeHtml(s) + '</li>').join('') + '</ul>'
            + '<h4>Nur rechts</h4><ul>' + (c.rightOnlySteps || []).map(s => '<li>' + escapeHtml(s) + '</li>').join('') + '</ul>';
    }
    function row(label, l, r) {
        return '<tr><th>' + escapeHtml(label) + '</th><td>' + escapeHtml(String(l)) + '</td><td>' + escapeHtml(String(r)) + '</td></tr>';
    }

    /* ─── Identities tab ─── */
    if ($('reloadIdentities')) {
        $('reloadIdentities').addEventListener('click', loadIdentities);
    }
    async function loadIdentities() {
        const out = $('identitiesList');
        out.innerHTML = '<div class="hint">Lade …</div>';
        try {
            const response = await fetch('/api/identities');
            const data = await response.json();
            renderIdentities(data.identities || []);
        } catch (ex) {
            out.innerHTML = '<div class="hint">Fehler: ' + ex + '</div>';
        }
    }
    function renderIdentities(items) {
        const out = $('identitiesList');
        if (!items.length) {
            out.innerHTML = '<div class="hint">Noch keine wiederkehrenden Sequenzen entdeckt.</div>';
            return;
        }
        out.innerHTML = '';
        items.forEach((identity) => {
            const card = document.createElement('div');
            card.className = 'identity-card';
            const seq = (identity.ruleIdSequence || []).join(' → ');
            card.innerHTML = '<h4>' + escapeHtml(identity.leftPattern || '?') + ' → '
                + escapeHtml(identity.rightPattern || '?') + '</h4>'
                + '<div class="hint">Sequenz: <code>' + escapeHtml(seq) + '</code></div>'
                + '<div class="hint">Vorkommen: ' + identity.occurrences
                + ' · Kompression: ' + (identity.compressionRatio || 0).toFixed(2)
                + ' · Status: ' + escapeHtml(identity.proofStatus || '')
                + ' · bekannt: ' + escapeHtml(identity.knownRuleStatus || '') + '</div>';
            const promote = document.createElement('button');
            promote.className = 'primary';
            promote.textContent = 'Als Regel übernehmen';
            promote.addEventListener('click', async () => {
                promote.disabled = true;
                try {
                    const res = await fetch('/api/identities/' + encodeURIComponent(identity.id) + '/promote',
                        { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
                    if (res.ok) {
                        promote.textContent = '✓ Übernommen';
                    } else {
                        promote.textContent = 'Fehler ' + res.status;
                        promote.disabled = false;
                    }
                } catch (ex) {
                    promote.textContent = 'Fehler';
                    promote.disabled = false;
                }
            });
            card.appendChild(promote);
            out.appendChild(card);
        });
    }

    /* ─── Dashboard tab ─── */
    if ($('reloadDashboard')) {
        $('reloadDashboard').addEventListener('click', loadDashboard);
    }
    async function loadDashboard() {
        const tiles = $('dashboardTiles');
        const rules = $('dashboardRules');
        tiles.innerHTML = '<div class="hint">Lade …</div>';
        try {
            const response = await fetch('/api/search-graph');
            const data = await response.json();
            const stats = data.stats || {};
            const macroUsage = stats.macroMoveUsage || {};
            const counterexamples = stats.counterexampleStats || {};
            const artifacts = stats.artifactCounts || {};
            const tileData = [
                ['Suchraumgröße', stats.searchSpaceSize],
                ['Match-Statistik', formatMap(stats.matchStats)],
                ['MacroMove-Nutzung', (macroUsage.timesApplied || 0) + ' / ' + (macroUsage.timesConsidered || 0)],
                ['Speicherverbrauch', formatBytes(stats.memoryUsage || 0)],
                ['Counterexamples', (counterexamples.found || 0) + ' / ' + (counterexamples.checked || 0)],
                ['Proof-Erfolgsrate', formatPercent(stats.proofSuccessRate || 0)],
                ['Artefakte', Object.values(artifacts).reduce((sum, value) => sum + Number(value || 0), 0)],
                ['Knoten', stats.nodesVisited],
                ['Kanten', stats.edgesGenerated],
                ['Sackgassen', stats.deadEnds],
                ['Bester Score', stats.bestScore],
                ['Verzweigungsfaktor (ø)', (stats.averageBranchingFactor || 0).toFixed(2)],
                ['Max. Tiefe', stats.maxDepthReached],
                ['Kandidaten', stats.candidateCount],
                ['Makroregeln', stats.macroRuleCount]
            ];
            tiles.innerHTML = '';
            tileData.forEach(([label, value]) => {
                const tile = document.createElement('div');
                tile.className = 'tile';
                tile.innerHTML = '<div class="tile-value">' + (value == null ? '–' : value)
                    + '</div><div class="tile-label">' + label + '</div>';
                tiles.appendChild(tile);
            });
            const usage = stats.ruleUsageFrequency || {};
            rules.textContent = Object.entries(usage)
                .map(([rule, count]) => count + '\t' + rule)
                .join('\n') || '–';
        } catch (ex) {
            tiles.innerHTML = '<div class="hint">Fehler: ' + ex + '</div>';
        }
    }

    function formatMap(value) {
        const entries = Object.entries(value || {});
        return entries.length ? entries.map(([k, v]) => k + ':' + v).join(' · ') : '–';
    }

    function formatPercent(value) {
        return (Number(value || 0) * 100).toFixed(0) + '%';
    }

    function formatBytes(value) {
        const bytes = Number(value || 0);
        if (bytes >= 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MiB';
        if (bytes >= 1024) return (bytes / 1024).toFixed(1) + ' KiB';
        return bytes + ' B';
    }

    /* ─── Benchmark tab ─── */
    if ($('reloadBenchmark')) {
        $('reloadBenchmark').addEventListener('click', loadBenchmark);
    }
    async function loadBenchmark() {
        const host = $('benchmarkSuites');
        if (!host) return;
        host.innerHTML = '<div class="hint">Lade Benchmark (das kann ein paar Sekunden dauern) …</div>';
        await ensureProofStatusLegend();
        try {
            const response = await fetch('/api/benchmark');
            if (!response.ok) {
                host.innerHTML = '<div class="status error">HTTP ' + response.status + '</div>';
                return;
            }
            const data = await response.json();
            const scenarios = data.scenarios || [];
            if (!scenarios.length) {
                host.innerHTML = '<div class="hint">Keine Benchmark-Szenarien gefunden.</div>';
                return;
            }
            host.innerHTML = renderBenchmarkGroups(scenarios)
                + '<p class="hint">Gesamtlaufzeit: ' + (data.elapsedMillis || 0) + ' ms</p>';
        } catch (ex) {
            host.innerHTML = '<div class="status error">Netzwerkfehler: ' + ex + '</div>';
        }
    }
    /**
     * Groups raw benchmark scenarios into the five UI categories Algebra /
     * Gleichungen / Ungleichungen / Analysis / Lineare Algebra so the
     * dashboard shows them as labelled sections.
     */
    function renderBenchmarkGroups(scenarios) {
        const groups = [
            { label: 'Algebra', match: (n) => ['known-identities',
                'polynomial-simplification', 'rational-simplification',
                'search-explosion'].indexOf(n) >= 0, items: [] },
            { label: 'Gleichungen', match: (n) => n === 'equations', items: [] },
            { label: 'Ungleichungen', match: (n) => n === 'inequalities', items: [] },
            { label: 'Analysis', match: (n) => n === 'calculus', items: [] },
            { label: 'Lineare Algebra', match: (n) => n === 'linear-algebra', items: [] },
            { label: 'Sonstige', match: () => true, items: [] }
        ];
        scenarios.forEach((sc) => {
            for (const g of groups) {
                if (g.match(sc.name || '')) { g.items.push(sc); break; }
            }
        });
        return groups
            .filter((g) => g.items.length > 0)
            .map((g) => '<section class="benchmark-group">'
                + '<h3 class="benchmark-group-title">' + escapeHtml(g.label) + '</h3>'
                + g.items.map(renderBenchmarkScenario).join('')
                + '</section>')
            .join('');
    }

    function renderBenchmarkScenario(scenario) {
        const rows = (scenario.results || []).map((r) => {
            const foundLabel = r.found
                ? '<span class="benchmark-ok">ja</span>'
                : '<span class="benchmark-miss">nein</span>';
            const expectedLabel = (r.expectedResultMatched === true)
                ? '<span class="benchmark-ok">✓</span>'
                : (r.expectedResultMatched === false)
                    ? '<span class="benchmark-miss">✗</span>'
                    : '<span class="hint">—</span>';
            const qualityLabel = r.quality === 'OK'
                ? '<span class="benchmark-ok">✅</span>'
                : r.quality === 'WARN'
                    ? '<span class="benchmark-warn">⚠️</span>'
                    : r.quality === 'FAIL'
                        ? '<span class="benchmark-miss">❌</span>'
                        : '';
            const eGraph = (r.eGraphClasses || 0) + ' / ' + (r.eGraphNodes || 0);
            const learned = r.learnedRuleUsed
                ? '<span class="benchmark-ok">✓</span>'
                : '<span class="hint">–</span>';
            return '<tr>'
                + '<td>' + qualityLabel + '</td>'
                + '<td><code>' + escapeHtml(r.strategy || '') + '</code></td>'
                + '<td><code>' + escapeHtml(r.expression || '') + '</code></td>'
                + '<td>' + foundLabel + '</td>'
                + '<td>' + expectedLabel + '</td>'
                + '<td>' + (r.elapsedMillis != null ? r.elapsedMillis + ' ms' : '–') + '</td>'
                + '<td>' + (r.visitedStates || r.exploredStates || 0) + '</td>'
                + '<td>' + (r.prunedStates || 0) + '</td>'
                + '<td>' + eGraph + '</td>'
                + '<td>' + (r.saturationSavings != null
                    ? (r.saturationSavings * 100).toFixed(1) + '%' : '–') + '</td>'
                + '<td>' + learned + '</td>'
                + '<td>' + renderProofStatusBadge(r.proofStatus) + '</td>'
                + '</tr>';
        }).join('');
        return '<div class="card benchmark-scenario">'
            + '<h3>Szenario: <code>' + escapeHtml(scenario.name || '') + '</code></h3>'
            + '<table class="benchmark-table">'
            + '<thead><tr>'
            + '<th>Status</th><th>Strategie</th><th>Ausdruck</th><th>Gefunden</th>'
            + '<th>Erw. getroffen</th><th>Laufzeit</th>'
            + '<th>Besucht</th><th>Geprunt</th><th>e-Klassen / -Knoten</th>'
            + '<th>Sat-Sparung</th><th>Lernregel</th><th>Proof-Status</th>'
            + '</tr></thead>'
            + '<tbody>' + rows + '</tbody>'
            + '</table>'
            + '</div>';
    }

    /* ─── Replay tab ─── */
    let replayState = { steps: [], index: 0, timer: null, alignedDerivationLatex: '', derivationLayout: null };
    if ($('replayLoad')) {
        $('replayLoad').addEventListener('click', loadReplay);
        $('replayPrev').addEventListener('click', () => { stopReplay(); stepReplay(-1); });
        $('replayNext').addEventListener('click', () => { stopReplay(); stepReplay(1); });
        $('replayPlay').addEventListener('click', () => {
            if (replayState.timer) { stopReplay(); return; }
            replayState.timer = setInterval(() => {
                if (replayState.index >= replayState.steps.length - 1) { stopReplay(); return; }
                stepReplay(1);
            }, 1200);
            $('replayPlay').textContent = '⏸';
        });
    }
    function stopReplay() {
        if (replayState.timer) { clearInterval(replayState.timer); replayState.timer = null; }
        if ($('replayPlay')) $('replayPlay').textContent = '▶';
    }
    function stepReplay(delta) {
        const next = replayState.index + delta;
        if (next < 0 || next >= replayState.steps.length) return;
        replayState.index = next;
        renderReplayStep();
    }
    async function populateReplayPaths() {
        const select = $('replayPathSelect');
        if (!select) return;
        try {
            const response = await fetch('/api/paths?sort=score');
            const data = await response.json();
            select.innerHTML = '';
            (data.transformations || []).forEach((path) => {
                const opt = document.createElement('option');
                opt.value = path.id;
                opt.textContent = path.id + ' — Δ' + path.totalImprovement;
                select.appendChild(opt);
            });
        } catch (ex) {
            select.innerHTML = '<option>Fehler: ' + ex + '</option>';
        }
    }
    async function loadReplay() {
        window.__regelsucheReplayReady = false;
        const select = $('replayPathSelect');
        const pathId = select && select.value;
        if (!pathId) { return; }
        stopReplay();
        try {
            const response = await fetch('/api/paths/' + encodeURIComponent(pathId) + '/replay');
            const data = await response.json();
            replayState.steps = data.steps || [];
            replayState.alignedDerivationLatex = data.alignedDerivationLatex || '';
            replayState.alignedDerivationLatexWithDiff = data.alignedDerivationLatexWithDiff || '';
            replayState.derivationLayout = data.derivationLayout || null;
            replayState.index = 0;
            renderReplayStep();
        } catch (ex) {
            $('replayCanvas').innerHTML = '<div class="hint">Fehler: ' + ex + '</div>';
            window.__regelsucheReplayReady = false;
        }
    }
    function renderReplayStep() {
        const canvas = $('replayCanvas');
        if (!canvas) return;
        if (!replayState.steps.length) {
            canvas.innerHTML = '<div class="hint">Wähle oben einen Pfad und klicke „Laden".</div>';
            return;
        }
        const step = replayState.steps[replayState.index];
        const ruleId = step.ruleId || '';
        // Math-domain-specific extras for the four PR-#13 demos.
        const extras = renderReplayDomainExtras(step, ruleId);
        const derivationBlock = renderAlignedDerivationBlock(
            replayState.derivationLayout,
            replayState.alignedDerivationLatex,
            replayState.index);
        const replayMetrics = renderReplayMetrics();
        const fromInline = '$' + (step.fromLatex || '') + '$';
        canvas.innerHTML = derivationBlock
            + replayMetrics
            + '<div class="replay-step">'
            + '<div class="replay-step-index">Schritt ' + (step.stepIndex + 1)
            + ' / ' + replayState.steps.length + '</div>'
            + '<div class="replay-from"><strong>Vorher:</strong> '
            + '<code>' + escapeHtml(step.fromExpression) + '</code><br>'
            + '<span class="math replay-step-from-math" data-math="' + escapeHtml(fromInline) + '">' + escapeHtml(fromInline) + '</span></div>'
            + '<div class="replay-to"><strong>Nachher:</strong> '
            + '<code>' + escapeHtml(step.toExpression) + '</code><br>'
            + '<span class="replay-step-math" data-math="$' + escapeHtml(step.toLatex || '')
            + '$">$' + escapeHtml(step.toLatex || '') + '$</span></div>'
            + '<div class="replay-rule"><strong>Regel:</strong> <code>'
            + escapeHtml(ruleId) + '</code></div>'
            + extras
            + '<div class="replay-explanation"><pre>'
            + escapeHtml(step.ruleExplanation || '') + '</pre></div>'
            + '<div class="hint">Δ Komplexität: ' + step.scoreDelta
            + ' · Äquivalenzerhaltend: ' + step.equivalencePreserving + '</div>'
            + '</div>';
        const derivationHost = canvas.querySelector('.replay-derivation-math');
        if (derivationHost) {
            window.renderMathLayout(replayState.derivationLayout, derivationHost);
        }
        const toHost = canvas.querySelector('.replay-step-math');
        if (toHost) {
            window.renderMathLayout(step.layout, toHost);
        }
        window.renderMath(canvas);
        window.__regelsucheReplayReady = true;
    }

    function renderReplayMetrics() {
        const macroSteps = replayState.steps.filter((s) => s.macroMoveExpansion).length;
        const counterexampleSteps = replayState.steps.filter((s) =>
            String(s.ruleExplanation || '').toLowerCase().includes('counterexample')).length;
        return '<div class="replay-dashboard-metrics">'
            + '<span><strong>searchSpaceSize</strong>: ' + replayState.steps.length + '</span>'
            + '<span><strong>macroMoveUsage</strong>: ' + macroSteps + '</span>'
            + '<span><strong>counterexampleStats</strong>: ' + counterexampleSteps + '</span>'
            + '</div>';
    }

    /**
     * Stage 2: render the whole derivation as one `\begin{aligned}` block
     * with a highlighted row for the currently focused step. The block is
     * provided by the backend (PathReplayDto.alignedDerivationLatex) so
     * the same rule-arrow style is reused across server-rendered
     * exports and the interactive UI.
     */
    function renderAlignedDerivationBlock(layout, latex, focusIndex) {
        if (!latex) return '';
        const display = '$$' + latex + '$$';
        return '<div class="replay-derivation-block replay-derivation-focus" data-focus-step="' + focusIndex + '">'
            + '<div class="replay-derivation-title">Rechenweg</div>'
            + '<div class="replay-derivation-math" data-math="' + escapeHtml(display) + '"'
            + (layout && layout.aria ? ' aria-label="' + escapeHtml(String(layout.aria)) + '"' : '') + '>'
            + escapeHtml(display)
            + '</div>'
            + '</div>';
    }

    /**
     * Domain-specific replay decorations:
     *  - inequality_* steps that flip the comparator show a red "Vergleichszeichen gedreht" Hinweis,
     *  - calculus_* steps render a Regelkarte (Potenzregel/Summenregel/Produktregel),
     *  - linalg_/matrix_/vector_ steps show a bmatrix preview block.
     *
     * Stage 3: the comparator-flip detection is driven exclusively by the
     * server-side `step.comparatorFlipped` flag emitted by
     * `PathReplayDto.from(...)`. The legacy JS heuristic
     * (rule id + ascii-comparator regex) has been removed so the JS and
     * codec agree on the flag.
     */
    function renderReplayDomainExtras(step, ruleId) {
        const out = [];
        if (step.comparatorFlipped === true) {
            out.push('<div class="status error replay-flip-notice">'
                + '<strong>⚠️ Vergleichszeichen wurde gedreht.</strong> '
                + 'Multiplikation/Division mit einem negativen Faktor dreht das '
                + 'Vergleichszeichen um.</div>');
        }
        if (ruleId.startsWith('calculus_')) {
            const label = derivativeRuleLabel(ruleId);
            if (label) {
                out.push('<div class="replay-rule-card replay-derivative-card">'
                    + '<strong>' + escapeHtml(label.title) + '</strong>'
                    + '<div class="rule-card-body">' + escapeHtml(label.body) + '</div>'
                    + '</div>');
            }
        }
        if (ruleId.startsWith('linalg_') || ruleId.startsWith('matrix_')
            || ruleId.startsWith('vector_')) {
            // The backend already emits LaTeX with \begin{bmatrix} for matrix
            // literals, but for the replay overlay we additionally tag the
            // block so the CSS picks up the matrix theme.
            const before = step.fromLatex || step.fromExpression || '';
            const after = step.toLatex || step.toExpression || '';
            out.push('<div class="replay-rule-card replay-matrix-card">'
                + '<strong>Matrix/Vektor</strong>'
                + '<div class="rule-card-body">$' + escapeHtml(before) + '$ → $'
                + escapeHtml(after) + '$</div>'
                + '</div>');
        }
        const macro = step.macroMoveExpansion;
        if (macro && Array.isArray(macro.atomicSteps) && macro.atomicSteps.length) {
            const supportingPaths = (macro.supportingPathIds || []).length
                ? '<div class="hint">Discovery-Branches: '
                    + (macro.supportingPathIds || []).map((id) =>
                        '<code>' + escapeHtml(id) + '</code>').join(', ')
                    + '</div>'
                : '';
            const atomicSteps = macro.atomicSteps.map((atomicStep) =>
                '<li><code>' + escapeHtml(atomicStep.ruleId || '') + '</code>: '
                    + '<code>' + escapeHtml(atomicStep.beforeExpression || '') + ' → '
                    + escapeHtml(atomicStep.afterExpression || '') + '</code></li>').join('');
            out.push('<div class="replay-rule-card replay-macro-card">'
                + '<strong>Makrozug: ' + escapeHtml(macro.macroRuleId || '') + '</strong>'
                + '<div class="rule-card-body">Kompression: '
                + escapeHtml(Number(macro.compressionRatio || 1).toFixed(2))
                + ' · atomare Schritte: ' + macro.atomicSteps.length + '</div>'
                + renderMacroStats(macro.stats)
                + supportingPaths
                + '<details' + (macro.expanded ? ' open' : '') + '>'
                + '<summary>Atomare Replay-Schritte anzeigen</summary>'
                + '<ol class="replay-macro-steps">' + atomicSteps + '</ol>'
                + '</details>'
                + '</div>');
        }
        return out.join('');
    }

    function renderMacroStats(stats) {
        if (!stats) return '';
        return '<div class="hint">Stats: considered=' + escapeHtml(stats.timesConsidered || 0)
            + ' · applied=' + escapeHtml(stats.timesApplied || 0)
            + ' · improved=' + escapeHtml(stats.timesImprovedScore || 0)
            + ' · avgCostReduction=' + escapeHtml(Number(stats.averageCostReduction || 0).toFixed(2))
            + '</div>';
    }

    function derivativeRuleLabel(ruleId) {
        switch (ruleId) {
            case 'calculus_diff_power_rule':
                return { title: 'Potenzregel',
                    body: 'd/dx xⁿ = n·xⁿ⁻¹' };
            case 'calculus_diff_of_sum':
                return { title: 'Summenregel',
                    body: 'd/dx (f + g) = f′ + g′' };
            case 'calculus_diff_of_difference':
                return { title: 'Differenzregel',
                    body: 'd/dx (f − g) = f′ − g′' };
            case 'calculus_diff_of_product':
                return { title: 'Produktregel',
                    body: 'd/dx (f · g) = f′·g + f·g′' };
            case 'calculus_diff_of_constant':
                return { title: 'Konstantenregel',
                    body: 'd/dx c = 0' };
            case 'calculus_diff_of_variable':
                return { title: 'Identitätsregel',
                    body: 'd/dx x = 1' };
            default:
                if (ruleId.startsWith('calculus_diff_of_')) {
                    return { title: 'Ableitungsregel',
                        body: 'Standardableitung der Elementarfunktion' };
                }
                return null;
        }
    }
    function escapeHtml(value) {
        if (value == null) return '';
        return String(value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    /* ─── Candidates tab ─── */
    $('reloadCandidates').addEventListener('click', loadCandidates);
    async function loadCandidates() {
        const out = $('candidatesList');
        out.innerHTML = '<div class="hint">Lade …</div>';
        try {
            const response = await fetch('/api/candidates');
            const data = await response.json();
            renderCandidates(data.candidates || []);
        } catch (ex) {
            out.innerHTML = '<div class="status error">Fehler: ' + ex + '</div>';
        }
    }

    function renderCandidates(candidates) {
        const out = $('candidatesList');
        if (!candidates.length) {
            out.innerHTML = '<div class="hint">Keine Regelkandidaten – starte zuerst eine Suche.</div>';
            return;
        }
        out.innerHTML = '';
        candidates.forEach((c) => {
            const div = document.createElement('div');
            div.className = 'list-item';
            const title = document.createElement('h4');
            title.textContent = (c.leftPattern || '?') + ' → ' + (c.rightPattern || '?');
            div.appendChild(title);
            const meta = document.createElement('div');
            meta.className = 'meta';
            meta.textContent = 'Beispiele ' + (c.examplesCount || 0)
                + ' · ⌀-Verbesserung ' + (c.averageScoreImprovement || 0).toFixed(2)
                + ' · max ' + (c.maximumScoreImprovement || 0)
                + ' · Status ' + (c.status || '-')
                + ' · Beweisstatus ' + (c.proofStatus || '-');
            div.appendChild(meta);
            const assumptionList = c.assumptions || extractAssumptionsFromText(c);
            if (assumptionList.length) {
                const wrap = document.createElement('div');
                wrap.style.marginTop = '0.4rem';
                assumptionList.forEach((a) => {
                    const span = document.createElement('span');
                    span.className = 'assumption';
                    span.textContent = a;
                    wrap.appendChild(span);
                });
                div.appendChild(wrap);
            }
            out.appendChild(div);
        });
    }

    /**
     * Fallback heuristic to display assumptions for candidates that come from
     * the server without an explicit field — we infer well-known ones from the
     * pattern strings so the UI is still informative.
     */
    function extractAssumptionsFromText(c) {
        const left = (c.leftPattern || '') + ' ' + (c.rightPattern || '');
        const hints = [];
        if (/\blog\(|\bln\(/i.test(left)) hints.push('Argument > 0');
        if (/\bsqrt\(/i.test(left)) hints.push('Argument ≥ 0');
        if (/\btan\(/i.test(left)) hints.push('cos(arg) ≠ 0');
        if (/\/[a-zA-Z_]/.test(left)) hints.push('Nenner ≠ 0');
        return hints;
    }

    /* ─── Inventory tab ─── */
    $('reloadInventory').addEventListener('click', loadInventory);
    async function loadInventory() {
        const out = $('inventoryList');
        out.innerHTML = '<div class="hint">Lade …</div>';
        try {
            const response = await fetch('/api/inventory');
            const data = await response.json();
            renderInventory(data.rules || []);
        } catch (ex) {
            out.innerHTML = '<div class="status error">Fehler: ' + ex + '</div>';
        }
    }

    function renderInventory(rules) {
        const out = $('inventoryList');
        if (!rules.length) {
            out.innerHTML = '<div class="hint">Inventar ist leer.</div>';
            return;
        }
        out.innerHTML = '';
        rules.forEach((rule) => {
            const div = document.createElement('div');
            div.className = 'list-item';
            const title = document.createElement('h4');
            title.textContent = (rule.leftPattern || '?') + ' → ' + (rule.rightPattern || '?');
            div.appendChild(title);
            const meta = document.createElement('div');
            meta.className = 'meta';
            meta.textContent = 'id=' + rule.id
                + ' · Beweisstatus ' + (rule.proofStatus || '-')
                + ' · ' + (rule.enabled === false ? 'deaktiviert' : 'aktiv');
            div.appendChild(meta);
            if (rule.tags && rule.tags.length) {
                const tagWrap = document.createElement('div');
                tagWrap.className = 'tags';
                rule.tags.forEach((t) => {
                    const span = document.createElement('span');
                    span.className = 'tag';
                    span.textContent = t;
                    tagWrap.appendChild(span);
                });
                div.appendChild(tagWrap);
            }
            const actions = document.createElement('div');
            actions.className = 'actions';
            const toggle = document.createElement('button');
            toggle.textContent = rule.enabled === false ? 'Aktivieren' : 'Deaktivieren';
            toggle.className = rule.enabled === false ? 'primary' : 'danger';
            toggle.addEventListener('click', () => {
                rule.enabled = !(rule.enabled !== false);
                renderInventory(rules); // local-only toggle; server endpoint not yet exposed
            });
            actions.appendChild(toggle);
            const tagInput = document.createElement('input');
            tagInput.type = 'text';
            tagInput.placeholder = 'neues Tag';
            tagInput.style.padding = '0.25rem 0.4rem';
            tagInput.style.border = '1px solid #d1d5db';
            tagInput.style.borderRadius = '4px';
            tagInput.style.fontSize = '0.8rem';
            actions.appendChild(tagInput);
            const tagBtn = document.createElement('button');
            tagBtn.textContent = 'Tag hinzufügen';
            tagBtn.addEventListener('click', () => {
                const value = tagInput.value.trim();
                if (!value) return;
                rule.tags = (rule.tags || []).concat([value]);
                tagInput.value = '';
                renderInventory(rules);
            });
            actions.appendChild(tagBtn);
            div.appendChild(actions);
            out.appendChild(div);
        });
    }

    /* ─── Memory tab ─── */
    if ($('reloadMemory')) {
        $('reloadMemory').addEventListener('click', loadMemory);
        if ($('memoryPruningFilter')) {
            $('memoryPruningFilter').addEventListener('change', loadMemory);
        }
    }
    async function loadMemory() {
        try {
            const [statesResp, pruningResp, macrosResp, universalResp] = await Promise.all([
                fetch('/api/memory/states'),
                fetch('/api/memory/pruning'),
                fetch('/api/memory/macros'),
                fetch('/api/memory/universal'),
            ]);
            const states = await statesResp.json();
            const pruning = await pruningResp.json();
            const macros = await macrosResp.json();
            const universal = await universalResp.json();
            renderMemoryStates(states);
            renderMemoryPruning(pruning);
            renderMemoryMacros(macros);
            renderMemoryUniversal(universal);
        } catch (e) {
            console.error('loadMemory failed', e);
        }
    }
    function renderMemoryStates(data) {
        const out = $('memoryStates');
        if (!out) return;
        out.innerHTML = '';
        const entries = (data && data.entries) || [];
        if (entries.length === 0) {
            out.textContent = 'Noch keine Zustände beobachtet. Starte einen DISCOVERY_PLUS-Suchlauf.';
            return;
        }
        entries.slice(0, 50).forEach((e) => {
            const div = document.createElement('div');
            div.className = 'list-item';
            div.innerHTML =
                '<div><strong>' + (e.canonicalExpression || '') + '</strong></div>'
                + '<div class="hint">hash: ' + e.canonicalHash + ' · visits: ' + e.visitCount
                + ' · bestScore: ' + e.bestScore + ' · depth: ' + e.minDepthSeen + '</div>';
            out.appendChild(div);
        });
    }
    function renderMemoryPruning(data) {
        const out = $('memoryPruning');
        if (!out) return;
        out.innerHTML = '';
        const filter = $('memoryPruningFilter') ? $('memoryPruningFilter').value : '';
        const decisions = ((data && data.decisions) || [])
            .filter((d) => !filter || d.reason === filter);
        if (decisions.length === 0) {
            out.textContent = 'Keine Pruning-Entscheidungen für den Filter.';
            return;
        }
        decisions.slice(0, 50).forEach((d) => {
            const div = document.createElement('div');
            div.className = 'list-item';
            div.innerHTML =
                '<div><span class="badge">' + d.reason + '</span> '
                + '<strong>' + (d.expression || '') + '</strong></div>'
                + '<div class="hint">' + (d.explanation || '') + '</div>';
            out.appendChild(div);
        });
    }
    function renderMemoryMacros(data) {
        const out = $('memoryMacros');
        if (!out) return;
        out.innerHTML = '';
        const macros = (data && data.macros) || [];
        if (macros.length === 0) {
            out.textContent = 'Noch keine Makroregeln gelernt.';
            return;
        }
        macros.forEach((m) => {
            const div = document.createElement('div');
            div.className = 'list-item';
            div.innerHTML =
                '<div><strong>' + m.leftPattern + ' → ' + m.rightPattern + '</strong></div>'
                + '<div class="hint">occurrences: ' + m.occurrenceCount
                + ' · confidence: ' + (m.confidenceScore || 0).toFixed(2)
                + ' · enabled: ' + m.enabled + '</div>';
            out.appendChild(div);
        });
    }
    function renderMemoryUniversal(data) {
        const out = $('memoryUniversal');
        if (out) {
            out.innerHTML = '';
            const patterns = (data && data.patterns) || [];
            if (patterns.length === 0) {
                out.textContent = 'Noch keine universellen Muster — starte einen DISCOVERY_PLUS-Suchlauf.';
            } else {
                patterns.forEach((p) => {
                    const div = document.createElement('div');
                    div.className = 'list-item';
                    const rules = escapeHtml((p.reachedByRuleIds || []).join(', ') || '—');
                    const pathId = p.bestKnownPathId ? String(p.bestKnownPathId) : '';
                    const escapedPathId = escapeHtml(pathId);
                    const pathLink = p.bestKnownPathId
                        ? ' · best path: <a href="#" data-path="' + escapedPathId
                            + '" class="universal-path">' + escapedPathId + '</a>'
                        : '';
                    div.innerHTML =
                        '<div><strong>' + escapeHtml(p.canonicalExpression || '') + '</strong></div>'
                        + '<div class="hint">universality: <b>' + escapeHtml(String(p.universalityScore ?? '')) + '</b>'
                        + ' · visits: ' + escapeHtml(String(p.visitCount ?? ''))
                        + ' · bestScore: ' + escapeHtml(String(p.bestScore ?? ''))
                        + ' · depth: ' + escapeHtml(String(p.minDepthSeen ?? '')) + '</div>'
                        + '<div class="hint">rules: ' + rules + pathLink + '</div>';
                    out.appendChild(div);
                });
                out.querySelectorAll('a.universal-path').forEach((a) => {
                    a.addEventListener('click', (evt) => {
                        evt.preventDefault();
                        // Hand off to the Paths tab so the user can replay the
                        // supporting transformation directly.
                        const pathId = a.dataset.path;
                        if (typeof window !== 'undefined') {
                            window.location.hash = '#path=' + encodeURIComponent(pathId);
                        }
                    });
                });
            }
        }
        const cov = $('memoryRuleCoverage');
        if (cov) {
            cov.innerHTML = '';
            const coverage = (data && data.ruleCoverage) || [];
            if (coverage.length === 0) {
                cov.textContent = 'Keine Coverage-Daten.';
            } else {
                coverage.slice(0, 30).forEach((c) => {
                    const div = document.createElement('div');
                    div.className = 'list-item';
                    div.innerHTML = '<span class="badge">' + c.coverage + '</span> '
                        + '<code>' + c.ruleId + '</code>';
                    cov.appendChild(div);
                });
            }
        }
    }

    /* ─── Auto-load on page open ─── */
    document.addEventListener('DOMContentLoaded', () => {
        window.renderMath(document.body);
        // Lazy-load when a tab is activated for the first time.
        document.querySelectorAll('.tab').forEach((tab) => {
            tab.addEventListener('click', () => {
                const which = tab.dataset.tab;
                if (which === 'paths' && !$('pathsList').dataset.loaded) {
                    loadPaths().finally(() => $('pathsList').dataset.loaded = '1');
                } else if (which === 'candidates' && !$('candidatesList').dataset.loaded) {
                    loadCandidates().finally(() => $('candidatesList').dataset.loaded = '1');
                } else if (which === 'inventory' && !$('inventoryList').dataset.loaded) {
                    loadInventory().finally(() => $('inventoryList').dataset.loaded = '1');
                } else if (which === 'identities' && $('identitiesList') && !$('identitiesList').dataset.loaded) {
                    loadIdentities().finally(() => $('identitiesList').dataset.loaded = '1');
                } else if (which === 'dashboard' && $('dashboardTiles') && !$('dashboardTiles').dataset.loaded) {
                    loadDashboard().finally(() => $('dashboardTiles').dataset.loaded = '1');
                } else if (which === 'replay' && $('replayPathSelect') && !$('replayPathSelect').dataset.loaded) {
                    populateReplayPaths().finally(() => $('replayPathSelect').dataset.loaded = '1');
                } else if (which === 'memory' && $('memoryStates') && !$('memoryStates').dataset.loaded) {
                    loadMemory().finally(() => $('memoryStates').dataset.loaded = '1');
                } else if (which === 'proofJobs' && $('proofJobList') && !$('proofJobList').dataset.loaded) {
                    loadProofJobs().finally(() => $('proofJobList').dataset.loaded = '1');
                }
            });
        });

        /* Proof-Jobs UI bindings */
        const submitBtn = $('proofJobSubmit');
        if (submitBtn) {
            submitBtn.addEventListener('click', submitProofJob);
        }
        const reloadBtn = $('proofJobReload');
        if (reloadBtn) {
            reloadBtn.addEventListener('click', loadProofJobs);
        }
    });

    /* ─── Proof Jobs ─── */

    function submitProofJob() {
        const message = $('proofJobMessage');
        const left = ($('proofJobLeft').value || '').trim();
        const right = ($('proofJobRight').value || '').trim();
        if (!left || !right) {
            if (message) { message.textContent = 'Left- und Right-Pattern sind erforderlich.'; }
            return;
        }
        const priority = parseInt($('proofJobPriority').value || '0', 10);
        const rawAssumptions = ($('proofJobAssumptions').value || '').split(/\r?\n/)
            .map((s) => s.trim())
            .filter((s) => s.length > 0)
            .map((expression) => ({ kind: 'CUSTOM', expression }));
        const body = JSON.stringify({
            leftPattern: left,
            rightPattern: right,
            assumptions: rawAssumptions,
            priority,
        });
        fetch('/api/proof/jobs', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body,
        }).then((response) => {
            if (response.status === 503) {
                if (message) { message.textContent = 'Proof-Workbench ist deaktiviert (REGELSUCHE_PROOF_ENABLED=false).'; }
                return null;
            }
            return response.json();
        }).then((json) => {
            if (json && json.jobId) {
                if (message) { message.textContent = 'Job eingereicht: ' + json.jobId; }
                loadProofJobs();
            }
        }).catch((err) => {
            if (message) { message.textContent = 'Fehler: ' + err; }
        });
    }

    function loadProofJobs() {
        const container = $('proofJobList');
        if (!container) { return Promise.resolve(); }
        return fetch('/api/proof/jobs').then((response) => {
            if (response.status === 503) {
                container.innerHTML = '<div class="item">Proof-Workbench ist deaktiviert.</div>';
                return null;
            }
            return response.json();
        }).then((json) => {
            if (!json) { return; }
            renderProofJobs(json.jobs || []);
        }).catch((err) => {
            container.innerHTML = '<div class="item">Fehler beim Laden: ' + err + '</div>';
        });
    }

    function renderProofJobs(jobs) {
        const container = $('proofJobList');
        container.innerHTML = '';
        if (jobs.length === 0) {
            container.innerHTML = '<div class="item">Noch keine Jobs eingereicht.</div>';
            return;
        }
        jobs.slice().sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || ''))
            .forEach((job) => {
                const div = document.createElement('div');
                div.className = 'item';
                const isTerminal = job.status === 'DONE' || job.status === 'FAILED' || job.status === 'CANCELLED';
                const cancelBtn = isTerminal ? ''
                    : ' <button class="proof-cancel" data-id="' + escapeHtml(job.id) + '">Cancel</button>';
                const artifactBtn = ' <button class="proof-artifacts" data-id="' + escapeHtml(job.id) + '">Artefakte</button>';
                div.innerHTML = '<div><b>' + escapeHtml(job.leftPattern) + ' → '
                    + escapeHtml(job.rightPattern) + '</b></div>'
                    + '<div>Status: <code>' + escapeHtml(job.status) + '</code>'
                    + ' · Worker: ' + escapeHtml(job.workerId)
                    + ' · Priorität: ' + job.priority
                    + ' · Retries: ' + job.retryCount + '/' + job.maxRetries
                    + (job.proofStatus ? ' · Proof: <code>' + escapeHtml(job.proofStatus) + '</code>' : '')
                    + '</div>'
                    + '<div class="hint">ID: <code>' + escapeHtml(job.id) + '</code>'
                    + ' · created ' + escapeHtml(job.createdAt) + '</div>'
                    + (job.errorMessage ? '<div class="hint">Fehler: ' + escapeHtml(job.errorMessage) + '</div>' : '')
                    + '<div class="actions">' + artifactBtn + cancelBtn + '</div>';
                container.appendChild(div);
            });
        container.querySelectorAll('.proof-cancel').forEach((btn) => {
            btn.addEventListener('click', () => cancelProofJob(btn.dataset.id));
        });
        container.querySelectorAll('.proof-artifacts').forEach((btn) => {
            btn.addEventListener('click', () => loadProofArtifacts(btn.dataset.id));
        });
    }

    function cancelProofJob(jobId) {
        fetch('/api/proof/jobs/' + encodeURIComponent(jobId) + '/cancel', { method: 'POST' })
            .then(() => loadProofJobs());
    }

    function loadProofArtifacts(jobId) {
        const container = $('proofJobArtifacts');
        if (!container) { return; }
        container.innerHTML = '<div class="item">Lade Artefakte für ' + escapeHtml(jobId) + ' …</div>';
        fetch('/api/proof/jobs/' + encodeURIComponent(jobId) + '/artifacts')
            .then((response) => response.json())
            .then((json) => {
                container.innerHTML = '';
                const header = document.createElement('div');
                header.className = 'item';
                header.innerHTML = '<b>Bundle für Job</b> <code>' + escapeHtml(jobId) + '</code>';
                container.appendChild(header);
                (json.artifacts || []).forEach((name) => {
                    const div = document.createElement('div');
                    div.className = 'item';
                    const url = '/api/proof/jobs/' + encodeURIComponent(jobId)
                        + '/artifacts/' + encodeURIComponent(name);
                    div.innerHTML = '<a href="' + url + '" target="_blank">' + escapeHtml(name) + '</a>';
                    container.appendChild(div);
                });
                if (!json.artifacts || json.artifacts.length === 0) {
                    const div = document.createElement('div');
                    div.className = 'item';
                    div.textContent = 'Noch keine Artefakte (Job läuft eventuell noch).';
                    container.appendChild(div);
                }
            }).catch((err) => {
                container.innerHTML = '<div class="item">Fehler: ' + escapeHtml(String(err)) + '</div>';
            });
    }
    // ───────────────────────── Didaktik tab (PR 17) ─────────────────────────
    function didaktikPathId() {
        const el = $('didaktikPathId');
        return el ? el.value.trim() : '';
    }
    function didaktikProfile() {
        const el = $('didaktikProfile');
        return el ? el.value : 'SCHOOL';
    }
    function didaktikCurrent() {
        const el = $('didaktikCurrent');
        return el ? el.value : '';
    }

    function renderDidaktikDiff(tokens) {
        return (tokens || []).map((t) => {
            const cls = 'diff-' + (t.change || 'UNCHANGED').toLowerCase();
            return '<span class="' + cls + '">' + escapeHtml(t.text || '') + '</span>';
        }).join(' ');
    }

    function loadDidaktikReplay() {
        const id = didaktikPathId();
        const container = $('didaktikReplay');
        if (!container) { return; }
        if (!id) { container.innerHTML = '<p class="hint">Pfad-ID erforderlich.</p>'; return; }
        container.innerHTML = '<p class="hint">Lade Replay …</p>';
        fetch('/api/didactic/replay/' + encodeURIComponent(id))
            .then((r) => r.ok ? r.json() : Promise.reject(r.status))
            .then((json) => {
                const steps = json.steps || [];
                if (steps.length === 0) {
                    container.innerHTML = '<p class="hint">Keine Schritte für ' + escapeHtml(id) + '.</p>';
                    return;
                }
                container.innerHTML = '<h4>Replay (' + escapeHtml(json.originalExpression || '')
                    + ' → ' + escapeHtml(json.improvedExpression || '') + ')</h4>'
                    + steps.map((s, i) => '<div class="didaktik-step">'
                        + '<b>Schritt ' + (i + 1) + ':</b> '
                        + '<code>' + escapeHtml(s.beforeExpression) + '</code> → '
                        + '<code>' + escapeHtml(s.afterExpression) + '</code>'
                        + '<div class="didaktik-step-diff">' + renderDidaktikDiff(s.diffTokens) + '</div>'
                        + (s.explanation ? '<div class="hint">' + escapeHtml(s.explanation) + '</div>' : '')
                        + '</div>').join('');
            })
            .catch((status) => {
                container.innerHTML = '<p class="hint">Fehler: ' + escapeHtml(String(status)) + '</p>';
            });
    }

    function loadDidaktikHint() {
        const id = didaktikPathId();
        const container = $('didaktikHints');
        if (!container) { return; }
        if (!id) { container.innerHTML = '<p class="hint">Pfad-ID erforderlich.</p>'; return; }
        container.innerHTML = '<p class="hint">Lade Hinweise …</p>';
        fetch('/api/didactic/hint/' + encodeURIComponent(id), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                currentExpression: didaktikCurrent(),
                pedagogyProfile: didaktikProfile()
            })
        })
            .then((r) => r.ok ? r.json() : Promise.reject(r.status))
            .then((json) => {
                const hints = json.hints || [];
                if (hints.length === 0) {
                    container.innerHTML = '<p class="hint">Keine Hinweise verfügbar.</p>';
                    return;
                }
                container.innerHTML = '<h4>Hinweise</h4>' + hints.map((h) =>
                    '<div class="didaktik-hint didaktik-hint-' + (h.strength || '').toLowerCase() + '">'
                    + '<b>' + escapeHtml(h.strength || '') + ':</b> '
                    + escapeHtml(h.text || '')
                    + '</div>').join('');
            })
            .catch((status) => {
                container.innerHTML = '<p class="hint">Fehler: ' + escapeHtml(String(status)) + '</p>';
            });
    }

    function runDidaktikStepCheck() {
        const container = $('didaktikStepResult');
        if (!container) { return; }
        const current = ($('didaktikStepCurrent') || {}).value || '';
        const step = ($('didaktikStepStudent') || {}).value || '';
        const difficulty = ($('didaktikDifficulty') || {}).value || 'MITTELSTUFE';
        if (!current.trim() || !step.trim()) {
            container.innerHTML = '<p class="hint">Beide Ausdrücke erforderlich.</p>';
            return;
        }
        container.innerHTML = '<p class="hint">Prüfe …</p>';
        fetch('/api/didactic/step-check', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                currentExpression: current, studentStep: step, difficulty: difficulty
            })
        })
            .then((r) => r.ok ? r.json() : r.text().then((t) => Promise.reject(t)))
            .then((json) => {
                const status = json.correct
                    ? (json.didacticallyAppropriate ? '✅ akzeptiert' : '⚠️ korrekt, aber zu komplex')
                    : '❌ nicht akzeptiert';
                let html = '<div><b>' + status + '</b></div>'
                    + '<div class="hint">' + escapeHtml(json.message || '') + '</div>';
                if (json.misconception) {
                    html += '<div class="didaktik-misconception">'
                        + '<b>Fehlvorstellung:</b> ' + escapeHtml(json.misconception.id || '') + '<br>'
                        + escapeHtml(json.misconception.explanation || '')
                        + '</div>';
                }
                container.innerHTML = html;
            })
            .catch((err) => {
                container.innerHTML = '<p class="hint">Fehler: ' + escapeHtml(String(err)) + '</p>';
            });
    }

    function loadDidaktikAnalytics() {
        const container = $('didaktikAnalytics');
        if (!container) { return; }
        fetch('/api/didactic/analytics')
            .then((r) => r.json())
            .then((json) => { container.textContent = JSON.stringify(json, null, 2); })
            .catch((err) => { container.textContent = 'Fehler: ' + String(err); });
    }

    function updateDidaktikExportLinks() {
        const id = didaktikPathId();
        const base = '/api/didactic/export/';
        const setHref = (elId, kind) => {
            const a = $(elId);
            if (!a) { return; }
            a.href = id ? (base + kind + '/' + encodeURIComponent(id) + '.md') : '#';
        };
        setHref('didaktikExportWorksheet', 'worksheet');
        setHref('didaktikExportSolution', 'solution');
        setHref('didaktikExportTeacher', 'teacher');
    }

    document.addEventListener('DOMContentLoaded', () => {
        const wireClick = (id, fn) => {
            const el = $(id);
            if (el) { el.addEventListener('click', fn); }
        };
        wireClick('didaktikReplayBtn', loadDidaktikReplay);
        wireClick('didaktikHintBtn', loadDidaktikHint);
        wireClick('didaktikStepBtn', runDidaktikStepCheck);
        wireClick('didaktikAnalyticsBtn', loadDidaktikAnalytics);
        const pathInput = $('didaktikPathId');
        if (pathInput) {
            pathInput.addEventListener('input', updateDidaktikExportLinks);
            updateDidaktikExportLinks();
        }
    });
})();

// ─────────── Rule-IDE: tree-local rule inspection ───────────
(() => {
    function $(id) { return document.getElementById(id); }

    function escapeHtml(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    let inspectData = null;
    let selectedPositionIndex = -1;
    let selectedMatchIndex = -1;
    let applyInFlight = false;

    function applyStatus(message, isError) {
        const status = $('inspectApplyStatus');
        if (!status) { return; }
        status.textContent = message || '';
        status.classList.toggle('error', !!isError);
        status.classList.toggle('ok', !!message && !isError);
    }

    function selectedPosition() {
        if (!inspectData || !inspectData.positions || selectedPositionIndex < 0) { return null; }
        return inspectData.positions[selectedPositionIndex] || null;
    }

    function selectedMatch() {
        const pos = selectedPosition();
        if (!pos || !pos.matches || selectedMatchIndex < 0) { return null; }
        return pos.matches[selectedMatchIndex] || null;
    }

    function syncApplyUi() {
        const applyBtn = $('inspectApplySelected');
        const label = $('inspectSelectedMatchLabel');
        const pos = selectedPosition();
        const match = selectedMatch();
        if (applyBtn) {
            applyBtn.disabled = applyInFlight || !match || !match.applicable;
        }
        if (!label) { return; }
        if (!pos || !match) {
            label.textContent = 'Kein Match ausgewählt.';
            return;
        }
        const applicability = match.applicable ? 'anwendbar' : 'nicht anwendbar';
        label.textContent = match.kind + ' #' + (selectedMatchIndex + 1) + ' (' + applicability + ')';
    }

    function selectPosition(index) {
        if (!inspectData || !inspectData.positions || index < 0 || index >= inspectData.positions.length) {
            selectedPositionIndex = -1;
            selectedMatchIndex = -1;
            return null;
        }
        selectedPositionIndex = index;
        inspectData.positions.forEach((pos, idx) => {
            pos.selected = idx === index;
        });
        const matches = inspectData.positions[index].matches || [];
        selectedMatchIndex = matches.findIndex((m) => m && m.applicable);
        if (selectedMatchIndex < 0) {
            selectedMatchIndex = matches.length > 0 ? 0 : -1;
        }
        return inspectData.positions[index];
    }

    function runInspect(expression, selectedPathKey) {
        const statusEl = $('inspectStatus');
        const resultEl = $('inspectResult');
        const matchPanel = $('inspectMatchPanel');
        if (!statusEl || !resultEl) { return; }
        statusEl.textContent = 'Lade …';
        resultEl.style.display = 'none';
        if (matchPanel) { matchPanel.style.display = 'none'; }
        applyStatus('');
        applyInFlight = false;

        const params = new URLSearchParams({ expression: expression });
        if (selectedPathKey) {
            params.set('selectedPathKey', selectedPathKey);
        }
        fetch('/api/inspect/tree?' + params.toString())
            .then((r) => r.ok ? r.json() : r.text().then((t) => Promise.reject(t)))
            .then((json) => {
                inspectData = json;
                const selected = (json.positions || []).findIndex((pos) => !!pos.selected);
                selectPosition(selected >= 0 ? selected : 0);
                statusEl.textContent = '';
                renderPositionList(json);
                const pos = selectedPosition();
                if (pos) {
                    renderMatchPanel(pos);
                }
                resultEl.style.display = '';
            })
            .catch((err) => {
                statusEl.textContent = 'Fehler: ' + String(err);
            });
    }

    function renderPositionList(json) {
        const listEl = $('inspectPositionList');
        if (!listEl) { return; }
        if (!json.positions || json.positions.length === 0) {
            listEl.innerHTML = '<p class="hint">Keine Regelmatches gefunden.</p>';
            return;
        }
        const ul = document.createElement('ul');
        ul.className = 'inspect-positions';
        json.positions.forEach((pos, idx) => {
            const li = document.createElement('li');
            const matchCount = pos.matches ? pos.matches.length : 0;
            const activeClass = pos.selected ? ' is-active' : '';
            li.innerHTML = '<button class="inspect-pos-btn' + activeClass + '" data-idx="' + idx + '"'
                + (pos.selected ? ' aria-current="true"' : '') + '>'
                + '<code>' + escapeHtml(pos.pathKey) + '</code>'
                + ' — <em>' + escapeHtml(pos.subtree) + '</em>'
                + ' <span class="badge">' + matchCount + ' Match' + (matchCount === 1 ? '' : 'es') + '</span>'
                + '</button>';
            ul.appendChild(li);
        });
        listEl.innerHTML = '';
        listEl.appendChild(ul);

        listEl.querySelectorAll('.inspect-pos-btn').forEach((btn) => {
            btn.addEventListener('click', () => {
                const idx = parseInt(btn.dataset.idx, 10);
                const pos = selectPosition(idx);
                renderPositionList(json);
                if (pos) {
                    renderMatchPanel(pos);
                }
            });
        });
    }

    function renderMatchPanel(pos) {
        const panel = $('inspectMatchPanel');
        const selectedPos = $('inspectSelectedPosition');
        const selectedSubtree = $('inspectSelectedSubtree');
        const matchList = $('inspectMatchList');
        if (!panel || !matchList) { return; }

        if (selectedPos) { selectedPos.textContent = pos.pathKey; }
        if (selectedSubtree) { selectedSubtree.textContent = pos.subtree; }
        applyStatus('');

        if (!pos.matches || pos.matches.length === 0) {
            matchList.innerHTML = '<p class="hint">Keine Regelmatches an dieser Position.</p>';
            selectedMatchIndex = -1;
        } else {
            let html = '';
            pos.matches.forEach((match, idx) => {
                const applicable = !!match.applicable;
                const selectedClass = idx === selectedMatchIndex ? ' selected' : '';
                html += '<div class="inspect-match' + selectedClass + '" data-match-idx="' + idx + '">';
                html += '<div class="inspect-match-header">'
                    + '<span class="badge-kind">' + escapeHtml(match.kind) + '</span>'
                    + ' <code class="inspect-enumerator">' + escapeHtml(match.enumeratorId) + '</code>'
                    + ' <span class="badge ' + (applicable ? 'badge-applicable' : 'badge-not-applicable') + '">'
                    + (applicable ? 'anwendbar' : 'nicht anwendbar') + '</span>'
                    + '</div>';
                html += '<div class="actions inspect-match-actions">'
                    + '<button type="button" class="inspect-select-match" data-match-idx="' + idx + '">Auswählen</button>'
                    + '<button type="button" class="primary inspect-apply-match" data-match-idx="' + idx + '"'
                    + (applicable ? '' : ' disabled') + '>Apply</button>'
                    + '</div>';

                // Rewrite preview
                const subtreeBefore = match.subtreeBefore || match.rewriteBefore;
                const subtreeAfter = match.subtreeAfter || match.rewriteAfter;
                if (subtreeAfter) {
                    html += '<div class="inspect-rewrite">'
                        + '<span class="inspect-rewrite-label">Teilbaum vorher:</span> <code>' + escapeHtml(subtreeBefore) + '</code>'
                        + ' → '
                        + '<span class="inspect-rewrite-label">Teilbaum nachher:</span> <code>' + escapeHtml(subtreeAfter) + '</code>'
                        + '</div>';
                    if (match.expressionAfter) {
                        html += '<div class="inspect-rewrite inspect-expression-after">'
                            + '<span class="inspect-rewrite-label">Gesamtausdruck nachher:</span> '
                            + '<code>' + escapeHtml(match.expressionAfter) + '</code>'
                            + '</div>';
                    }
                } else {
                    html += '<div class="inspect-rewrite hint">Kein konkreter Rewrite verfügbar.</div>';
                }

                // Bindings
                if (match.bindings && match.bindings.length > 0) {
                    html += '<table class="inspect-bindings"><thead><tr>'
                        + '<th>Name</th><th>Wert</th><th>Typ</th>'
                        + '</tr></thead><tbody>';
                    match.bindings.forEach((b) => {
                        html += '<tr>'
                            + '<td><code>' + escapeHtml(b.name) + '</code></td>'
                            + '<td><code>' + escapeHtml(b.value) + '</code></td>'
                            + '<td>' + escapeHtml(b.kind) + '</td>'
                            + '</tr>';
                    });
                    html += '</tbody></table>';
                }

                html += '</div>';
            });
            matchList.innerHTML = html;
            matchList.querySelectorAll('.inspect-select-match').forEach((btn) => {
                btn.addEventListener('click', () => {
                    selectedMatchIndex = parseInt(btn.dataset.matchIdx, 10);
                    renderMatchPanel(pos);
                });
            });
            matchList.querySelectorAll('.inspect-apply-match').forEach((btn) => {
                btn.addEventListener('click', () => {
                    selectedMatchIndex = parseInt(btn.dataset.matchIdx, 10);
                    syncApplyUi();
                    applySelectedMatch();
                });
            });
        }

        syncApplyUi();
        panel.style.display = '';
    }

    function applySelectedMatch() {
        const pos = selectedPosition();
        const match = selectedMatch();
        const exprEl = $('inspectExpression');
        if (!pos || !match) { return; }
        if (!match.applicable) {
            applyStatus('Dieses Match ist nicht anwendbar.', true);
            return;
        }
        const expression = inspectData && inspectData.expression
            ? inspectData.expression
            : (exprEl ? exprEl.value.trim() : '');
        applyInFlight = true;
        syncApplyUi();
        applyStatus('Wende Rewrite an …');
        fetch('/api/inspect/tree/apply', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                expression: expression,
                pathKey: pos.pathKey,
                matchId: match.matchId
            })
        })
            .then((r) => r.ok ? r.json() : r.text().then((t) => Promise.reject(t)))
            .then((json) => {
                inspectData = json.inspection;
                const nextExpression = json.expressionAfter || '';
                if (exprEl) {
                    exprEl.value = nextExpression;
                }
                const selected = (inspectData.positions || []).findIndex((p) => !!p.selected);
                const posAfter = selectPosition(selected >= 0 ? selected : 0);
                renderPositionList(inspectData);
                if (posAfter) {
                    renderMatchPanel(posAfter);
                }
                applyStatus('Rewrite angewendet: ' + (json.kind || match.kind), false);
            })
            .catch((err) => {
                applyStatus('Fehler beim Anwenden: ' + String(err), true);
            })
            .finally(() => {
                applyInFlight = false;
                syncApplyUi();
            });
    }

    document.addEventListener('DOMContentLoaded', () => {
        const form = $('inspectForm');
        if (form) {
            form.addEventListener('submit', (e) => {
                e.preventDefault();
                const exprEl = $('inspectExpression');
                const expression = exprEl ? exprEl.value.trim() : '';
                if (expression) { runInspect(expression, null); }
            });
        }
        const applyBtn = $('inspectApplySelected');
        if (applyBtn) {
            applyBtn.addEventListener('click', () => applySelectedMatch());
        }
    });
})();
