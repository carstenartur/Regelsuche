'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const number = value => Number(value).toLocaleString('de-DE');
  let currentArtifact = null;
  let study = null;
  const text = (tag, value) => { const node = document.createElement(tag); node.textContent = value; return node; };
  async function request(path, body) {
    const response = await fetch(path, body === undefined ? {} : {
      method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
    });
    if (!response.ok) throw new Error(await response.text());
    return response.json();
  }
  function card(title, artifact) {
    const result = artifact.evidence.result, audit = artifact.evidence.audit;
    const node = document.createElement('article');
    node.className = 'result ' + (audit.status === 'VERIFIED' ? 'good' : 'pending');
    node.append(text('h3', title), text('strong', result.status === 'SOLVED' ? 'Lösungsmenge berechnet' : result.status),
      text('p', `Darstellung: ${result.selected}. Konstruktion: ${number(result.totalWork)} / ${number(result.budget)}. Prüfung: ${number(audit.work)} (${audit.status}).`));
    if (result.solution) node.append(text('p', `${result.solution.classification}: ${result.variables.length} Variablen, ${result.solution.basis.length} freie Parameter.`));
    return node;
  }
  function retain(artifact) {
    currentArtifact = artifact;
    $('evidence').textContent = JSON.stringify(artifact.evidence, null, 2);
    $('export').disabled = false;
  }
  function clearArtifact() { currentArtifact = null; $('export').disabled = true; $('evidence').textContent = 'Noch kein bestätigtes Ergebnis.'; }
  function download(name, value) {
    const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2) + '\n'], {type:'application/json'}));
    const link = document.createElement('a'); link.href = url; link.download = name; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  $('solveForm').addEventListener('submit', async event => {
    event.preventDefault(); clearArtifact(); $('solve').disabled = true;
    $('status').textContent = 'Beide Wege werden mit demselben Budget berechnet …';
    $('comparison').replaceChildren();
    try {
      const equations = $('equations').value.split(/\n|;/).map(s => s.trim()).filter(Boolean);
      const maxWorkUnits = Number($('budget').value);
      if (!Number.isInteger(maxWorkUnits) || maxWorkUnits < 0 || maxWorkUnits > 1000000) throw new Error('Ungültiges ganzzahliges Budget.');
      const base = {schema:'regelsuche.linear-solve-request/v1', equations, maxWorkUnits};
      const [direct, selected] = await Promise.all([
        request('/api/representations/solve', {...base, route:'DIRECT'}),
        request('/api/representations/solve', {...base, route:$('route').value})
      ]);
      $('comparison').append(card('Direkte Elimination', direct), card('Gewählte Darstellung', selected));
      retain(selected);
      $('status').textContent = selected.evidence.audit.status === 'VERIFIED'
        ? 'Exakte Lösungsmenge berechnet und durch ein zweites Verfahren bestätigt.'
        : 'Kein bestätigter Abschluss. Status und verbrauchte Arbeit bleiben im Nachweis erhalten.';
    } catch (error) { $('status').textContent = 'Berechnung fehlgeschlagen: ' + error.message; }
    finally { $('solve').disabled = false; }
  });
  $('export').addEventListener('click', () => { if (currentArtifact) download('linear-solution.json', currentArtifact); });
  $('import').addEventListener('change', async () => {
    const file = $('import').files[0]; if (!file) return;
    clearArtifact(); $('comparison').replaceChildren(); $('status').textContent = 'Vollständiges Replay läuft …';
    try {
      if (file.size > 4 * 1024 * 1024) throw new Error('Datei ist größer als 4 MiB.');
      const artifact = await request('/api/representations/solve/replay', JSON.parse(await file.text()));
      retain(artifact); $('comparison').append(card('Erneut berechnetes Artefakt', artifact));
      $('status').textContent = 'Gesamter Replay bestätigt: Quelle, Auswahl, Lösung und Arbeit stimmen überein.';
    } catch (error) { $('status').textContent = 'Prüfung fehlgeschlagen: ' + error.message; }
    finally { $('import').value = ''; }
  });
  function renderCase() {
    $('caseResults').replaceChildren();
    if (!study) return;
    const rows = study.rows.filter(row => row.task.id === $('case').value);
    for (const row of rows) $('caseResults').append(card(row.profile, row.artifact));
  }
  $('case').addEventListener('change', renderCase);
  $('train').addEventListener('click', async () => {
    $('train').disabled = true; $('learningStatus').textContent = 'Training, Freeze und Transfer werden ausgeführt …';
    try {
      study = await request('/api/representations/study');
      $('profileRows').replaceChildren();
      for (const profile of study.summary.profiles) {
        const row = document.createElement('tr');
        for (const value of [profile.profile, number(profile.applicationWork), number(profile.includingLearningWork), `${profile.verifiedSolutions} / ${study.summary.applicationCases}`]) row.append(text('td', value));
        $('profileRows').append(row);
      }
      $('learningStatus').textContent = `Eingefrorene Auswahl: unabhängige Blöcke ab ${study.policy.minimumVariables} Variablen. Lernkosten: ${number(study.summary.learningWork)}.`;
      const learned = study.summary.profiles.find(p => p.profile === 'LEARNED');
      const fixed = study.summary.profiles.find(p => p.profile === 'FIXED_AUTO');
      $('learningConclusion').textContent = learned.includingLearningWork < fixed.includingLearningWork
        ? 'In diesem Entwicklungssatz benötigt das Lernen einschließlich Trainingskosten weniger Arbeit als die feste Auswahl.'
        : 'In diesem Entwicklungssatz bringt das Lernen einschließlich Trainingskosten keinen zusätzlichen Kostenvorteil gegenüber der festen Auswahl.';
      $('case').replaceChildren();
      for (const row of study.rows.filter(row => row.profile === 'DIRECT')) {
        const option = text('option', `${row.task.family} · ${row.task.id}`); option.value = row.task.id; $('case').append(option);
      }
      $('case').value = 'recurrence-0'; renderCase();
      $('trainingEvidence').textContent = JSON.stringify({policyHash:study.policyHash, policy:study.policy, training:study.training}, null, 2);
      $('studyResults').hidden = false;
    } catch (error) { $('learningStatus').textContent = 'Studie fehlgeschlagen: ' + error.message; }
    finally { $('train').disabled = false; }
  });
  $('exportStudy').addEventListener('click', () => { if (study) download('representation-transfer-study.json', study); });
})();
