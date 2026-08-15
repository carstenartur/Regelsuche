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

## Erzwingbare Informationsregime

`RepresentationDiscoveryInformationBoundary` bindet Regel-Auswahl,
Formation-Katalog, Post-Freeze-Katalog und Holdout-Verpflichtung an eine
content-addressed Identität. Die vier Tracks aus #663 werden damit nicht nur
dokumentiert, sondern durch verschiedene zugängliche Informationsflächen
ausgeführt:

- **R1 – targetfreie Kompression:** Formation und Auswertung sehen keinen
  bekannten-Strukturen-Katalog.
- **R2 – katalogblinde Post-hoc-Brücke:** Formation sieht keinen Katalog. Erst
  nachdem der vollständige Kandidatensatz eingefroren wurde, kann der gebundene
  Katalog zur Klassifikation offengelegt werden.
- **R3 – katalogsichtbare Wissensnavigation:** Formation und spätere
  Klassifikation verwenden denselben ausdrücklich ausgewählten Katalog.
- **R4 – Hidden-Structure-Rediscovery:** Die festgelegte Struktur und alle von
  ihr deklarierten direkten Regel-Packs werden während der Formation
  zurückgehalten. Der vollständige Katalog und die Holdout-Details werden erst
  nach dem Kandidaten-Freeze offengelegt.

R2 und R4 benötigen ein `CandidateFreezeReceipt`, das sowohl den eingefrorenen
Kandidatensatz als auch exakt dieselbe Informationsgrenze bindet. Ein Receipt aus
einem anderen Track oder einer anderen Pack-Auswahl wird abgewiesen. Bei R4 wird
der komplette deklarierte Regel-Pack deaktiviert; nur das Struktur-Label zu
verbergen, während ein direktes Makro ausführbar bleibt, ist unzulässig. Falls
eine versteckte Struktur eine direkte Regel nennt, aber keinen zugehörigen
Rule-Pack deklariert, schlägt die Konfiguration fehlersicher fehl.

Knowledge Packs sind nicht identisch mit:

- externen Java-Plugins, die Code über `ServiceLoader` laden,
- `.regelsuche`-/`.rules`-Dateien für anwenderdefinierte Regeln,
- aus Suchpfaden gelernten Makros, die Discovery- und Promotion-Gates durchlaufen.

Die gemeinsame Architekturkarte und Auswahlhilfe steht unter
[Erweiterungssystem](extension-system.md). Ein separat veröffentlichbares
Knowledge-Pack-Ökosystem, Installation/Updates und kryptografische Trust-Policies
gehören zum offenen Issue #104.
