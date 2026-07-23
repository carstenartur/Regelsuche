(() => {
    'use strict';

    const panel = document.getElementById('tab-ruleIde');
    if (!panel) { return; }

    panel.innerHTML = `
        <h2>AST-Regelradar</h2>
        <p class="hint">Der Baum zeigt einen vollständigen Ausdruckszustand. Die gleichmäßig verteilten Punkte um einen Knoten sind konkrete, an genau dieser AST-Position gefundene Regelanwendungen. Die mathematische Ermittlung erfolgt vollständig im Backend.</p>
        <div class="radar-layout">
            <section class="card radar-controls" aria-labelledby="radar-input-title">
                <h3 id="radar-input-title">Ausdruck und Regelkontext</h3>
                <label class="field"><span>Ausdruck</span>
                    <input id="radarExpression" type="text" value="(x + 1)^2 + 0" autocomplete="off">
                </label>
                <label class="field"><span>Zielausdruck (optional)</span>
                    <input id="radarGoal" type="text" placeholder="z. B. x^2 + 2*x + 1">
                </label>
                <div class="radar-control-grid">
                    <label class="field"><span>Knowledge-Pack-Profil</span>
                        <select id="radarKnowledgeProfile">
                            <option value="CORE" selected>CORE</option>
                            <option value="CORE_PLUS_SYMPY_POLYNOMIAL">CORE_PLUS_SYMPY_POLYNOMIAL</option>
                            <option value="EXPLORATORY">EXPLORATORY</option>
                            <option value="ALL">ALL</option>
                        </select>
                    </label>
                    <label class="field"><span>Makro-Mindeststatus</span>
                        <select id="radarMacroStatus">
                            <option value="OBSERVED">OBSERVED</option>
                            <option value="VALIDATED_BY_EXAMPLES" selected>VALIDATED_BY_EXAMPLES</option>
                            <option value="SYMBOLICALLY_VERIFIED">SYMBOLICALLY_VERIFIED</option>
                            <option value="FORMALLY_PROVABLE">FORMALLY_PROVABLE</option>
                            <option value="FORMALLY_PROVED">FORMALLY_PROVED</option>
                        </select>
                    </label>
                    <label class="field"><span>Kandidaten je Knoten</span>
                        <input id="radarMaxPerNode" type="number" min="1" max="200" value="24">
                    </label>
                    <label class="field"><span>Kandidaten gesamt</span>
                        <input id="radarMaxTotal" type="number" min="1" max="2000" value="240">
                    </label>
                </div>
                <fieldset class="radar-options">
                    <legend>Aktive Regelquellen</legend>
                    <label><input id="radarPlugins" type="checkbox" checked> Regeldateien und Plugins</label>
                    <label><input id="radarMacros" type="checkbox" checked> qualifizierte gelernte Makros</label>
                    <label><input id="radarRejected" type="checkbox" checked> abgelehnte Treffer auditierbar anzeigen</label>
                </fieldset>
                <label class="field"><span>Geltende Annahmen (eine pro Zeile)</span>
                    <textarea id="radarAssumptions" rows="3" placeholder="z. B. x != 0"></textarea>
                </label>
                <div class="actions">
                    <button id="radarInspect" class="primary" type="button">Radar berechnen</button>
                    <button id="radarUndo" type="button" disabled>Rückgängig</button>
                    <button id="radarZoomOut" type="button" aria-label="Darstellung verkleinern">−</button>
                    <button id="radarZoomIn" type="button" aria-label="Darstellung vergrößern">+</button>
                </div>
                <div id="radarStatus" class="status" role="status" aria-live="polite"></div>
            </section>

            <section class="card radar-origin-filter" aria-labelledby="radar-filter-title">
                <h3 id="radar-filter-title">Darstellung filtern</h3>
                <p class="hint">Filter verändern nur die Darstellung, niemals den vom Backend gemeldeten Kandidatenbestand.</p>
                <div id="radarOriginFilters" class="radar-origin-filters"></div>
                <div class="radar-legend" aria-label="Legende der Regelherkünfte">
                    <span><i class="radar-symbol origin-core">●</i> Grundregel</span>
                    <span><i class="radar-symbol origin-knowledge_pack">◆</i> Knowledge Pack</span>
                    <span><i class="radar-symbol origin-rule_file">■</i> Regeldatei</span>
                    <span><i class="radar-symbol origin-plugin">▲</i> Plugin</span>
                    <span><i class="radar-symbol origin-learned_macro">★</i> gelernte Makroregel</span>
                </div>
            </section>
        </div>

        <section class="card radar-tree-card" aria-labelledby="radar-tree-title">
            <div class="radar-tree-heading">
                <div><h3 id="radar-tree-title">Ausdrucks-AST mit lokalen Regelkreisen</h3>
                <p id="radarTreeSummary" class="hint"></p></div>
            </div>
            <div id="radarTreeViewport" class="radar-tree-viewport" tabindex="0" aria-label="Zoom- und scrollbar dargestellter Ausdrucksbaum">
                <svg id="radarTree" class="radar-tree" role="tree" aria-labelledby="radar-tree-title"></svg>
            </div>
        </section>

        <div class="radar-detail-grid">
            <section id="radarCandidatePanel" class="card" aria-labelledby="radar-candidate-title">
                <h3 id="radar-candidate-title">Ausgewählte Regelanwendung</h3>
                <div id="radarCandidateDetail" class="radar-candidate-detail"><p class="hint">Einen Punkt im AST oder eine Tabellenzeile auswählen.</p></div>
                <div class="actions">
                    <label id="radarAssumptionAckLabel" class="radar-assumption-ack" hidden>
                        <input id="radarAssumptionAck" type="checkbox"> Angezeigte Annahmen für diesen manuellen Schritt bestätigen
                    </label>
                    <button id="radarApply" class="primary" type="button" disabled>Ausgewählten Schritt anwenden</button>
                </div>
                <div id="radarApplyStatus" class="status" role="status" aria-live="polite"></div>
            </section>

            <section class="card" aria-labelledby="radar-search-title">
                <h3 id="radar-search-title">Begrenzte Suche mit Kandidatenkorrelation</h3>
                <div class="radar-control-grid">
                    <label class="field"><span>Maximale Tiefe</span><input id="radarSearchDepth" type="number" min="1" max="12" value="4"></label>
                    <label class="field"><span>Maximale Zustände</span><input id="radarSearchStates" type="number" min="1" max="2000" value="120"></label>
                    <label class="field"><span>Züge je Zustand</span><input id="radarSearchMoves" type="number" min="1" max="500" value="60"></label>
                </div>
                <button id="radarRunSearch" type="button">Lokalen Suchgraph erzeugen</button>
                <div id="radarSearchSummary" class="status" role="status" aria-live="polite"></div>
                <div id="radarSearchGraph" class="radar-search-graph"></div>
            </section>
        </div>

        <section class="card" aria-labelledby="radar-table-title">
            <h3 id="radar-table-title">Vollständige zugängliche Kandidatenliste</h3>
            <p class="hint">Die Tabelle enthält dieselben Backend-Kandidaten wie die Punkte. Sie bleibt auch ohne SVG- oder Farbwahrnehmung vollständig bedienbar.</p>
            <div class="radar-table-wrap"><table class="radar-table">
                <thead><tr><th>Position</th><th>Herkunft</th><th>Regel</th><th>Ergebnis</th><th>Status</th><th>Aktion</th></tr></thead>
                <tbody id="radarCandidateRows"></tbody>
            </table></div>
        </section>`;

    const ORIGINS = ['CORE', 'KNOWLEDGE_PACK', 'RULE_FILE', 'PLUGIN', 'LEARNED_MACRO'];
    const ORIGIN_LABELS = {
        CORE: 'Grundregeln',
        KNOWLEDGE_PACK: 'Knowledge Packs',
        RULE_FILE: 'Regeldateien',
        PLUGIN: 'Plugins',
        LEARNED_MACRO: 'Gelernte Makros'
    };
    const state = {
        snapshot: null,
        selectedId: '',
        originVisible: new Set(ORIGINS),
        undo: [],
        zoom: 1,
        search: null
    };

    const $ = (id) => document.getElementById(id);
    const esc = (value) => String(value == null ? '' : value)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#039;');

    function context(extra = {}) {
        const outcomes = extra.outcomeByCandidateId || {};
        return {
            knowledgeProfile: $('radarKnowledgeProfile').value,
            enabledPacks: [],
            disabledPacks: [],
            includePlugins: $('radarPlugins').checked,
            includeLearnedMacros: $('radarMacros').checked,
            minMacroProofStatus: $('radarMacroStatus').value,
            searchProfile: 'DISCOVERY',
            goalExpression: $('radarGoal').value.trim(),
            maxCandidatesPerPosition: numberValue('radarMaxPerNode', 24),
            maxCandidatesTotal: numberValue('radarMaxTotal', 240),
            assumptions: $('radarAssumptions').value.split(/\r?\n/).map(v => v.trim()).filter(Boolean),
            includeRejectedCandidates: $('radarRejected').checked,
            selectedCandidateId: extra.selectedCandidateId || state.selectedId || '',
            outcomeByCandidateId: outcomes
        };
    }

    function numberValue(id, fallback) {
        const parsed = parseInt($(id).value, 10);
        return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
    }

    function post(path, body) {
        return fetch(path, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(body)
        }).then(async response => {
            const text = await response.text();
            let json;
            try { json = text ? JSON.parse(text) : {}; } catch (error) { throw new Error(text || response.statusText); }
            if (!response.ok) { throw new Error((json.code ? json.code + ': ' : '') + (json.message || response.statusText)); }
            return json;
        });
    }

    function inspect(options = {}) {
        const expression = (options.expression != null ? options.expression : $('radarExpression').value).trim();
        if (!expression) { setStatus('Bitte einen Ausdruck eingeben.', true); return Promise.resolve(); }
        setStatus('AST und lokale Regelanwendungen werden im Backend berechnet …');
        return post('/api/rule-radar/inspect', {
            expression,
            context: context(options)
        }).then(snapshot => {
            state.snapshot = snapshot;
            state.selectedId = options.selectedCandidateId || '';
            $('radarExpression').value = snapshot.expression || expression;
            render();
            if (!snapshot.valid) {
                setStatus((snapshot.diagnostics || []).map(d => d.message).join(' · ') || 'Ausdruck ist ungültig.', true);
            } else {
                const t = snapshot.truncation || {};
                setStatus(`${snapshot.nodes.length} AST-Knoten, ${t.returnedCandidateCount || 0} sichtbare von ${t.generatedCandidateCount || 0} konkreten Anwendungen${t.truncated ? ' (Budgetgrenze sichtbar ausgewiesen)' : ''}.`);
            }
        }).catch(error => setStatus('Fehler: ' + error.message, true));
    }

    function setStatus(message, error = false) {
        const el = $('radarStatus');
        el.textContent = message;
        el.classList.toggle('error', error);
    }

    function render() {
        renderTree();
        renderTable();
        renderCandidateDetail();
        $('radarUndo').disabled = state.undo.length === 0;
    }

    function visibleCandidates() {
        return ((state.snapshot && state.snapshot.candidates) || [])
            .filter(candidate => state.originVisible.has(candidate.origin));
    }

    function renderOriginFilters() {
        const host = $('radarOriginFilters');
        host.innerHTML = ORIGINS.map(origin => `<label><input type="checkbox" data-origin="${origin}" checked> ${esc(ORIGIN_LABELS[origin])}</label>`).join('');
        host.querySelectorAll('input[data-origin]').forEach(input => {
            input.addEventListener('change', () => {
                if (input.checked) { state.originVisible.add(input.dataset.origin); }
                else { state.originVisible.delete(input.dataset.origin); }
                renderTree(); renderTable();
            });
        });
    }

    function renderTree() {
        const svg = $('radarTree');
        const snapshot = state.snapshot;
        if (!snapshot || !snapshot.valid || !snapshot.nodes.length) {
            svg.innerHTML = '';
            svg.setAttribute('viewBox', '0 0 900 240');
            $('radarTreeSummary').textContent = 'Noch kein gültiger AST geladen.';
            return;
        }
        const nodes = snapshot.nodes;
        const byKey = new Map(nodes.map(node => [node.pathKey, node]));
        const positions = layoutTree(byKey);
        const maxDepth = Math.max(...nodes.map(node => node.depth));
        const maxX = Math.max(...Array.from(positions.values()).map(pos => pos.x), 800) + 110;
        const height = Math.max(300, 130 + maxDepth * 150);
        svg.setAttribute('viewBox', `0 0 ${maxX} ${height}`);
        svg.style.width = `${maxX * state.zoom}px`;
        svg.style.height = `${height * state.zoom}px`;

        const candidates = visibleCandidates();
        const candidatesByPath = new Map();
        candidates.forEach(candidate => {
            if (!candidatesByPath.has(candidate.pathKey)) { candidatesByPath.set(candidate.pathKey, []); }
            candidatesByPath.get(candidate.pathKey).push(candidate);
        });
        candidatesByPath.forEach(list => list.sort((a, b) => a.orderingKey.localeCompare(b.orderingKey)));

        const edges = [];
        nodes.forEach(node => {
            const from = positions.get(node.pathKey);
            (node.childPathKeys || []).forEach(childKey => {
                const to = positions.get(childKey);
                if (from && to) { edges.push(`<line class="radar-ast-edge" x1="${from.x}" y1="${from.y}" x2="${to.x}" y2="${to.y}"></line>`); }
            });
        });

        const nodeMarkup = nodes.map(node => {
            const pos = positions.get(node.pathKey);
            const local = candidatesByPath.get(node.pathKey) || [];
            const radius = 43 + Math.min(24, local.length * 1.2);
            const points = local.map((candidate, index) => {
                const angle = -Math.PI / 2 + (2 * Math.PI * index / Math.max(1, local.length));
                const x = pos.x + Math.cos(angle) * radius;
                const y = pos.y + Math.sin(angle) * radius;
                return candidatePoint(candidate, x, y, index + 1, local.length);
            }).join('');
            const omitted = node.omittedCandidateCount > 0 ? `<text class="radar-omitted" x="${pos.x + radius - 4}" y="${pos.y + radius - 2}">+${node.omittedCandidateCount}</text>` : '';
            const selectedNode = local.some(candidate => candidate.candidateId === state.selectedId) ? ' selected-node' : '';
            return `<g class="radar-ast-node${selectedNode}" role="treeitem" aria-label="${esc(node.nodeKind + ' ' + node.label + ', Position ' + node.pathKey + ', ' + node.candidateCount + ' Anwendungen')}">
                <circle class="radar-rule-halo" cx="${pos.x}" cy="${pos.y}" r="${radius}"></circle>
                <circle class="radar-node-body" cx="${pos.x}" cy="${pos.y}" r="27"></circle>
                <text class="radar-node-label" x="${pos.x}" y="${pos.y + 1}">${esc(node.label)}</text>
                <text class="radar-node-path" x="${pos.x}" y="${pos.y + 82}">${esc(node.pathKey)}</text>
                ${points}${omitted}
            </g>`;
        }).join('');
        svg.innerHTML = `<g class="radar-ast-edges">${edges.join('')}</g><g class="radar-ast-nodes">${nodeMarkup}</g>`;
        bindCandidatePoints(svg);
        const t = snapshot.truncation;
        $('radarTreeSummary').textContent = `${nodes.length} Knoten · ${t.returnedCandidateCount} dargestellte Anwendungen · ${t.omittedCandidateCount} durch deklarierte Budgets ausgelassen.`;
    }

    function layoutTree(byKey) {
        const positions = new Map();
        let leaf = 0;
        function visit(key) {
            const node = byKey.get(key);
            if (!node) { return 0; }
            const childXs = (node.childPathKeys || []).map(visit);
            const x = childXs.length ? childXs.reduce((a, b) => a + b, 0) / childXs.length : 100 + (leaf++ * 180);
            const y = 85 + node.depth * 150;
            positions.set(key, {x, y});
            return x;
        }
        visit('root');
        return positions;
    }

    function candidatePoint(candidate, x, y, ordinal, count) {
        const selected = candidate.candidateId === state.selectedId ? ' selected' : '';
        const rejected = candidate.applicable ? '' : ' rejected';
        const cls = `radar-move-point origin-${candidate.origin.toLowerCase()}${selected}${rejected}`;
        const label = `${candidate.displayName || candidate.ruleId}, ${candidate.origin}, Position ${candidate.pathKey}, Anwendung ${ordinal} von ${count}, ${candidate.applicable ? 'anwendbar' : candidate.outcome}`;
        const shape = shapeMarkup(candidate.origin, x, y);
        return `<g class="${cls}" data-candidate-id="${esc(candidate.candidateId)}" role="button" tabindex="0" aria-label="${esc(label)}">
            <title>${esc(label)}</title>${shape}
        </g>`;
    }

    function shapeMarkup(origin, x, y) {
        if (origin === 'KNOWLEDGE_PACK') { return `<path d="M ${x} ${y-9} L ${x+9} ${y} L ${x} ${y+9} L ${x-9} ${y} Z"></path>`; }
        if (origin === 'RULE_FILE') { return `<rect x="${x-8}" y="${y-8}" width="16" height="16" rx="2"></rect>`; }
        if (origin === 'PLUGIN') { return `<path d="M ${x} ${y-10} L ${x+10} ${y+8} L ${x-10} ${y+8} Z"></path>`; }
        if (origin === 'LEARNED_MACRO') {
            const points = [];
            for (let i = 0; i < 10; i++) {
                const angle = -Math.PI / 2 + i * Math.PI / 5;
                const radius = i % 2 === 0 ? 10 : 4.5;
                points.push(`${x + Math.cos(angle)*radius},${y + Math.sin(angle)*radius}`);
            }
            return `<polygon points="${points.join(' ')}"></polygon>`;
        }
        return `<circle cx="${x}" cy="${y}" r="8"></circle>`;
    }

    function bindCandidatePoints(root) {
        root.querySelectorAll('[data-candidate-id]').forEach(element => {
            const activate = () => selectCandidate(element.dataset.candidateId, true);
            element.addEventListener('click', activate);
            element.addEventListener('keydown', event => {
                if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); activate(); }
            });
        });
    }

    function selectCandidate(candidateId, focusDetails = false) {
        state.selectedId = candidateId;
        $('radarAssumptionAck').checked = false;
        renderTree(); renderTable(); renderCandidateDetail();
        if (focusDetails) { $('radarCandidatePanel').scrollIntoView({behavior: 'smooth', block: 'nearest'}); }
    }

    function selectedCandidate() {
        return ((state.snapshot && state.snapshot.candidates) || []).find(candidate => candidate.candidateId === state.selectedId) || null;
    }

    function renderCandidateDetail() {
        const host = $('radarCandidateDetail');
        const candidate = selectedCandidate();
        const apply = $('radarApply');
        const ackLabel = $('radarAssumptionAckLabel');
        if (!candidate) {
            host.innerHTML = '<p class="hint">Einen Punkt im AST oder eine Tabellenzeile auswählen.</p>';
            apply.disabled = true; ackLabel.hidden = true; return;
        }
        const bindings = (candidate.bindings || []).length
            ? `<table class="radar-mini-table"><thead><tr><th>Variable</th><th>Bindung</th><th>Typ</th></tr></thead><tbody>${candidate.bindings.map(binding => `<tr><td><code>${esc(binding.name)}</code></td><td><code>${esc(binding.value)}</code></td><td>${esc(binding.kind)}</td></tr>`).join('')}</tbody></table>`
            : '<p class="hint">Keine expliziten Pattern-Bindungen.</p>';
        const assumptions = (candidate.assumptions || []).length
            ? `<div class="radar-assumptions"><strong>Annahmen:</strong><ul>${candidate.assumptions.map(value => `<li><code>${esc(value)}</code></li>`).join('')}</ul></div>`
            : '<p class="hint">Keine zusätzlichen Annahmen.</p>';
        const macro = candidate.macroEvidence ? macroMarkup(candidate.macroEvidence) : '';
        host.innerHTML = `
            <dl class="radar-dl">
                <dt>Candidate-ID</dt><dd><code>${esc(candidate.candidateId)}</code></dd>
                <dt>Position</dt><dd><code>${esc(candidate.pathKey)}</code></dd>
                <dt>Regel</dt><dd><code>${esc(candidate.ruleId)}</code> · ${esc(candidate.displayName)}</dd>
                <dt>Herkunft</dt><dd>${esc(candidate.origin)} · ${esc(candidate.sourceReference || '—')}</dd>
                <dt>Validierung</dt><dd><code>${esc(candidate.validationStatus)}</code> · Outcome <code>${esc(candidate.outcome)}</code></dd>
                <dt>Kostenhinweis</dt><dd>${candidate.estimatedCostDelta} · ${candidate.mayIncreaseComplexity ? 'kann Komplexität erhöhen' : 'keine deklarierte Erhöhung'}</dd>
            </dl>
            ${bindings}${assumptions}
            <div class="radar-rewrite-preview">
                <p><strong>Teilbaum:</strong> <code>${esc(candidate.subtreeBefore)}</code> → <code>${esc(candidate.subtreeAfter)}</code></p>
                <p><strong>Vollständiger Zustand:</strong> <code>${esc(candidate.expressionBefore)}</code> → <code>${esc(candidate.expressionAfter)}</code></p>
            </div>${macro}`;
        ackLabel.hidden = !(candidate.assumptions || []).length;
        syncApplyButton();
    }

    function macroMarkup(evidence) {
        const steps = evidence.atomicSteps || [];
        return `<details class="radar-macro-evidence"><summary>Atomare Makro-Evidence aufklappen (${steps.length} Schritte, ${evidence.supportingPathIds.length} Pfade)</summary>
            <p>ReusableRule <code>${esc(evidence.reusableRuleId)}</code>, Confidence ${evidence.confidenceScore}, Occurrences ${evidence.occurrenceCount}, Compression ${evidence.compressionRatio}</p>
            ${steps.length ? `<ol>${steps.map(step => `<li><code>${esc(step.beforeExpression)}</code> <span aria-hidden="true">→</span> <code>${esc(step.afterExpression)}</code> über <code>${esc(step.ruleId)}</code></li>`).join('')}</ol>` : '<p class="hint">Supporting-Path-IDs sind vorhanden; die referenzierten Pfade liegen im aktuellen GraphStore nicht vor.</p>'}
        </details>`;
    }

    function syncApplyButton() {
        const candidate = selectedCandidate();
        const needsAck = candidate && (candidate.assumptions || []).length > 0;
        $('radarApply').disabled = !candidate || !candidate.applicable || (needsAck && !$('radarAssumptionAck').checked);
    }

    function applySelected() {
        const candidate = selectedCandidate();
        if (!candidate) { return; }
        $('radarApplyStatus').textContent = 'Der exakt angezeigte Kandidat wird erneut serverseitig auf Staleness geprüft …';
        post('/api/rule-radar/apply', {
            expression: state.snapshot.expression,
            candidateId: candidate.candidateId,
            context: context({selectedCandidateId: candidate.candidateId})
        }).then(result => {
            state.undo.push({
                expression: state.snapshot.expression,
                candidateId: candidate.candidateId,
                pathKey: candidate.pathKey,
                ruleId: candidate.ruleId
            });
            state.snapshot = result.inspection;
            state.selectedId = '';
            $('radarExpression').value = result.expressionAfter;
            $('radarApplyStatus').textContent = `Angewendet: ${result.ruleId} an ${result.pathKey}.`;
            render();
        }).catch(error => $('radarApplyStatus').textContent = 'Fehler: ' + error.message);
    }

    function undo() {
        const entry = state.undo.pop();
        if (entry == null) { return; }
        state.selectedId = '';
        $('radarApplyStatus').textContent = `Rückgängig: ${entry.ruleId} an ${entry.pathKey}.`;
        inspect({expression: entry.expression});
    }

    function renderTable() {
        const body = $('radarCandidateRows');
        const candidates = visibleCandidates();
        if (!candidates.length) {
            body.innerHTML = '<tr><td colspan="6" class="hint">Keine Kandidaten unter den aktiven Darstellungsfiltern.</td></tr>';
            return;
        }
        body.innerHTML = candidates.map(candidate => `<tr class="${candidate.candidateId === state.selectedId ? 'selected' : ''}">
            <td><code>${esc(candidate.pathKey)}</code></td>
            <td><span class="radar-origin-chip origin-${candidate.origin.toLowerCase()}">${esc(candidate.origin)}</span></td>
            <td><code>${esc(candidate.ruleId)}</code><br><span>${esc(candidate.displayName)}</span></td>
            <td><code>${esc(candidate.expressionAfter)}</code></td>
            <td><code>${esc(candidate.outcome)}</code>${candidate.applicable ? '' : ' · nicht ausführbar'}</td>
            <td><button type="button" data-row-candidate="${esc(candidate.candidateId)}">Auswählen</button></td>
        </tr>`).join('');
        body.querySelectorAll('[data-row-candidate]').forEach(button => button.addEventListener('click', () => selectCandidate(button.dataset.rowCandidate, true)));
    }

    function runSearch() {
        const expression = $('radarExpression').value.trim();
        $('radarSearchSummary').textContent = 'Suche läuft mit denselben positionsgebundenen Kandidaten …';
        post('/api/rule-radar/search', {
            expression,
            targetExpression: $('radarGoal').value.trim(),
            maxDepth: numberValue('radarSearchDepth', 4),
            maxStates: numberValue('radarSearchStates', 120),
            maxMovesPerState: numberValue('radarSearchMoves', 60),
            context: context()
        }).then(result => {
            state.search = result;
            $('radarSearchSummary').textContent = `${result.exploredStateCount} Zustände expandiert, ${result.generatedCandidateCount} Kandidaten betrachtet, Ziel ${result.targetReached ? 'erreicht' : 'nicht erreicht'}.`;
            renderSearchGraph();
        }).catch(error => $('radarSearchSummary').textContent = 'Fehler: ' + error.message);
    }

    function renderSearchGraph() {
        const host = $('radarSearchGraph');
        const result = state.search;
        if (!result) { host.innerHTML = ''; return; }
        const stateById = new Map((result.states || []).map(item => [item.stateId, item]));
        const edges = result.edges || [];
        const eventsByCandidate = new Map();
        (result.events || []).forEach(event => eventsByCandidate.set(event.candidateId, event));
        host.innerHTML = `<div class="radar-state-list">${(result.states || []).map(item => `<div class="radar-search-state ${item.target ? 'target' : ''}"><strong>${esc(item.stateId)}</strong><span>Tiefe ${item.depth}</span><code>${esc(item.expression)}</code></div>`).join('')}</div>
            <div class="radar-edge-list"><h4>Angewandte Suchkanten</h4>${edges.length ? edges.map(edge => `<button type="button" class="radar-search-edge" data-search-candidate="${esc(edge.candidateId)}" data-from-expression="${esc(edge.fromExpression)}">
                <code>${esc(edge.fromStateId)}</code> → <code>${esc(edge.toStateId)}</code><br>
                ${esc(edge.ruleId)} @ ${esc(edge.pathKey)} · ${esc(edge.origin)}
            </button>`).join('') : '<p class="hint">Keine neue Suchkante innerhalb der Budgets.</p>'}</div>
            <details><summary>Alle Suchentscheidungen (${(result.events || []).length})</summary><ol class="radar-event-list">${(result.events || []).map(event => `<li><code>${esc(event.outcome)}</code> · ${esc(event.ruleId)} @ ${esc(event.pathKey)} — ${esc(event.detail)}</li>`).join('')}</ol></details>`;
        host.querySelectorAll('[data-search-candidate]').forEach(button => {
            button.addEventListener('click', () => {
                const outcomes = result.finalOutcomeByCandidateId || {};
                state.selectedId = button.dataset.searchCandidate;
                $('radarExpression').value = button.dataset.fromExpression;
                inspect({
                    expression: button.dataset.fromExpression,
                    selectedCandidateId: button.dataset.searchCandidate,
                    outcomeByCandidateId: outcomes
                });
            });
        });
    }

    function changeZoom(delta) {
        state.zoom = Math.max(0.55, Math.min(2.4, state.zoom + delta));
        renderTree();
    }

    renderOriginFilters();
    $('radarInspect').addEventListener('click', () => inspect());
    $('radarExpression').addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); inspect(); } });
    $('radarUndo').addEventListener('click', undo);
    $('radarApply').addEventListener('click', applySelected);
    $('radarAssumptionAck').addEventListener('change', syncApplyButton);
    $('radarRunSearch').addEventListener('click', runSearch);
    $('radarZoomOut').addEventListener('click', () => changeZoom(-0.15));
    $('radarZoomIn').addEventListener('click', () => changeZoom(0.15));
    inspect();
})();
