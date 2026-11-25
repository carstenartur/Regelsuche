const byId = id => document.getElementById(id);
const form = byId('cnfForm'), status = byId('cnfStatus'), download = byId('cnfDownload');
const fields = ['cnfVariableCount', 'cnfClauses', 'cnfFixed', 'cnfWork'].map(byId);
const run = byId('cnfRun'), cancel = byId('cnfCancel');
const draftKey = 'regelsuche.cnf-draft.v1';
let active = null, fileUrl = null;

function clearResult() {
    if (fileUrl) URL.revokeObjectURL(fileUrl);
    fileUrl = null;
    download.hidden = true;
    download.removeAttribute('href');
    status.textContent = '';
    delete status.dataset.status;
}

function literals(text) {
    if (!text.trim()) return [];
    return text.trim().split(/\s+/).map(token => {
        if (!/^[+-]?\d+$/.test(token) || !Number.isSafeInteger(Number(token)))
            throw new Error('Literale müssen vorzeichenbehaftete ganze Zahlen sein.');
        return Number(token);
    });
}

try {
    const saved = JSON.parse(sessionStorage.getItem(draftKey));
    if (Array.isArray(saved) && saved.length === fields.length && saved.every(v => typeof v === 'string'))
        fields.forEach((field, i) => { field.value = saved[i]; });
} catch { /* Storage may be disabled. The form still works. */ }
form.addEventListener('input', () => {
    clearResult();
    try { sessionStorage.setItem(draftKey, JSON.stringify(fields.map(field => field.value))); } catch { /* optional draft */ }
});
run.disabled = false;

form.addEventListener('submit', async event => {
    event.preventDefault();
    if (active) return;
    clearResult();
    try {
        const clauses = fields[1].value.split(/\r?\n/).map(line => line.trim()).filter(Boolean)
            .map(line => line === '0' ? [] : literals(line));
        const payload = {variableCount: Number(fields[0].value), clauses, fixed: literals(fields[2].value),
            maximumWork: Number(fields[3].value)};
        active = '/api/certificate-demos/cnf/' + crypto.randomUUID();
        byId('cnfFields').disabled = true; run.disabled = true; cancel.hidden = false; cancel.disabled = false;
        status.textContent = 'Belegungen werden gezählt und der Beleg wird geprüft …';
        const response = await fetch(active, {method: 'POST', redirect: 'error',
            headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload)});
        if (!response.ok) throw new Error(await response.text());
        const bytes = await response.text();
        const report = JSON.parse(bytes);
        if (report.schema !== 'regelsuche.cnf-count/v1') throw new Error('Unbekanntes Antwortformat.');
        if (report.status === 'EXACT' && /^(0|[1-9][0-9]*)$/.test(report.count) && report.certificate) {
            status.textContent = `Exakt ${report.count} Belegungen. Verbraucht: ${report.work} Arbeitseinheiten.`;
            fileUrl = URL.createObjectURL(new Blob([bytes], {type: 'application/json'}));
            download.href = fileUrl; download.hidden = false;
        } else if (report.status === 'UNAVAILABLE') {
            const reasons = {WORK_LIMIT: 'Arbeitsbudget erschöpft', TERM_LIMIT: 'Speichergrenze erreicht',
                WIDTH_LIMIT: 'Tabellenbreite überschritten', TIME_LIMIT: 'Zeitgrenze erreicht', CANCELLED: 'Berechnung abgebrochen',
                RUNTIME_BIT_LIMIT: 'Zahlengröße überschritten'};
            status.textContent = `${reasons[report.reason] || report.reason}. Keine exakte Anzahl. Verbraucht: ${report.work} Arbeitseinheiten.`;
        } else throw new Error('Unvollständiger Zählbeleg.');
        status.dataset.status = report.status;
    } catch (error) {
        status.textContent = 'Zählung fehlgeschlagen: ' + error.message; status.dataset.status = 'ERROR';
    } finally {
        active = null; byId('cnfFields').disabled = false; run.disabled = false; cancel.hidden = true;
    }
});

cancel.addEventListener('click', async () => {
    const job = active;
    if (!job) return;
    cancel.disabled = true;
    try {
        const response = await fetch(job + '/cancel', {method: 'POST', redirect: 'error'});
        if (active !== job) return;
        if (response.status === 202) status.textContent = 'Abbruch angefordert …';
        else if (response.status !== 404) throw new Error('Abbruchanforderung fehlgeschlagen.');
    } catch (error) {
        if (active === job) { status.textContent = error.message; cancel.disabled = false; }
    }
});
