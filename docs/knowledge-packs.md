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

## Pattern-Syntax und AST-Semantik

Regelpattern verwenden dieselbe binäre Operatorstruktur wie der produktive
Ausdrucks-AST. Neben der Infixschreibweise akzeptiert der Loader die historische
funktionale Schreibweise als explizite Operator-Aliasse:

| Funktionale Schreibweise | AST-/Infixbedeutung |
|---|---|
| `add(A, B)` | `A + B` |
| `sub(A, B)` | `A - B` |
| `mul(A, B)` | `A * B` |
| `div(A, B)` | `A / B` |
| `pow(A, B)` | `A ^ B` |

Diese fünf Aliasse verlangen exakt zwei Argumente und werden bereits beim Laden
in `PatternExpr.Operation` übersetzt. Eine falsche Stelligkeit schlägt
fehlersicher fehl. Andere Namen wie `sin`, `cos`, `log` oder domänenspezifische
Funktionen bleiben gewöhnliche `PatternExpr.Function`-Knoten.

Ausführbare Patternregeln verwenden standardmäßig das Erkennungsprofil `EXACT`.
Ein Pack darf das Profil einer einzelnen Regel ausdrücklich über
`recognition: ARITHMETIC_AC` beziehungsweise `recognition: ALGEBRAIC_AC`
erweitern. Der Loader übergibt diese Deklaration an `PatternRewriteRule`; fehlt
sie, bleibt die bisherige exakte Semantik unverändert. Das Erkennungsprofil einer
bekannten Struktur erweitert eine Folge-Regel nicht automatisch. Klassifikation
und Ausführung müssen daher bewusst dieselbe zulässige Äquivalenz deklarieren,
wenn jede erkannte Form unmittelbar ausführbar sein soll. Das Profil gehört zur
content-addressed Regelinventaridentität.

Damit muss beispielsweise

```text
add(pow(sin(?X), 2), pow(cos(?X), 2))
```

gegen denselben produktiven AST matchen wie

```text
sin(?X)^2 + cos(?X)^2
```

Die CI charakterisiert sowohl die Gleichheit der beiden Patternformen als auch
die reale Ausführung einer geladenen funktional notierten Knowledge-Pack-Regel
gegen einen gewöhnlichen Infix-Ausdruck. Für AC-deklarierte Regeln wird außerdem
die vertauschte Operandenreihenfolge ausgeführt; Regeln ohne Deklaration bleiben
nachweislich exakt. Reine Metadaten- oder Nicht-Leerheitsprüfungen gelten nicht
als Nachweis, dass eine Regel im Produktionsmotor ausführbar ist.

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
den tatsächlichen ausführbaren Regelbestand, Formation-Katalog,
Post-Freeze-Katalog und Holdout-Verpflichtung an eine content-addressed Identität.
Die vier Tracks aus #663 werden damit nicht nur dokumentiert, sondern durch
verschiedene zugängliche Informationsflächen ausgeführt:

- **R1 – targetfreie Kompression:** Formation und Auswertung sehen keinen
  bekannten-Strukturen-Katalog. Der vorab festgelegte primitive Regelbestand
  bleibt sichtbar und wird als eigener Inventory-Hash gebunden.
- **R2 – katalogblinde Post-hoc-Brücke:** Formation sieht weder Katalogeinträge
  noch deren deklarierte Regel-Packs. Erst nachdem der vollständige
  Kandidatensatz eingefroren wurde, werden der gebundene Katalog, seine
  Konsequenzen und der zugehörige Regelbestand zur Klassifikation offengelegt.
- **R3 – katalogsichtbare Wissensnavigation:** Formation und spätere
  Klassifikation verwenden denselben ausdrücklich ausgewählten Katalog und
  denselben ausführbaren Regelbestand.
- **R4 – Hidden-Structure-Rediscovery:** Die festgelegte Struktur und alle von
  ihr deklarierten direkten Regel-Packs werden während der Formation
  zurückgehalten. Der vollständige Katalog und die Holdout-Details werden erst
  nach dem Kandidaten-Freeze offengelegt.

Die Regel-Inventaridentität umfasst nicht nur Pack-IDs, sondern die kanonischen
Quell- und Zielpattern, Recognition-Profile, Orientierungs- und Kostenmerkmale
sowie die vollständigen `RuleDescriptor`-Metadaten. Eine Regeländerung erzeugt
daher auch bei unveränderter Pack-Auswahl eine neue Experimentidentität.

R2 und R4 benötigen ein `CandidateFreezeReceipt`, das sowohl den eingefrorenen
Kandidatensatz als auch exakt dieselbe Informationsgrenze bindet. Das Receipt
wird ausschließlich durch `freezeCandidates(...)` ausgegeben und kann nicht über
einen öffentlichen Konstruktor aus bekannten Hashes nachgebaut werden. Ein
Receipt aus einem anderen Track oder einer anderen Inventar-Auswahl wird
abgewiesen.

Bei R2 werden alle Regel-Packs zurückgehalten, die von den verborgenen
Katalogeinträgen als Folgeinventar deklariert sind. Bei R4 betrifft das die
angeforderten Holdout-Strukturen. Weil Knowledge Packs die kleinste
aktivierbare Einheit sind, kann dies konservativ weitere Strukturen desselben
Packs ausblenden; die tatsächlich ausgeschlossenen Struktur-IDs und Packs werden
im Post-Freeze-Artefakt ausgewiesen. Falls eine versteckte Struktur eine direkte
Regel nennt, aber keinen zugehörigen Rule-Pack deklariert, schlägt die
Konfiguration fehlersicher fehl.

Knowledge Packs sind nicht identisch mit:

- externen Java-Plugins, die Code über `ServiceLoader` laden,
- `.regelsuche`-/`.rules`-Dateien für anwenderdefinierte Regeln,
- aus Suchpfaden gelernten Makros, die Discovery- und Promotion-Gates durchlaufen.

Die gemeinsame Architekturkarte und Auswahlhilfe steht unter
[Erweiterungssystem](extension-system.md). Ein separat veröffentlichbares
Knowledge-Pack-Ökosystem, Installation/Updates und kryptografische Trust-Policies
gehören zum offenen Issue #104.
