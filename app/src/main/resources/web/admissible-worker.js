'use strict';
importScripts('admissible-proof.js');
self.onmessage = async event => {
    try {
        if (event.data.kind === 'example') {
            const checked = AdmissibleProof.parseProof(AdmissibleProof.example);
            self.postMessage({ok: true, result: {example: true, selectedPolicy: null,
                runs: [{...checked, case: 'Fenster 0 bis 8', role: 'selected', policy: 'Lehrbeispiel',
                    status: 'OPTIMAL', cost: null, visited: null}]}});
        } else if (event.data.kind === 'bundle') {
            self.postMessage({ok: true, result: await AdmissibleProof.checkBundle(event.data.text)});
        } else throw new Error('Unbekannter Prüfauftrag.');
    } catch (error) {
        self.postMessage({ok: false, message: error instanceof Error ? error.message : 'Prüfung fehlgeschlagen.'});
    }
};
