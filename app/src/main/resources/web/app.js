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
    // Optional CDN libraries for the interactive Cytoscape graph view and
    // MathJax-based inline LaTeX. Loaded dynamically so the static HTML
    // contains no third-party <script src=...> tags (avoids SRI churn and
    // means the workbench works offline with the Mermaid fallback).
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
    loadCdnScript('https://unpkg.com/cytoscape@3.28.1/dist/cytoscape.min.js');
    // KaTeX is preferred for math typesetting (fast, App-feel). MathJax is
    // loaded as a fallback for constructs that KaTeX does not support, and
    // also as the renderer used by legacy call sites until they are migrated
    // to the central renderMath(root) pipeline. The KaTeX stylesheet is
    // injected so the page works without a static <link> in index.html.
    function loadCdnStylesheet(href) {
        return new Promise((resolve) => {
            const l = document.createElement('link');
            l.rel = 'stylesheet';
            l.href = href;
            l.crossOrigin = 'anonymous';
            l.referrerPolicy = 'no-referrer';
            l.onload = () => resolve(true);
            l.onerror = () => resolve(false);
            document.head.appendChild(l);
        });
    }
    loadCdnStylesheet('https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css');
    loadCdnScript('https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js').then((ok) => {
        if (!ok) { return; }
        loadCdnScript('https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/contrib/auto-render.min.js').then(() => {
            // Typeset whatever is already on screen once KaTeX is ready.
            window.renderMath(document.body);
        });
    });
    loadCdnScript('https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js');

    /**
     * Central math typesetter. Walks `root` for nodes carrying inline LaTeX
     * (`[data-math]`, `.math`, or the legacy `.latex` class) and renders
     * them via KaTeX when available, falling back to MathJax, and finally
     * leaving the raw LaTeX visible inside a `<code>` block tagged
     * `math-fallback` so the formula remains legible without any CDN.
     *
     * All UI surfaces (replay, demo summary, search-graph inspector,
     * matrix preview, hints, proof panel, export preview) must call this
     * helper instead of invoking MathJax directly so the rendering path
     * stays uniform.
     */
    window.renderMath = function renderMath(root) {
        if (!root) { return; }
        const nodes = root.querySelectorAll('[data-math], .math, .latex');
        if (typeof window.renderMathInElement === 'function') {
            try {
                window.renderMathInElement(root, {
                    delimiters: [
                        { left: '$$', right: '$$', display: true },
                        { left: '$', right: '$', display: false },
                        { left: '\\(', right: '\\)', display: false },
                        { left: '\\[', right: '\\]', display: true }
                    ],
                    // Stage 3: enable KaTeX trust mode so the
                    // `\htmlClass{diff-old|diff-new}{…}` markers emitted
                    // by `MathPresentation.alignedDerivationLatexWithDiff`
                    // survive into the rendered DOM as styleable spans.
                    trust: true,
                    strict: 'ignore',
                    throwOnError: false
                });
                return;
            } catch (_) {
                // fall through to MathJax / plain fallback below
            }
        }
        if (window.MathJax && typeof window.MathJax.typesetPromise === 'function') {
            window.MathJax.typesetPromise([root]).catch(() => {});
            return;
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
            // Refresh the existing panels so users see graph/replay immediately.
            if (typeof loadPaths === 'function') { loadPaths().catch(() => {}); }
            if (typeof loadIdentities === 'function') { loadIdentities().catch(() => {}); }
            const graphBtn = $('reloadGraph');
            if (graphBtn) { graphBtn.click(); }
        } catch (ex) {
            status.className = 'status error';
            status.textContent = 'Netzwerkfehler: ' + ex;
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
        const banner = targetReached
            ? '<div class="status ok demo-banner">'
                + '<strong>Identität erkannt:</strong> '
                + escapeHtml(selected.originalExpression || data.expression || '')
                + ' = '
                + escapeHtml(selected.improvedExpression || '')
                + '</div>'
            : '<div class="status warn demo-banner">'
                + '<strong>Keine Identität gefunden.</strong> Bester gefundener Umformungsweg: '
                + (selected.improvedExpression
                    ? escapeHtml(selected.originalExpression || data.expression || '')
                      + ' → '
                      + escapeHtml(selected.improvedExpression)
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
        const bestMoveBlock = bestMove
            ? '<h4>Best Move</h4><p><code>' + escapeHtml(bestMove.beforeExpression || '')
                + ' → ' + escapeHtml(bestMove.afterExpression || '')
                + '</code> · Regel <code>' + escapeHtml(bestMove.ruleId || '') + '</code></p>'
            : '';

        const rows = [
            ['Eingabe', data.expression || ''],
            ['Profil', data.profile || ''],
            ['Treffer (selectedPath)',
                selected.improvedExpression
                    ? selected.originalExpression + ' → ' + selected.improvedExpression
                      + ' (' + stepCount + ' Schritte, Verbesserung '
                      + (selected.totalImprovement || 0) + ')'
                    : '–'],
            ['Proof-Status', proofStatus || '–'],
            ['Erwartete Identität', data.expectedHighlight || ''],
            ['Knoten / Kanten', (m.nodes || 0) + ' / ' + (m.edges || 0)],
            ['Pfade entdeckt', m.pathsDiscovered || 0],
            ['Identitäten gefunden', m.identitiesFound || 0],
            ['Laufzeit', (m.elapsedMillis || 0) + ' ms']
        ];
        const tableRows = rows.map((r) =>
            '<tr><th>' + escapeHtml(r[0]) + '</th><td>' + escapeHtml(String(r[1])) + '</td></tr>').join('');
        const idList = identities.length
            ? '<h4>Erkannte Identitäten</h4><ul>' + identities.map((i) =>
                '<li><code>' + escapeHtml(i.leftPattern) + ' → ' + escapeHtml(i.rightPattern)
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
        const out = $('graphOutput');
        const canvas = $('graphCanvas');
        const inspector = $('graphInspector');
        const filter = $('graphFilter') && $('graphFilter').value || '';
        const interactive = $('graphInteractive') && $('graphInteractive').checked;
        const filterQuery = filter ? ('?filter=' + encodeURIComponent(filter)) : '';
        const source = $('graphSource') && $('graphSource').value || 'search-graph';
        out.textContent = 'Lade …';
        if (canvas) canvas.style.display = 'none';
        if (inspector) { inspector.style.display = 'none'; inspector.innerHTML = ''; }
        try {
            if (interactive && source === 'search-graph' && typeof cytoscape === 'function' && !window.__cytoscapeFailed) {
                const response = await fetch('/api/search-graph' + filterQuery);
                const data = await response.json();
                renderCytoscape(data);
                out.textContent = '(Interaktive Cytoscape-Ansicht aktiv – Mermaid-Quelltext unten ist Fallback.)';
                const mermaidResp = await fetch('/api/exports/search-graph.mmd' + filterQuery);
                out.textContent = (await mermaidResp.text());
                return;
            }
            const url = source === 'search-graph' ? ('/api/exports/search-graph.mmd' + filterQuery) : '/api/graph';
            const response = await fetch(url);
            out.textContent = await response.text();
        } catch (ex) {
            out.textContent = 'Fehler: ' + ex;
        }
    });

    function renderCytoscape(graph) {
        const canvas = $('graphCanvas');
        const inspector = $('graphInspector');
        if (!canvas || typeof cytoscape !== 'function') {
            return;
        }
        canvas.style.display = 'block';
        canvas.innerHTML = '';
        const elements = [];
        (graph.nodes || []).forEach(n => elements.push({ data: { id: n.id, label: n.expression, payload: n } }));
        (graph.edges || []).forEach(e => elements.push({
            data: { id: e.from + '->' + e.to + ':' + e.ruleId, source: e.from, target: e.to, label: e.ruleId, payload: e }
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
                { selector: 'node[?payload.isDeadEnd]', style: { 'background-color': '#9ca3af' } },
                { selector: 'edge', style: { 'label': 'data(label)', 'font-size': 8, 'curve-style': 'bezier', 'target-arrow-shape': 'triangle' } }
            ],
            layout: { name: 'breadthfirst', spacingFactor: 1.2 }
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
        if (inspector) {
            inspector.style.display = 'block';
            inspector.innerHTML = '<em>Klicke auf einen Knoten oder eine Kante, um Details anzuzeigen.</em>';
        }
    }

    /**
     * Stage 4 — KaTeX graph-node HTML overlays. Renders each Cytoscape
     * node's expression (via `payload.expressionLatex`) as an absolutely
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
                    || payload.latex
                    || payload.expression
                    || id;
                if (!host) {
                    host = document.createElement('div');
                    host.className = 'graph-node-math';
                    host.setAttribute('data-node-id', id);
                    host.setAttribute('data-math', '$' + latex + '$');
                    host.textContent = '$' + latex + '$';
                    if (payload.expression) {
                        host.setAttribute('aria-label', String(payload.expression));
                    }
                    layer.appendChild(host);
                }
                if (payload.isBest) { host.classList.add('is-best'); } else { host.classList.remove('is-best'); }
                if (payload.isDeadEnd) { host.classList.add('is-dead-end'); } else { host.classList.remove('is-dead-end'); }
                const box = projectNode(node);
                // Use translate3d so the GPU compositor can animate the
                // CSS transition smoothly; the matching `.graph-node-math`
                // CSS rule defines `transition: transform 200ms ease`.
                host.style.transform = 'translate3d(' + (box.x + box.w / 2) + 'px,'
                    + (box.y + box.h / 2) + 'px, 0) translate(-50%, -50%)';
            });
            // Optional edge captions.
            if (showEdges) {
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
                        host.setAttribute('data-math', '$' + latex + '$');
                        host.textContent = '$' + latex + '$';
                        layer.appendChild(host);
                    }
                    const bb = edge.renderedBoundingBox();
                    const cx = (bb.x1 + bb.x2) / 2;
                    const cy2 = (bb.y1 + bb.y2) / 2;
                    host.style.transform = 'translate3d(' + cx + 'px,' + cy2 + 'px, 0) translate(-50%, -50%)';
                });
            }
            // Garbage-collect overlays for removed elements.
            layer.querySelectorAll('[data-node-id]').forEach((host) => {
                if (!nodeIds.has(host.getAttribute('data-node-id'))) {
                    host.remove();
                }
            });
            // Route every freshly added/updated math host through the
            // central renderMath() pipeline so KaTeX takes over.
            window.renderMath(layer);
        }
        function cssEscape(value) {
            if (typeof CSS !== 'undefined' && typeof CSS.escape === 'function') {
                return CSS.escape(value);
            }
            return String(value).replace(/[^a-zA-Z0-9_-]/g, (c) => '\\' + c);
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

    function showInspector(payload) {
        const inspector = $('graphInspector');
        if (!inspector) return;
        const rows = Object.entries(payload || {}).map(([k, v]) =>
            `<div><strong>${escapeHtml(k)}:</strong> ${escapeHtml(typeof v === 'object' ? JSON.stringify(v) : String(v))}</div>`);
        inspector.innerHTML = rows.join('');
        if (payload && payload.latex) {
            inspector.innerHTML += '<div class="math" data-math="$' + payload.latex + '$">$' + payload.latex + '$</div>';
        }
        window.renderMath(inspector);
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
            const tileData = [
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
    let replayState = { steps: [], index: 0, timer: null, alignedDerivationLatex: '' };
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
            replayState.index = 0;
            renderReplayStep();
        } catch (ex) {
            $('replayCanvas').innerHTML = '<div class="hint">Fehler: ' + ex + '</div>';
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
        // Stage 3: prefer the diff-annotated derivation block when the
        // backend provides it (alignedDerivationLatexWithDiff) so changed
        // tokens are colour-coded inline. Falls back to the plain block.
        const derivationBlock = renderAlignedDerivationBlock(
            replayState.alignedDerivationLatexWithDiff
                || replayState.alignedDerivationLatex,
            replayState.index);
        // Per-step: wrap changed spans in the from/to LaTeX in
        // \htmlClass{diff-old|diff-new}{…} so the per-step view shows
        // the same colour-diff highlight inline. KaTeX trust mode is
        // already enabled in renderMath().
        const fromDiff = wrapDiffLatex(step.fromLatex || '',
            step.changedFromSpans, 'diff-old');
        const toDiff = wrapDiffLatex(step.toLatex || '',
            step.changedToSpans, 'diff-new');
        const fromInline = '$' + fromDiff + '$';
        const toInline = '$' + toDiff + '$';
        canvas.innerHTML = derivationBlock
            + '<div class="replay-step">'
            + '<div class="replay-step-index">Schritt ' + (step.stepIndex + 1)
            + ' / ' + replayState.steps.length + '</div>'
            + '<div class="replay-from"><strong>Vorher:</strong> '
            + '<code>' + escapeHtml(step.fromExpression) + '</code><br>'
            + '<span class="math" data-math="' + escapeHtml(fromInline) + '">' + escapeHtml(fromInline) + '</span></div>'
            + '<div class="replay-to"><strong>Nachher:</strong> '
            + '<code>' + escapeHtml(step.toExpression) + '</code><br>'
            + '<span class="math" data-math="' + escapeHtml(toInline) + '">' + escapeHtml(toInline) + '</span></div>'
            + '<div class="replay-rule"><strong>Regel:</strong> <code>'
            + escapeHtml(ruleId) + '</code></div>'
            + extras
            + '<div class="replay-explanation"><pre>'
            + escapeHtml(step.ruleExplanation || '') + '</pre></div>'
            + '<div class="hint">Δ Komplexität: ' + step.scoreDelta
            + ' · Äquivalenzerhaltend: ' + step.equivalencePreserving + '</div>'
            + '</div>';
        window.renderMath(canvas);
    }

    /**
     * Stage 3 — wraps the given `[start, length]` character spans of
     * `latex` in `\htmlClass{<cssClass>}{…}` so KaTeX (with trust mode)
     * surfaces them as colour-diff highlights in the rendered DOM.
     * Mirrors `MathPresentation.wrapDiff(...)` on the server side so the
     * per-step inline view matches the aligned-derivation block.
     */
    function wrapDiffLatex(latex, spans, cssClass) {
        if (!latex || !spans || !spans.length) { return latex || ''; }
        const norm = [];
        for (const span of spans) {
            if (!span || span.length < 2) { continue; }
            const start = Math.max(0, span[0] | 0);
            const end = Math.min(latex.length, start + (span[1] | 0));
            if (end <= start) { continue; }
            norm.push([start, end]);
        }
        if (!norm.length) { return latex; }
        norm.sort((a, b) => a[0] - b[0]);
        const merged = [norm[0].slice()];
        for (let i = 1; i < norm.length; i++) {
            const last = merged[merged.length - 1];
            if (norm[i][0] <= last[1]) {
                last[1] = Math.max(last[1], norm[i][1]);
            } else {
                merged.push(norm[i].slice());
            }
        }
        let out = '';
        let cursor = 0;
        for (const [s, e] of merged) {
            if (s > cursor) { out += latex.substring(cursor, s); }
            out += '\\htmlClass{' + cssClass + '}{' + latex.substring(s, e) + '}';
            cursor = e;
        }
        if (cursor < latex.length) { out += latex.substring(cursor); }
        return out;
    }

    /**
     * Stage 2: render the whole derivation as one `\begin{aligned}` block
     * with a highlighted row for the currently focused step. The block is
     * provided by the backend (PathReplayDto.alignedDerivationLatex) so
     * the same rule-arrow style is reused across server-rendered
     * exports and the interactive UI.
     */
    function renderAlignedDerivationBlock(latex, focusIndex) {
        if (!latex) return '';
        const display = '$$' + latex + '$$';
        // Stage 3: focused step gets a row-highlight class on the wrapper
        // so the CSS can scope the .replay-derivation-focus accent rule
        // (KaTeX renders the aligned block as a single math node, so the
        // class lives on the wrapper rather than per-row).
        return '<div class="replay-derivation-block replay-derivation-focus" data-focus-step="' + focusIndex + '">'
            + '<div class="replay-derivation-title">Rechenweg</div>'
            + '<div class="math replay-derivation-math" data-math="' + escapeHtml(display) + '">'
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
        return out.join('');
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
