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
    loadCdnScript('https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js');

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
    async function runDemo(demoId, btn) {
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

    function renderDemoSummary(data) {
        const m = data.metrics || {};
        const best = data.bestPath || {};
        const selected = data.selectedPath || best;
        const identities = (data.identities || []).slice(0, 5);
        const targetReached = !!data.targetReached;
        const assumptions = data.assumptions || [];

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

        const proofTag = selected.validationStatus
            ? renderProofStatusBadge(selected.validationStatus)
            : '';
        const assumptionsBlock = assumptions.length
            ? '<h4>Annahmen</h4><ul>' + assumptions.map((a) =>
                '<li><code>' + escapeHtml(a) + '</code></li>').join('') + '</ul>'
            : '';
        const bestMove = (selected.steps && selected.steps.length)
            ? selected.steps[0]
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
                      + ' (' + (selected.steps ? selected.steps.length : 0) + ' Schritte, Verbesserung '
                      + (selected.totalImprovement || 0) + ')'
                    : '–'],
            ['Proof-Status', selected.validationStatus || '–'],
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
            + (proofTag ? '<p>Proof-Status des selektierten Pfades: ' + proofTag + '</p>' : '')
            + bestMoveBlock
            + assumptionsBlock
            + '<table>' + tableRows + '</table>'
            + idList
            + '<div class="demo-actions">' + linkList + '</div>';
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
        const form = event.target;
        const domains = Array.from(form.querySelectorAll('input[name="domain"]:checked')).map((c) => c.value);
        const payload = {
            expression: form.expression.value,
            type: form.type.value,
            profile: form.profile.value,
            domains: domains
        };
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
                { selector: 'node', style: { 'label': 'data(label)', 'font-size': 10, 'background-color': '#3b82f6', 'color': '#fff', 'text-valign': 'center', 'text-halign': 'center' } },
                { selector: 'node[?payload.isBest]', style: { 'background-color': '#10b981' } },
                { selector: 'node[?payload.isDeadEnd]', style: { 'background-color': '#9ca3af' } },
                { selector: 'edge', style: { 'label': 'data(label)', 'font-size': 8, 'curve-style': 'bezier', 'target-arrow-shape': 'triangle' } }
            ],
            layout: { name: 'breadthfirst', spacingFactor: 1.2 }
        });
        cy.on('tap', 'node', evt => showInspector(evt.target.data('payload')));
        cy.on('tap', 'edge', evt => showInspector(evt.target.data('payload')));
        if (inspector) {
            inspector.style.display = 'block';
            inspector.innerHTML = '<em>Klicke auf einen Knoten oder eine Kante, um Details anzuzeigen.</em>';
        }
    }

    function showInspector(payload) {
        const inspector = $('graphInspector');
        if (!inspector) return;
        const rows = Object.entries(payload || {}).map(([k, v]) =>
            `<div><strong>${escapeHtml(k)}:</strong> ${escapeHtml(typeof v === 'object' ? JSON.stringify(v) : String(v))}</div>`);
        inspector.innerHTML = rows.join('');
        if (payload && payload.latex && window.MathJax && window.MathJax.typesetPromise) {
            inspector.innerHTML += '<div class="latex">$' + payload.latex + '$</div>';
            window.MathJax.typesetPromise([inspector]).catch(() => {});
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
            host.innerHTML = scenarios.map(renderBenchmarkScenario).join('')
                + '<p class="hint">Gesamtlaufzeit: ' + (data.elapsedMillis || 0) + ' ms</p>';
        } catch (ex) {
            host.innerHTML = '<div class="status error">Netzwerkfehler: ' + ex + '</div>';
        }
    }
    function renderBenchmarkScenario(scenario) {
        const rows = (scenario.results || []).map((r) => {
            const foundLabel = r.found
                ? '<span class="benchmark-ok">ja</span>'
                : '<span class="benchmark-miss">nein</span>';
            return '<tr>'
                + '<td><code>' + escapeHtml(r.strategy || '') + '</code></td>'
                + '<td><code>' + escapeHtml(r.expression || '') + '</code></td>'
                + '<td>' + foundLabel + '</td>'
                + '<td>' + (r.elapsedMillis != null ? r.elapsedMillis + ' ms' : '–') + '</td>'
                + '<td>' + (r.exploredStates || 0) + '</td>'
                + '<td>' + (r.expandedSteps || 0) + '</td>'
                + '<td>' + (r.distinctRules || 0) + '</td>'
                + '<td>' + renderProofStatusBadge(r.proofStatus) + '</td>'
                + '</tr>';
        }).join('');
        return '<div class="card benchmark-scenario">'
            + '<h3>Szenario: <code>' + escapeHtml(scenario.name || '') + '</code></h3>'
            + '<table class="benchmark-table">'
            + '<thead><tr>'
            + '<th>Strategie</th><th>Ausdruck</th><th>Gefunden</th><th>Laufzeit</th>'
            + '<th>Besuchte Zustände</th><th>Schritte</th><th>Regeln</th><th>Proof-Status</th>'
            + '</tr></thead>'
            + '<tbody>' + rows + '</tbody>'
            + '</table>'
            + '</div>';
    }

    /* ─── Replay tab ─── */
    let replayState = { steps: [], index: 0, timer: null };
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
        canvas.innerHTML = '<div class="replay-step">'
            + '<div class="replay-step-index">Schritt ' + (step.stepIndex + 1)
            + ' / ' + replayState.steps.length + '</div>'
            + '<div class="replay-from"><strong>Vorher:</strong> '
            + '<code>' + escapeHtml(step.fromExpression) + '</code><br>'
            + '<span class="latex">$' + escapeHtml(step.fromLatex) + '$</span></div>'
            + '<div class="replay-to"><strong>Nachher:</strong> '
            + '<code>' + escapeHtml(step.toExpression) + '</code><br>'
            + '<span class="latex">$' + escapeHtml(step.toLatex) + '$</span></div>'
            + '<div class="replay-rule"><strong>Regel:</strong> <code>'
            + escapeHtml(step.ruleId) + '</code></div>'
            + '<div class="replay-explanation"><pre>'
            + escapeHtml(step.ruleExplanation || '') + '</pre></div>'
            + '<div class="hint">Δ Komplexität: ' + step.scoreDelta
            + ' · Äquivalenzerhaltend: ' + step.equivalencePreserving + '</div>'
            + '</div>';
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
                }
            });
        });
    });
})();
