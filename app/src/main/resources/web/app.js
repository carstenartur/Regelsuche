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
        out.textContent = 'Lade …';
        try {
            const source = $('graphSource') && $('graphSource').value || 'search-graph';
            const url = source === 'search-graph' ? '/api/exports/search-graph.mmd' : '/api/graph';
            const response = await fetch(url);
            out.textContent = await response.text();
        } catch (ex) {
            out.textContent = 'Fehler: ' + ex;
        }
    });

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
