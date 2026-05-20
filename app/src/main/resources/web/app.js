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
        if (!parsed || !parsed.paths || !parsed.paths.length) {
            out.innerHTML = '<div class="hint">Keine Pfade verfügbar. Starte zuerst eine Suche.</div>';
            return;
        }
        out.innerHTML = '';
        parsed.paths.forEach((path) => {
            const div = document.createElement('div');
            div.className = 'list-item clickable';
            const title = document.createElement('h4');
            title.textContent = (path.originalExpression || '?') + ' → ' + (path.simplifiedExpression || '?');
            div.appendChild(title);
            const meta = document.createElement('div');
            meta.className = 'meta';
            meta.textContent = 'Tiefe ' + (path.depth || 0)
                + ' · Verbesserung ' + (path.improvement || 0)
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
        out.textContent = 'Lade …';
        try {
            const response = await fetch('/api/graph');
            out.textContent = await response.text();
        } catch (ex) {
            out.textContent = 'Fehler: ' + ex;
        }
    });

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
                }
            });
        });
    });
})();
