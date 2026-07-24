# Knowledge Packs

Regelsuche Knowledge Packs liegen unter
`regelsuche-core/src/main/resources/rules/packs/*.rules.yaml`.

Sie sind kuratierte Core-Pakete und enthalten neben Pattern und Replacement auch
Provenance, Lizenz, Reviewstatus, Risikostufe und Validierungsbeispiele. Erweiterte
SymPy-Packs existieren für polynomiale, trigonometrische, rationale und
logarithmische Identitäten.

Nur Regeln mit freigegebenem Status wie `VALIDATED` oder `REVIEWED` dürfen
registriert werden. Kandidaten bleiben deaktiviert, bis die Review- und
Validierungsanforderungen erfüllt sind.

Knowledge Packs sind nicht identisch mit:

- externen Java-Plugins, die Code über `ServiceLoader` laden,
- `.regelsuche`-/`.rules`-Dateien für anwenderdefinierte Regeln,
- aus Suchpfaden gelernten Makros, die Discovery- und Promotion-Gates durchlaufen.

Die gemeinsame Architekturkarte und Auswahlhilfe steht unter
[Erweiterungssystem](extension-system.md). Ein separat veröffentlichbares
Knowledge-Pack-Ökosystem, Installation/Updates und kryptografische Trust-Policies
gehören zum offenen Issue #104.
