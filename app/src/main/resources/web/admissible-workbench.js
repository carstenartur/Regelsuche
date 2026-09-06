/* Local evidence inspection, isolated from the expression workbench's mutable state. */
(function () {
    'use strict';
    const byId = id => document.getElementById(id);
    let generation = 0, worker = null, timer = null, result = null, current = null, path = [];
    const format = value => value === null ? '—' : BigInt(value).toLocaleString('de-DE');
    function element(tag, text) { const node = document.createElement(tag); node.textContent = text; return node; }
    function clearWork() {
        generation++;
        if (worker) worker.terminate();
        worker = null; clearTimeout(timer); timer = null;
        result = null; current = null; path = [];
        byId('result').hidden = true;
        byId('comparisons').replaceChildren(); byId('branches').replaceChildren(); byId('offsets').replaceChildren();
        byId('run').replaceChildren(); byId('error').hidden = true; byId('tamperStatus').textContent = '';
    }
    function fail(message) {
        clearWork();
        byId('status').textContent = 'Kein bestätigtes Ergebnis.';
        byId('error').textContent = message; byId('error').hidden = false;
    }
    function launch(message, ticket, exploratory = false) {
        if (ticket !== generation) return;
        try {
            worker = new Worker(new URL('admissible-worker.js', document.baseURI));
            byId('status').textContent = 'Mathematische Zertifikate werden im Browser geprüft …';
            timer = setTimeout(() => { if (ticket === generation) fail('Zeitbudget erschöpft; Prüfung abgebrochen.'); }, 10000);
            worker.onmessage = event => {
                if (ticket !== generation) return;
                worker.terminate(); worker = null; clearTimeout(timer); timer = null;
                if (!event.data.ok) { fail(event.data.message); return; }
                result = event.data.result;
                result.exploratory = exploratory || result.exploratory === true;
                showResult();
            };
            worker.onerror = () => { if (ticket === generation) fail('Prüfmodul nicht verfügbar. Öffne die Seite über den lokalen Workbench-Server.'); };
            worker.postMessage(message);
        } catch (_) { fail('Prüfmodul konnte nicht gestartet werden.'); }
    }
    function showResult() {
        const complete = result.runs.filter(r => r.status === 'OPTIMAL').length;
        byId('status').textContent = `${complete} Optimalitätszertifikate im Browser nachgerechnet.`;
        byId('summary').textContent = result.example ? 'Lehrbeispiel: Im Fenster 0 bis 8 passen höchstens vier zulässige Plätze.'
            : result.exploratory ? `Neue explorative Aufgabe · Fixierte Vergleichsstrategie: ${result.selectedPolicy}. Kein Trainings- oder zurückgehaltener Testfall.`
            : `Ausgewählte Strategie laut Export: ${result.selectedPolicy}. ${result.runs.length / 2} zurückgehaltene Aufgaben im Vergleich.`;
        byId('scope').textContent = result.example
            ? 'Konstruiertes kleines Beweisbeispiel, kein gemessener SDK-Lauf. Grüne Positionen gehören zum zulässigen Muster.'
            : 'Die mathematischen Beweise sind hier neu geprüft. Auswahl, Arbeitsaufwand und Quellmanifest sind importierte Angaben, keine authentifizierte Herkunft.';
        for (const [i, run] of result.runs.entries()) {
            const row = document.createElement('tr');
            [run.case, `${run.policy}${run.role === 'selected' ? ' (ausgewählt)' : ' (bisher)'}`, run.witness.length,
                format(run.cost), run.status === 'OPTIMAL' ? 'Maximum bewiesen' : 'Nur unterer Zeuge · Budgetabbruch']
                .forEach((text, col) => row.append(element(col === 0 ? 'th' : 'td', String(text))));
            row.firstChild.scope = 'row'; byId('comparisons').append(row);
            const option = element('option', `${run.case} · ${run.policy}`); option.value = String(i); byId('run').append(option);
        }
        byId('result').hidden = false;
        chooseRun();
    }
    function chooseRun() {
        current = result.runs[Number(byId('run').value)]; path = [current.root];
        byId('witness').textContent = 'Zulässiges Muster: ' + current.witness.join(', ');
        byId('source').textContent = result.sourceManifestSha256 ? 'Quellmanifest-Kennung: ' + result.sourceManifestSha256 : 'Lehrbeispiel ohne Laufzeit- oder Lernbehauptung.';
        renderStep();
    }
    function renderStep() {
        const step = AdmissibleProof.step(current, path[path.length - 1]);
        byId('back').disabled = path.length <= 1;
        byId('root').disabled = path.length <= 1;
        byId('position').textContent = `Tiefe ${path.length - 1} · Noch ${step.size} mögliche Positionen. Grün: Positionen des gefundenen zulässigen Musters.`;
        byId('proofStatus').textContent = current.status === 'OPTIMAL'
            ? `Maximum ${current.witness.length} bestätigt · ${current.checkedNodes} Beweisknoten vollständig geprüft.`
            : `Nur ${current.witness.length} zulässige Plätze nachgewiesen. Der Lauf endete am Budget; kein Maximum bestätigt.`;
        byId('offsets').replaceChildren(...step.values.map(value => {
            const node = element('span', String(value));
            if (current.witness.includes(value)) { node.className = 'in-witness'; node.title = 'Im zulässigen Muster'; }
            return node;
        }));
        byId('branches').replaceChildren();
        if (current.status !== 'OPTIMAL') {
            byId('explanation').textContent = 'Für diese Aufgabe liegt kein vollständiger Obergrenzenbeweis vor. Es werden keine fehlenden Suchschritte erfunden.';
            return;
        }
        if (step.terminal === 'bound') {
            byId('explanation').textContent = `Dieser Zweig enthält nur noch ${step.size} Plätze. Er kann das bereits zulässige ${current.witness.length}er-Muster nicht verbessern.`;
            return;
        }
        byId('explanation').textContent = `Alle ${step.prime} Restklassen modulo ${step.prime} sind besetzt. Jede zulässige Teilmenge muss eine Klasse vollständig weglassen. Klasse 0 bleibt wegen des enthaltenen Startpunkts erhalten.`;
        for (const child of step.children) {
            const button = element('button', `Restklasse ${child.residue} weglassen → ${child.size} Plätze (${child.removed} entfernt)`);
            button.type = 'button';
            button.addEventListener('click', () => { path.push(child.mask); renderStep(); byId('back').focus(); });
            byId('branches').append(button);
        }
    }
    byId('example').addEventListener('click', () => { clearWork(); byId('bundle').value = ''; launch({kind: 'example'}, generation); });
    byId('clear').addEventListener('click', () => {
        clearWork(); byId('bundle').value = '';
        byId('status').textContent = 'Zurückgesetzt. Keine frühere Prüfung oder Auswahl bleibt aktiv.';
    });
    byId('bundle').addEventListener('change', async () => {
        clearWork();
        const ticket = generation, file = byId('bundle').files[0];
        if (!file) { byId('status').textContent = 'Keine Datei ausgewählt.'; return; }
        if (file.size > AdmissibleProof.MAX_BYTES) { fail('Datei überschreitet 8 MB.'); return; }
        byId('status').textContent = 'Lokale Datei wird gelesen …';
        try { const text = await file.text(); launch({kind: 'bundle', text}, ticket); }
        catch (_) { if (ticket === generation) fail('Lokale Datei konnte nicht gelesen werden.'); }
    });
    byId('run').addEventListener('change', chooseRun);
    byId('back').addEventListener('click', () => { if (path.length > 1) path.pop(); renderStep(); });
    byId('root').addEventListener('click', () => { path = [current.root]; renderStep(); });
    byId('tamper').addEventListener('click', () => {
        try {
            AdmissibleProof.parseProof(AdmissibleProof.example.replace('1ff:2\n', ''));
            byId('tamperStatus').textContent = 'Fehler: Die Beschädigung wurde nicht erkannt.';
        } catch (_) {
            byId('tamperStatus').textContent = 'Wie erwartet abgewiesen: Der erforderliche Wurzelzweig fehlt. Das geladene Experiment wurde nicht verändert.';
        }
    });
    // Optional same-document runner adapter. It supplies bytes, never a trusted
    // result/status. The ordinary bounded independent worker verifies them again.
    window.addEventListener('admissible:local-result', event => {
        clearWork(); byId('bundle').value = '';
        if (typeof event.detail !== 'string' || new TextEncoder().encode(event.detail).length > AdmissibleProof.MAX_BYTES) {
            fail('Ungültiges oder zu großes Ergebnis des lokalen Runners.'); return;
        }
        launch({kind: 'bundle', text: event.detail}, generation, true);
    });
    window.addEventListener('pagehide', clearWork);
})();
