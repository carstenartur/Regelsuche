# Symbolische Nebenbedingungen (Assumptions)

Das Paket `de.regelsuche.assumption` modelliert Voraussetzungen, unter denen
eine Transformation, ein Kandidat oder eine bekannte mathematische Struktur
gültig beziehungsweise anwendbar ist. Eine Annahme ist keine beiläufige
Textnotiz: Sie gehört zur Identität und Evidence eines mathematischen Ergebnisses.

## Modell

`Assumption(kind, expression, symbols)` verbindet eine typisierte Art mit der
konkreten symbolischen Aussage. Unterstützt werden insbesondere:

- `NON_ZERO`, `POSITIVE`, `NON_NEGATIVE`;
- `INTEGER`, `NATURAL`, `REAL`, `RATIONAL`;
- `INVERTIBLE` und `DOMAIN_MEMBERSHIP`;
- `CUSTOM_PREDICATE` für noch nicht strukturell modellierte Aussagen;
- `UNKNOWN` für explizit nicht entschiedene Information.

Die veralteten Werte `DOMAIN` und `CUSTOM` bleiben nur zur Quellkompatibilität
erhalten. Neue Implementierungen verwenden `DOMAIN_MEMBERSHIP` beziehungsweise
`CUSTOM_PREDICATE`.

Praktische Konstruktoren sind unter anderem:

```java
Assumption.nonZero("b");
Assumption.positive("x");
Assumption.natural("n");
Assumption.invertible("A");
```

`AssumptionContext` sammelt die entlang eines Pfads oder in einem Run bekannten
Annahmen. `AssumptionSignature` erzeugt daraus eine normalisierte,
deterministische Signatur für Zustands-, Evidence- und Cache-Grenzen.

## Dreiwertige Auswertung

Eine erforderliche Annahme wird nicht als boolescher Spezialfall eines einzelnen
Backends ausgewertet. Der gemeinsame Vertrag lautet:

```text
required Assumption + known AssumptionContext
  -> AssumptionEvaluator(s)
  -> per-evaluator evidence
  -> TRUE | FALSE | UNKNOWN
  -> apply | reject | retain obligation
```

`AssumptionTruthValue` unterscheidet:

- `TRUE`: Die deklarierte Evidence erfüllt die Voraussetzung.
- `FALSE`: Die deklarierte Evidence widerlegt die Voraussetzung.
- `UNKNOWN`: Die Voraussetzung wurde nicht entschieden.

`UNKNOWN` darf niemals stillschweigend als `TRUE` behandelt werden.

### Evaluator-Evidence

Jeder `AssumptionEvaluator` besitzt eine stabile ID und Revision. Seine
`AssumptionEvaluationEvidence` enthält zusätzlich:

- das dreiwertige Ergebnis;
- eine maschinenlesbare Disposition;
- eine Erklärung;
- optional einen Verweis auf ein Solver-, Proof- oder anderes Evidence-Artefakt.

Die Dispositionen sind:

| Disposition | Bedeutung |
| --- | --- |
| `EVALUATED` | Der Evaluator hat fachlich ausgewertet; das Ergebnis kann `TRUE`, `FALSE` oder `UNKNOWN` sein. |
| `UNSUPPORTED` | Die Voraussetzung liegt außerhalb des unterstützten Fragments. |
| `TIMEOUT` | Das deklarierte Zeit- oder Arbeitsbudget wurde ausgeschöpft. |
| `TECHNICAL_FAILURE` | Der Evaluator konnte aus technischem Grund kein fachliches Ergebnis liefern. |

Die letzten drei Dispositionen tragen immer `UNKNOWN`. Timeout, fehlende
Übersetzung oder technische Nichtverfügbarkeit sind damit weder mathematische
Widerlegung noch Zustimmung.

### Portfolio und Konflikte

`AssumptionEvaluatorPortfolio` führt eine nichtleere, deterministisch sortierte
Evaluator-Menge aus. Seine Identität ist content-addressed und bindet
Portfolio-Revision, Evaluator-ID, Evaluator-Revision und Implementierungsklasse.
Eine geänderte Evaluatorauswahl oder Revision erzeugt daher eine andere
`evaluatorProfileHash`.

Die Aggregation ist fehlersicher:

- mindestens ein `TRUE` und kein `FALSE` ergibt `TRUE`;
- mindestens ein `FALSE` und kein `TRUE` ergibt `FALSE`;
- `TRUE` und `FALSE` gemeinsam ergeben `UNKNOWN` plus `conflicting=true`;
- ausschließlich nicht entscheidende Ergebnisse ergeben `UNKNOWN`.

Alle Einzelergebnisse bleiben in `AssumptionEvaluation` sichtbar. Doppelte
Evaluator-IDs und falsch attribuierte Evidence werden abgewiesen.

## Lokaler Evaluator

`KnownAssumptionEvaluator` ist der erste produktive Evaluator. Er verwendet die
explizit bekannten typisierten Annahmen und die vorhandenen monotonen
Implikationsregeln, beispielsweise:

```text
NATURAL  -> INTEGER -> RATIONAL -> REAL
POSITIVE -> NON_ZERO
```

Fehlt eine ausreichende explizite Aussage, bleibt das Ergebnis `UNKNOWN`. Der
Evaluator erfindet keine Annahmen und ruft kein externes System auf.

Ein rein lokales Portfolio entsteht über:

```java
AssumptionEvaluatorPortfolio portfolio =
    AssumptionEvaluatorPortfolio.localOnly();
AssumptionEvaluation evaluation = portfolio.evaluate(required, context);
```

## Externe Evaluatoren

SymPy, Z3, cvc5 und formale Prover können später denselben Vertrag implementieren.
Dabei gelten folgende Grenzen:

- Externe Systeme laufen reproduzierbar und gepinnt über JUnit/Testcontainers;
- Python, Solver oder weitere Laufzeiten werden nicht zu Host-Voraussetzungen;
- Eingabe, unterstütztes Fragment, Timeout und Ressourcenbudget sind explizit;
- `UNSUPPORTED`, `TIMEOUT` und `TECHNICAL_FAILURE` bleiben maschinenlesbar;
- Solver- oder CAS-Ausgaben sind Evidence, aber nicht automatisch formaler Beweis;
- erwartete technische Terminalzustände werden in `UNKNOWN`-Evidence übersetzt;
  Vertragsverletzungen und fehlerhafte Attribution schlagen dagegen fehlersicher
  fehl.

Die Portfolio-ID muss in Runs, Kandidaten, E-Graph-Saturation und Proof-
Obligationen mitgeführt werden, sobald diese Verbraucher den Vertrag anbinden.

## Integration in Regeln

`RewriteRule.assumptions(subtree)` liefert die Voraussetzungen einer konkreten
Regelanwendung. Rationale, logarithmische, radikale, trigonometrische und
Analysis-Regeln erzeugen bereits passende Annahmen, etwa einen von null
verschiedenen Nenner oder ein positives Logarithmusargument.

Eine bedingte Regel darf künftig erst dann angewendet oder als E-Graph-Union
übernommen werden, wenn ihr deklarierter Guard gemäß dem aktiven Portfolio
`TRUE` ist. `FALSE`, `UNKNOWN` und Konflikte bleiben getrennte, sichtbare
Terminalentscheidungen. Die produktive Einbindung in bedingte Rewrites und
E-Class-Analysen wird in Issue #662 verfolgt.

## Weitergabe an Proof- und Discovery-Evidence

Proof-, Gegenbeispiel- und Representation-Discovery-Komponenten müssen
mindestens binden:

- erforderliche Annahme;
- normalisierte Kontextsignatur;
- Evaluator-Portfolio-Hash;
- aggregiertes Ergebnis und Konfliktstatus;
- vollständige Einzelevidence mit Backend-Revisionen.

Damit kann die Discovery-Oberfläche aus Issue #669 später nachvollziehbar
anzeigen, welche Annahme erfüllt, widerlegt, unbekannt oder zwischen Evaluatoren
umstritten ist, ohne aus einem Backend-Label einen stärkeren Claim abzuleiten.
