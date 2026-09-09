(() => {
    'use strict';
    const element = id => document.getElementById(id);
    let domains = [];
    let evidenceBytes = '';
    const meanings = {
        CONFIRMED: 'Der unabhängige Evaluator der Domäne hat ein Zertifikat ausgestellt. Dessen angegebener Gültigkeitsbereich ist maßgeblich.',
        REFUTED: 'Ein Kandidat wurde widerlegt; der Lauf veröffentlicht kein bestätigtes Ergebnis.',
        BUDGET_EXHAUSTED: 'Das Suchbudget ist erschöpft. Daraus folgt keine mathematische Entscheidung.',
        INCONCLUSIVE: 'Die vorhandenen Prüfungen erlauben noch keine Entscheidung.',
        UNSUPPORTED: 'Die Domäne unterstützt diese Prüfung nicht.',
        INVALID_SEED: 'Die Startdaten verletzen die Invarianten der Domäne.'
    };
    const selection = () => domains[Number(element('domainChoice').value)];
    element('domainChoice').addEventListener('change', () => {
        const domain = selection();
        element('domainOrigin').textContent = domain ? `${domain.providerId} · Version ${domain.providerVersion}` : '';
    });
    element('domainForm').addEventListener('submit', async event => {
        event.preventDefault();
        const domain = selection();
        if (!domain) return;
        element('startDomain').disabled = true;
        element('domainResult').hidden = true;
        element('domainStatus').textContent = 'Der Suchlauf wird ausgeführt …';
        try {
            const response = await fetch('/api/discovery-domains/run', {
                method: 'POST', headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({providerId: domain.providerId, domainId: domain.domainId,
                    revision: domain.revision, campaignId: element('campaign').value,
                    seed: element('seed').value, budget: element('budget').value})
            });
            const text = await response.text();
            if (!response.ok) throw new Error(text);
            const result = JSON.parse(text);
            evidenceBytes = text;
            element('outcome').textContent = result.outcome;
            element('interpretation').textContent = meanings[result.outcome] || 'Unbekannter Ergebnisstatus.';
            element('certificate').textContent = result.certificate ? 'Ein Zertifikat ist in der Evidence enthalten.' : 'Kein Zertifikat ausgestellt.';
            element('counterexamples').replaceChildren();
            for (const attempt of result.candidateAttempts || []) {
                const witness = attempt.counterexampleWitness;
                if (!witness) continue;
                const item = document.createElement('li');
                item.textContent = witness;
                element('counterexamples').append(item);
            }
            element('evidence').textContent = JSON.stringify(result, null, 2);
            element('domainResult').hidden = false;
            element('domainStatus').textContent = 'Lauf abgeschlossen. Die vollständige Evidence kann gespeichert werden.';
        } catch (error) {
            evidenceBytes = '';
            element('domainStatus').textContent = `Lauf fehlgeschlagen: ${error.message}`;
        } finally { element('startDomain').disabled = domains.length === 0; }
    });
    element('downloadEvidence').addEventListener('click', () => {
        if (!evidenceBytes) return;
        const url = URL.createObjectURL(new Blob([evidenceBytes], {type: 'application/json'}));
        const link = document.createElement('a');
        link.href = url; link.download = 'discovery-evidence.json'; link.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
    fetch('/api/discovery-domains').then(async response => {
        if (!response.ok) throw new Error(await response.text());
        const data = await response.json();
        domains = data.domains;
        domains.forEach((domain, index) => {
            const option = document.createElement('option'); option.value = String(index);
            option.textContent = `${domain.domainId} · ${domain.revision}`;
            element('domainChoice').append(option);
        });
        element('domainChoice').dispatchEvent(new Event('change'));
        element('startDomain').disabled = domains.length === 0;
        element('domainStatus').textContent = domains.length
            ? 'Domäne auswählen und passende Startdaten eingeben.'
            : 'Für diese Workbench wurden noch keine eigenen Domänen aktiviert.';
    }).catch(error => { element('domainStatus').textContent = `Domänen konnten nicht geladen werden: ${error.message}`; });
})();
