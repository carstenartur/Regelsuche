# Eingefrorene Evaluation der targetfreien Repräsentationssuche

Die erste SymPy-Brücke zeigt einen einzelnen ausführbaren R2-Pfad. Für einen
vergleichbaren Befund reicht ein solcher Demonstrator jedoch nicht aus. Diese
Evaluation friert deshalb vor jeder weiteren Ausführung eine vollständige
Matrix aus Fällen und targetblinden Suchpolitiken ein.

## Getrennte Informationsflächen

Drei Klassenpfad-Ressourcen bilden die Preregistrierung:

```text
target-free-representation-formation-v1.json
target-free-representation-qualification-v1.json
target-free-representation-preregistration-v1.json
```

Die **Formation** enthält ausschließlich:

- Ausgangsausdruck und explizite Annahmen;
- R1- oder R2-Informationsspur;
- `RuleProfile.MINIMAL_KERNEL` und explizit je Fall aktivierte Rule-Packs;
- sämtliche endlichen Arbeitsbudgets einschließlich Engine-Aufrufen und
  zugelassenen primitiven Schritten;
- Policy-ID, Produktionsadapter, Aufrufschnittstelle, Konstruktorvertrag,
  deterministischen Seed, initiale Annahmenpolitik und targetblinde
  Auswahlgrenze.

Die **Qualifikation** enthält die erst nach dem Kandidaten-Freeze zulässigen
Referenzausdrücke, Capability-Beziehungen und Negativbedingungen. Der
Plangenerator öffnet diese Datei nicht. Er übernimmt ausschließlich ihren in
der Preregistrierung gebundenen SHA-256-Wert und die exakte Bytezahl.

Die **Preregistrierung** bindet ihre eigenen Bytes sowie Formation und
Qualifikation fail-closed:

```text
Formation:      6640 Bytes
                sha256:aae56e8ae14b2df8b9929f9e0a554205f7438e7ec8386a8a8520da03342fd223
Qualifikation:  2947 Bytes
                sha256:b87239dcc3a3bed26cee1db87240cb6d7587f0f9a551e5a279f9738cd8866835
Preregistrierung:
                1019 Bytes
                sha256:1ce6d02d7d87161de274a88bbc2bcfe92816dbb182287397176805a47e274610
```

Eine Änderung an einer dieser Ressourcen erzeugt nicht stillschweigend eine
neue Studienautorität, sondern muss ausdrücklich als neue Version eingefroren
werden.

## Eingefrorene Ausführungspolitik

Vor der Ausführung ist festgelegt:

- Basisinventar `RuleProfile.MINIMAL_KERNEL`;
- explizite fallbezogene Aktivierung von Core- und Knowledge-Pack-IDs;
- R2-Zurückhaltung jedes aktivierten Packs, das eine erst nach dem Freeze
  sichtbare bekannte Struktur unmittelbar beherrscht;
- direkte Verwendung der sechs ursprünglichen Formation-Budgets durch
  `TargetFreeRepresentationSearch`;
- zusätzliche, explizite Budgets für `significantImprovementThreshold`,
  `maxExpandingSteps`, `beamWidth`, Engine-Aufrufe und zugelassene primitive
  Schritte;
- Vereinigung der deklarierten Fallannahmen mit den Annahmen jedes erhaltenen
  Zustands;
- deterministische globale Transformationsreihenfolge nach Regel, Ausdruck,
  Application-Key und primitiven Regel-IDs;
- für den Random-Control ein Neustart vom eingefrorenen Seed bei jedem Lauf,
  kanonische Sortierung vor dem Shuffle sowie Erhalt der Annahmen im Zustand
  und in seiner Identität.

Gleiche konfigurierte Obergrenzen dürfen später nicht als gleiche tatsächlich
verbrauchte Arbeit ausgegeben werden. Der Ausführungsbericht muss die realen
Engine-Aufrufe, zugelassenen primitiven Schritte, erzeugten Transitionen,
explorierten Zustände und erhaltenen Kandidaten je Zeile ausweisen.

## 6 × 4-Matrix

Die Formation enthält sechs fachlich unterschiedliche Fälle:

1. annahmesensitive Kürzung von `(x * 1) / x` über das explizit aktivierte
   Core-Pack für rationale Cancellation;
2. katalogblinde trigonometrische Wissensbrücke;
3. Neutral-Element-Kompression von `x + 0`;
4. occurrence-lokale trigonometrische Brücke in einem nichtassoziativen
   Multiplikationskontext, der die bekannte Summenstruktur als eigenen
   AST-Teilbaum erhält;
5. Kompression eines wiederholten Terms mit expliziter Termkollektion;
6. Teleskopierungsbrücke unter den Annahmen `n != 0` und `n + 1 != 0`.

Jeder Fall wird mit vier im Checkout vorhandenen targetblinden Adaptern
kombiniert:

| Policy | Produktionsadapter | Konstruktor | Zweck |
|---|---|---|---|
| `BOUNDED_ENUMERATION_V1` | `TargetFreeRepresentationSearch` | parameterlos | begrenzter Zustandsbestand und rohe Pareto-Front |
| `RANDOM_MONTE_CARLO_V1` | `RandomMonteCarloSearchStrategy` | `long` | reproduzierbarer Random-Valid-Kontrolllauf |
| `SCALAR_BEST_FIRST_V1` | `BestFirstSearchStrategy` | parameterlos | eingefrorene skalare targetblinde Auswahl |
| `STRUCTURAL_DIVERSITY_V1` | `StructuralDiversitySearchStrategy` | parameterlos | strukturell diverse targetblinde Retention |

Damit entstehen genau 24 Konfigurationen. Vor der tatsächlichen Ausführung muss
jede Zeile ausdrücklich so ausgewiesen werden:

```text
status         = REMAINING
terminalReason = NOT_EXECUTED
```

`TargetFreeRepresentationEvaluationPlan` erzeugt für jede Kombination eine
stabile Konfigurations-ID, bindet die exakte Repository-Revision und verwirft
fehlende, zusätzliche, doppelte oder bereits als ausgeführt dargestellte
Einträge. Vor dem Schreiben werden außerdem Adapterklassen,
Aufrufschnittstellen, Konstruktorverträge, Inventar-IDs und Budgets geprüft.

## Ausführbare Brücken und Kontrollen

Die eingefrorenen Fälle verweisen nur auf tatsächlich vorhandene
Produktionsverträge:

- die occurrence-lokale Brücke liegt unter einer äußeren Multiplikation; die
  trigonometrische Summe bleibt daher trotz kanonischer AC-Normalisierung ein
  echter AST-Teilbaum und qualifiziert die konkrete Konsequenz
  `rule:sympy.trig.pythagorean`;
- der Rational-Pack deklariert die bekannte Struktur
  `sympy.rational.telescoping-unit-step` und bindet sie an
  `rule:sympy.rational.partial_fraction.telescoping`;
- die Kürzungskontrolle verwendet eine Form, auf die
  `ast_cancel_division_factor` tatsächlich anwendbar ist und deren Annahme
  `x != 0` erhalten bleiben muss;
- Neutral-Element- und Termkollektionsfälle aktivieren nur die dafür benötigten
  Inventarteile.

Damit kann ein negatives Resultat später von einem nicht ausführbaren Fixture
unterschieden werden.

## Checkout-Evidence

Der Gradle-Task

```bash
./gradlew --no-daemon \
  :regelsuche-discovery:generateTargetFreeRepresentationEvaluationPlan
```

schreibt:

```text
regelsuche-discovery/build/reports/representation-discovery/evaluation-plan/
  representation-discovery-plan.json
```

Der Task ist Bestandteil von Modul- und Root-`check`. Die
Repository-Revision wird fail-closed aus einer expliziten Autorität oder einem
sauberen Checkout bestimmt.

Die Charakterisierung prüft insbesondere:

- exakt sechs Fall-IDs, vier Policy-IDs und die fest gepinnte 24-Zeilen-Matrix;
- eindeutige inhaltsadressierte Konfigurations-IDs;
- die Byte- und Hashbindung aller drei Ressourcen;
- bekannte Inventar-IDs und vollständige Budgetbereiche;
- vorhandene Adapterklassen, Schnittstellen, Konstruktorverträge und Seeds;
- reproduzierbaren Random-Control trotz wiederholter Adapterverwendung und
  unterschiedlicher Engine-Iteratorreihenfolge;
- Erhalt von Transformations- und Fallannahmen;
- occurrence-lokale und Teleskopierungsbrücken erst nach dem Freeze;
- kanonische, byteidentische Ausgabe und Manipulationserkennung;
- Abwesenheit aller Referenzausdrücke, Capability-Labels und
  Qualifikationsfelder aus dem generierten Plan.

## Reproduktion

```bash
./gradlew --no-daemon \
  :regelsuche-core:test \
  --tests de.regelsuche.knowledge.KnowledgePackRegistryTest \
  :regelsuche-search:test \
  --tests de.regelsuche.search.strategy.SearchStrategyTest \
  :regelsuche-discovery:test \
  --tests de.regelsuche.discovery.representation.TargetFreeRepresentationEvaluationPlanTest \
  :regelsuche-discovery:generateTargetFreeRepresentationEvaluationPlan
```

Der vollständige Repository-Vertrag bleibt:

```bash
./gradlew --no-daemon ciCheck
mvn --batch-mode --no-transfer-progress -Pfull verify
```

## Claim-Grenze

Der erzeugte Plan ist noch **kein Ergebnis der 24 Suchläufe**. Er beweist keine
mathematische Neuheit, globale Optimalität, CPU-Zeit-Gleichheit oder allgemeine
Überlegenheit. Er stellt die Voraussetzung dafür her, dass die folgenden
Ausführungen vollständig bilanziert, reproduzierbar, annahmenerhaltend und frei
von unbemerkter Zielformsteuerung verglichen werden können.
