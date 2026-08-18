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

Die **Formation** enthält nur:

- Ausgangsausdruck und explizite Annahmen;
- R1- oder R2-Informationsspur;
- endliches Arbeitsbudget;
- Policy-ID, ausführbare Adapterklasse und targetblinde Auswahlgrenze.

Die **Qualifikation** enthält die erst nach dem Kandidaten-Freeze zulässigen
Referenzausdrücke, Capability-Beziehungen und Negativbedingungen. Der
Plangenerator öffnet diese Datei nicht. Er übernimmt ausschließlich ihren in
der Preregistrierung gebundenen SHA-256-Wert und die exakte Bytezahl.

Die **Preregistrierung** bindet beide Ressourcen bytegenau und legt die
vollständige Matrix, den Ausgangsstatus und die Claim-Grenze fest:

```text
Formation:      sha256:057245d5097e737147046502f2e2c20519869f98e3cd741277e9ee7c901c3c8f
Qualifikation:  sha256:a0d45d9ddf49aaa895f4c759bbcb33c646f16c781923b7303df90342e1d03072
Preregistrierung:
                sha256:02b437322ebfd175d482f59e40da169ad5a927c5934b702023b7134d8c364464
```

## 6 × 4-Matrix

Die Formation enthält sechs unterschiedliche fachliche Fälle:

1. annahmesensitive Kürzung von `x / x`;
2. katalogblinde trigonometrische Wissensbrücke;
3. Neutral-Element-Kompression von `x + 0`;
4. occurrence-lokale Binomstruktur in einem größeren Ausdruck;
5. Kompression eines wiederholten Terms;
6. eine mögliche Teleskopierungsbrücke unter expliziten Nennerannahmen.

Jeder Fall wird mit vier bereits im Checkout vorhandenen Policy-Adaptern
kombiniert:

| Policy | Produktionsadapter | Zweck |
|---|---|---|
| `BOUNDED_ENUMERATION_V1` | `TargetFreeRepresentationSearch` | vollständiger begrenzter Zustandsbestand plus rohe Pareto-Front |
| `RANDOM_MONTE_CARLO_V1` | `RandomMonteCarloSearchStrategy` | deterministisch seedbarer Zufallskontrolllauf |
| `SCALAR_BEST_FIRST_V1` | `BestFirstSearchStrategy` | eingefrorene skalare targetblinde Kostensteuerung |
| `STRUCTURAL_DIVERSITY_V1` | `StructuralDiversitySearchStrategy` | strukturell diverse targetblinde Retention |

Damit entstehen genau 24 Konfigurationen. Vor der tatsächlichen Ausführung muss
jede Zeile ausdrücklich so ausgewiesen werden:

```text
status         = REMAINING
terminalReason = NOT_EXECUTED
```

`TargetFreeRepresentationEvaluationPlan` erzeugt für jede Kombination eine
stabile Konfigurations-ID, bindet die exakte Repository-Revision und verwirft
fehlende, zusätzliche, doppelte oder bereits als ausgeführt dargestellte
Einträge.

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

Der Task ist Bestandteil von Modul- und Root-`check`. Die Repository-Revision
wird wie beim unveränderlichen Discovery-Run fail-closed aus einer expliziten
Autorität oder einem sauberen Checkout bestimmt.

Die Charakterisierung prüft insbesondere:

- exakt sechs Fälle, vier Policies und 24 Konfigurationen;
- die vollständige kartesische Matrix in stabiler Reihenfolge;
- vorhandene Adapterklassen;
- Byte- und Hashbindung beider getrennten Ressourcen;
- kanonische, wiederholbar identische Planausgabe;
- Manipulationserkennung;
- Abwesenheit aller Referenzausdrücke, Capability-Labels und
  Qualifikationsfelder aus dem generierten Plan.

## Reproduktion

```bash
./gradlew --no-daemon \
  :regelsuche-discovery:test \
  --tests de.regelsuche.discovery.representation.TargetFreeRepresentationEvaluationPlanTest \
  :regelsuche-discovery:generateTargetFreeRepresentationEvaluationPlan
```

## Claim-Grenze

Der erzeugte Plan ist noch **kein Ergebnis der 24 Suchläufe**. Er beweist auch
keine mathematische Neuheit, Überlegenheit, globale Optimalität oder
CPU-Zeit-Gleichheit. Er stellt die Voraussetzung dafür her, dass die folgenden
Ausführungen vollständig bilanziert, reproduzierbar und frei von einer
unbemerkten Zielformsteuerung verglichen werden können.
