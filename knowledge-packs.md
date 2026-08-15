# Knowledge Packs

Regelsuche Knowledge Packs liegen unter
`regelsuche-core/src/main/resources/rules/packs/*.rules.yaml`.

Sie sind kuratierte Core-Pakete und enthalten neben Pattern und Replacement auch
Provenance, Lizenz, Reviewstatus, Risikostufe und Validierungsbeispiele. Erweiterte
SymPy-Packs existieren für polynomiale, trigonometrische, rationale und
logarithmische Identitäten.

Jedes Pack trägt zusätzlich ein `tier`-Feld (`kernel` oder `first-party`, Standard
`first-party`). Kernel-Packs lassen sich nicht abschalten; First-Party-Packs sind für
Baseline-/Ablationsläufe abschaltbar. Details stehen unter [Regel-Tiers und Ablation](rule-tiers.md).

Nur Regeln mit freigegebenem Status wie `VALIDATED` oder `REVIEWED` dürfen
registriert werden. Kandidaten bleiben deaktiviert, bis die Review- und
Validierungsanforderungen erfüllt sind.

## Provenienzgebundene bekannte Strukturen

Ein Knowledge Pack kann zusätzlich bekannte mathematische Strukturen für die
Repräsentationsanalyse deklarieren. Diese Einträge verwenden den vorhandenen
`ExprMatcher` und enthalten mindestens:

- eine stabile Struktur-ID und Domäne;
- ein explizites Erkennungsprofil;
- erforderliche Annahmen und konkrete Folgefähigkeiten;
- Quelle, Lizenz, unveränderliche Revision und Übersetzungshinweise;
- eine minimale unabhängige Evidenzstufe.

Eine erkannte Form ist Klassifikation, kein Beweis. Eine Folgefähigkeit wird erst
freigeschaltet, wenn die Kandidatenvalidierung die deklarierte Evidenzschwelle
erreicht. Provenienz und Evidenzpolitik gehen in die content-addressed
Katalogidentität ein.

Der erste Slice aus SymPys trigonometrischem `fu`-Wissen ist auf SymPy 1.14.0,
Commit `fe935ceb303891d1f8bea4c03b19fd9ec9464b02`, festgelegt. Er ist
experimentell und standardmäßig deaktiviert. Regelsuche übernimmt weder die
Python-Laufzeit noch SymPys globales `simplify()` oder dessen interne
Ausdrucksrepräsentation.

Für Experimente bleiben drei Informationsregime getrennt:

- katalogblinde Discovery aktiviert den Pack erst nach Candidate Formation;
- katalogsichtbare Navigation aktiviert ihn ausdrücklich vor der Suche;
- Rediscovery-Holdouts verbergen Struktur und direkte Regel gemeinsam.

Knowledge Packs sind nicht identisch mit:

- externen Java-Plugins, die Code über `ServiceLoader` laden,
- `.regelsuche`-/`.rules`-Dateien für anwenderdefinierte Regeln,
- aus Suchpfaden gelernten Makros, die Discovery- und Promotion-Gates durchlaufen.

Die gemeinsame Architekturkarte und Auswahlhilfe steht unter
[Erweiterungssystem](extension-system.md). Ein separat veröffentlichbares
Knowledge-Pack-Ökosystem, Installation/Updates und kryptografische Trust-Policies
gehören zum offenen Issue #104.
