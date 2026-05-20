document.getElementById('searchForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = event.target;
    const payload = {
        expression: form.expression.value,
        type: form.type.value,
        profile: form.profile.value
    };
    const out = document.getElementById('searchOutput');
    out.textContent = 'Suche läuft ...';
    try {
        const response = await fetch('/api/search', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        const text = await response.text();
        out.textContent = text;
    } catch (ex) {
        out.textContent = 'Fehler: ' + ex;
    }
});

document.getElementById('reloadPaths').addEventListener('click', async () => {
    const out = document.getElementById('pathsOutput');
    out.textContent = 'Lade ...';
    const response = await fetch('/api/paths');
    out.textContent = await response.text();
});

document.getElementById('reloadInventory').addEventListener('click', async () => {
    const out = document.getElementById('inventoryOutput');
    out.textContent = 'Lade ...';
    const response = await fetch('/api/inventory');
    out.textContent = await response.text();
});
