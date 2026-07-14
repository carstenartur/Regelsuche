# Evidenzbewusste Interessantheitsbewertung

Regelsuche bewertet mathematische Discovery-Kandidaten entlang mehrerer getrennter
Achsen. Ein hoher Rang ist weder ein Beweis noch eine Neuheitsbehauptung. Die
Bewertung soll vielmehr beantworten, welche **nicht widerlegten** Kandidaten unter
einem bestimmten Forschungsprofil zuerst untersucht werden sollten.

## Erst Zulässigkeit, dann Rang

`EvidenceAwareInterestingnessAssessor` prüft vor jeder Rangbildung harte
Ausschlussgründe:

- der Kandidat ist bereits `REJECTED`;
- ein Gegenbeispiel wurde gefunden;
- ein Oracle widerspricht;
- positive oder negative Prüfungen sind fehlgeschlagen;
- konfigurierte, ausgeführte und übersprungene Prüfungen sind inkonsistent
  bilanziert.

Ein solcher Kandidat erhält `BLOCKED`. Ein strukturell auffälliges Muster kann
diesen Status nicht durch einen hohen Teilscore überstimmen.

Noch nicht widerlegte, aber unvollständig geprüfte Kandidaten bleiben als
`RANKABLE_INCOMPLETE` sichtbar. Übersprungene Checks, fehlende Negativsuiten,
unklare Counterexample-Suche, unbekannte Projektneuheit, fehlende Held-out-
Übertragung und noch nicht gemessener Downstream-Nutzen erzeugen benannte
Warnungen und einen expliziten Risikoabzug.

## Benannte Rangkomponenten

Das Schema `regelsuche.interestingness-assessment/v1` berichtet acht ganzzahlige
Permille-Komponenten:

1. Kompression des beobachteten Umformungspfads,
2. Generalisierung über Platzhalter und Zeugen,
3. unabhängige Evidenz,
4. Wiederverwendbarkeit,
5. strukturelle Überraschung gegenüber bekannten Regeln,
6. gemessene Übertragung auf zurückgehaltene Familien,
7. Einfachheit der benötigten Annahmen,
8. gepaarter Downstream-Nutzen.

Proof- und Counterexample-Status sind absichtlich **keine positiven
Rangkomponenten**. Sie bleiben separate Evidenz- und Gate-Achsen.

Triviale Kontrollen werden sichtbar markiert:

- reine Alpha-Umbenennung,
- reine Formatvariante,
- generische Neutral-Element-Normalisierung.

Sie erhalten einen eigenen Kontrollabzug statt einer heimlichen Sonderbehandlung.

## Explizite Profile

Zwei Profile sind versioniert:

- `THEORY_DISCOVERY` gewichtet strukturelle Überraschung, Generalisierung und
  Cross-Family-Übertragung stärker;
- `SEARCH_REUSE` gewichtet Kompression, Wiederverwendbarkeit und gemessenen
  Suchnutzen stärker.

Die Gewichte jedes Profils summieren sich auf 1000 und werden für jede Komponente
mit ausgegeben. Ein Profilwechsel verändert nur Gewichte, nicht die zugrunde
liegenden Rohkomponenten.

## Calibration und gehaltene TEST-Familien

`InterestingnessProfileCalibrationEvaluator` wählt ein Profil ausschließlich auf
dem `CALIBRATION`-Split. Danach wird dieses Profil eingefroren und unverändert auf
`TEST` angewendet.

Die Evaluation wird als `SPLIT_REJECTED` abgebrochen, wenn:

- eine mathematische Strukturfamilie beide Splits berührt;
- derselbe strukturelle Signaturhash beide Splits berührt;
- IDs doppelt vorkommen;
- ein Split zu klein ist oder keine unterschiedlichen Relevanzlabels enthält.

Relevanzlabels werden erst nach der Kandidatenbewertung für die Paarvergleichs-
metrik benutzt. Sie werden nie an den Assessor übergeben. Deshalb bleiben bei einer
reinen Änderung von TEST-Labels unverändert:

- das ausgewählte Profil,
- der predictive dataset hash,
- alle Assessment-Hashes,
- alle Komponenten und Gesamtwerte.

Ein separater `labeledEvaluationHash` macht die geänderte Auswertung dennoch
sichtbar.

## Pareto-Bericht

Das Schema `regelsuche.interestingness-calibration/v1` weist zusätzlich die
Pareto-Front der TEST-Kandidaten aus. Ein Kandidat wird nur dann dominiert, wenn ein
anderer auf allen Rohkomponenten mindestens gleich gut ist, keine höheren Evidenz-
oder Kontrollabzüge besitzt und auf mindestens einer Achse strikt besser ist.

Damit bleibt sichtbar, ob ein Kandidat beispielsweise besonders überraschend,
aber noch wenig wiederverwendbar ist, statt alle Zielkonflikte in einer einzigen
Zahl zu verstecken.

## Wissenschaftliche Grenzen

Die aktuellen Artefakte unterscheiden ausdrücklich:

- mathematische Gültigkeit,
- Counterexample-Status,
- projektinterne Neuheit,
- externe mathematische Neuheit,
- Interessantheit,
- Suchnutzen,
- Evidenzvollständigkeit,
- Public-Evidence-Status.

`NOVEL_WITHIN_PROJECT` bedeutet nicht, dass eine Aussage in der Literatur neu ist.
`RANKABLE_COMPLETE` bedeutet nicht, dass sie formal bewiesen ist. Ein hoher
Interessantheitsrang aktiviert, promotet oder veröffentlicht keinen Kandidaten.

## Reproduzierbare Artefakte

Die fokussierten Tests erzeugen:

```text
regelsuche-learning/build/reports/interestingness-assessment/report.json
regelsuche-learning/build/reports/interestingness-calibration/report.json
```

Die veröffentlichten Schemata liegen unter:

```text
docs/schemas/regelsuche-interestingness-assessment-v1.schema.json
docs/schemas/regelsuche-interestingness-calibration-v1.schema.json
```

Die nächsten #223-Schritte sind ein größerer eingefrorener Kandidatenkorpus,
Unsicherheits- und Sensitivitätsberichte sowie vorab definierte Akzeptanzschwellen
für fachlich kuratierte Relevanzlabels.
