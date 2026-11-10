/* Task entry, accessible tab navigation and conservative search-result summaries. */
(() => {
    'use strict';
    const tabs = Array.from(document.querySelectorAll('.tab'));
    const panels = Array.from(document.querySelectorAll('.tab-panel'));
    const byId = (id) => document.getElementById(id);
    const revealTools = () => {
        document.body.classList.remove('pre-search');
        document.body.dataset.preSearch = 'false';
    };

    let restoringTab = false;
    let pendingRestore;
    function applyTab(name) {
        const tab = tabs.find((candidate) => candidate.dataset.tab === name);
        if (!tab || tab.disabled) return false;
        const panel = byId('tab-' + name);
        if (!panel) return false;
        if (name !== 'workbench') revealTools();
        tabs.forEach((candidate) => {
            const active = candidate === tab;
            candidate.classList.toggle('active', active);
            candidate.setAttribute('aria-selected', String(active));
            candidate.tabIndex = active ? 0 : -1;
        });
        panels.forEach((candidate) => candidate.classList.toggle('active', candidate === panel));
        byId('demoHero').hidden = name !== 'workbench';
        const query = new URLSearchParams(location.hash.slice(1));
        // The retained-run controller owns its richer run/artifact/candidate URL.
        if (!restoringTab && !query.has('run') && query.get('tab') !== name) {
            history.pushState(null, '', '#tab=' + encodeURIComponent(name));
        }
        return true;
    }

    function activateTab(name) {
        const tab = tabs.find((candidate) => candidate.dataset.tab === name);
        if (!tab || tab.disabled) return false;
        // Other controllers attach their data loaders to this same click.
        tab.click();
        return true;
    }

    document.querySelector('.tabs').setAttribute('role', 'tablist');
    tabs.forEach((tab) => {
        tab.id = tab.id || 'tool-' + tab.dataset.tab;
        tab.setAttribute('role', 'tab');
        tab.setAttribute('aria-controls', 'tab-' + tab.dataset.tab);
        const active = tab.classList.contains('active');
        tab.setAttribute('aria-selected', String(active));
        tab.tabIndex = active ? 0 : -1;
        const panel = byId('tab-' + tab.dataset.tab);
        if (panel) {
            panel.setAttribute('role', 'tabpanel');
            panel.setAttribute('aria-labelledby', tab.id);
        }
        tab.addEventListener('click', () => applyTab(tab.dataset.tab));
        tab.addEventListener('keydown', (event) => {
            if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
            const visible = tabs.filter((candidate) => !candidate.disabled && candidate.getClientRects().length);
            const position = visible.indexOf(tab);
            const next = event.key === 'Home' ? 0 : event.key === 'End' ? visible.length - 1
                : (position + (event.key === 'ArrowRight' ? 1 : -1) + visible.length) % visible.length;
            if (!visible[next]) return;
            event.preventDefault();
            visible[next].focus();
            visible[next].click();
        });
    });
    document.addEventListener('click', (event) => {
        const link = event.target.closest('[data-open-tab]');
        if (!link || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || event.button > 0) return;
        event.preventDefault();
        if (activateTab(link.dataset.openTab)) {
            const panel = byId('tab-' + link.dataset.openTab);
            panel.tabIndex = -1;
            panel.focus({preventScroll: true});
            panel.scrollIntoView({block: 'start', behavior: 'smooth'});
            if (link.id === 'workflowExplore') byId('searchForm').elements.expression.focus({preventScroll: true});
        }
    });
    byId('showAllTools').addEventListener('click', revealTools);
    const restoreTab = () => {
        clearTimeout(pendingRestore);
        // Run after all DOM-ready/hash listeners, including lazy-loader binding
        // and the retained-run controller re-enabling its disabled tool tabs.
        pendingRestore = setTimeout(() => {
            const query = new URLSearchParams(location.hash.slice(1));
            if (query.has('run')) return;
            restoringTab = true;
            try { activateTab(query.get('tab') || 'workbench'); }
            finally { restoringTab = false; }
        }, 0);
    };
    window.addEventListener('hashchange', restoreTab);
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', restoreTab, {once: true});
    else restoreTab();

    function append(parent, tag, text, className) {
        const element = document.createElement(tag);
        if (text !== undefined) element.textContent = String(text);
        if (className) element.className = className;
        parent.appendChild(element);
        return element;
    }

    const evidenceLabels = {
        REJECTED: 'Verworfen; kein verwendbarer Nachweis.',
        OBSERVED: 'Beobachtetes Muster; noch nicht unabhängig geprüft.',
        VALIDATED_BY_EXAMPLES: 'Durch Beispiele bestätigt; das beweist keine allgemeine Gültigkeit.',
        SYMBOLICALLY_VERIFIED: 'Symbolisch geprüft im angegebenen Modell und unter seinen Voraussetzungen.',
        FORMALLY_PROVABLE: 'Beweis vorbereitet; noch kein abgeschlossener formaler Nachweis.',
        FORMALLY_PROVED: 'Als formal bewiesen gemeldet. Prüfer, gebundene Aussage und Voraussetzungen im Beleg prüfen.'
    };

    function renderSearchResult(data, options = {}) {
        const host = byId('searchResultSummary');
        host.replaceChildren();
        host.hidden = false;
        append(host, 'h3', options.error ? 'Suche nicht abgeschlossen' : 'Ergebnis').id = 'searchResultTitle';
        if (options.error) {
            append(host, 'p', options.error, 'status error');
            append(host, 'p', 'Prüfe die Eingabe und starte die Suche erneut. Ein technischer Fehler ist kein mathematisches Gegenbeispiel.');
            return;
        }
        const runtime = data && data.schema === 'regelsuche.safe-runtime-artifact/v1' ? data.evidence : null;
        const outcomes = runtime && Array.isArray(runtime.outcomes) ? runtime.outcomes : [];
        const candidates = outcomes.filter((outcome) => outcome.candidate && typeof outcome.candidate.expression === 'string');
        const expression = runtime ? runtime.request && runtime.request.source : data && data.expression;
        const candidate = candidates[0] && candidates[0].candidate;
        const best = runtime ? candidate && candidate.expression : data && data.bestExpression;
        const status = runtime ? runtime.status : data && (data.stopReason || data.status);
        if (best) {
            append(host, 'p', runtime ? 'Ein geprüfter Kandidat aus diesem Lauf:' : 'Bester gefundener Ausdruck:');
            append(host, 'code', (expression ? expression + ' → ' : '') + best, 'result-expression');
        } else {
            append(host, 'p', 'Kein besserer Ausdruck in diesem Suchlauf gefunden.', 'result-empty');
        }
        if (status) append(host, 'p', 'Laufstatus: ' + status);
        if (data && Number.isInteger(data.successes)) append(host, 'p', 'Gefundene Verbesserungen: ' + data.successes);
        append(host, 'h4', 'Voraussetzungen');
        // Runtime-level assumptions describe the request; a candidate also
        // carries requirements introduced by its own conditional steps.
        const assumptions = runtime ? candidate ? candidate.retainedAssumptions : runtime.retainedAssumptions : data && data.assumptions;
        if (Array.isArray(assumptions)) {
            if (assumptions.length) {
                const list = append(host, 'ul');
                assumptions.forEach((assumption) => append(list, 'li', assumption));
            } else append(host, 'p', 'In dieser Antwort sind keine zusätzlichen Voraussetzungen angegeben.');
        } else append(host, 'p', 'Diese Suchantwort enthält keine Angaben zu Voraussetzungen. Prüfe sie vor einer Übernahme am konkreten Rechenweg.');
        append(host, 'h4', 'Was wurde geprüft?');
        append(host, 'p', runtime
            ? (options.replayed ? 'Das importierte Artefakt wurde vom Server frisch nachgerechnet. ' : 'Die Runtime hat ihre Kandidaten mit den gebundenen Ausführungsregeln geprüft. ')
                + 'Ein Replay bestätigt die Ausführung; die mathematische Beweiskraft hängt von Regeln, Modell und Voraussetzungen ab.'
            : evidenceLabels[data && data.proofStatus] || 'Suchergebnis ohne mitgelieferten unabhängigen Nachweis.');
        append(host, 'p', 'Ein exakter Identitätscheck gilt für sein mathematisches Fragment. Ein SMT-Solverbeleg und ein vom Lean-Kernel geprüfter Beweis sind unterschiedliche Nachweise.', 'hint');
        const diagnostics = data && Array.isArray(data.diagnostics) ? data.diagnostics : [];
        if (diagnostics.length || outcomes.some((outcome) => /FAILURE|BUDGET/.test(outcome.status))) {
            const details = append(host, 'details');
            append(details, 'summary', 'Diagnosen und erreichte Grenzen');
            const list = append(details, 'ul');
            diagnostics.slice(0, 10).forEach((diagnostic) => append(list, 'li',
                [diagnostic.ruleId, diagnostic.stage, diagnostic.message || diagnostic.detailCode].filter(Boolean).join(' · ')));
            outcomes.filter((outcome) => /FAILURE|BUDGET/.test(outcome.status)).slice(0, 10)
                .forEach((outcome) => append(list, 'li', [outcome.id, outcome.status, outcome.detailCode].filter(Boolean).join(' · ')));
        }
        const actions = append(host, 'div', undefined, 'actions');
        const path = append(actions, 'a', runtime ? 'Prüfdetails ansehen' : best ? 'Rechenweg ansehen' : 'Andere Beispiele erkunden', 'export-button');
        path.href = runtime ? '#searchResponseDetails' : '#tab=' + (best ? 'paths' : 'workbench');
        if (runtime) path.addEventListener('click', () => { byId('searchResponseDetails').open = true; });
        else path.dataset.openTab = best ? 'paths' : 'workbench';
        const proof = append(actions, 'a', 'Nachweis prüfen', 'export-button');
        proof.href = '#tab=proofJobs'; proof.dataset.openTab = 'proofJobs';
    }

    window.RegelsucheWorkbench = Object.freeze({activateTab, renderSearchResult});
})();
