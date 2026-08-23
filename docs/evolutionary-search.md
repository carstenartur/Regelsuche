# Evolutionäre Suche und gelernte Regelprogramme

**Implementierungsstand: 23. August 2026**

Regelsuche modelliert evolutionäre Kandidaten als explizite, replayfähige
`EvolutionGenome`s. Kandidaten können ausgeführt, mutiert, in Populationen
bewertet und reproduzierbar gespeichert werden. Evolution allein verleiht
jedoch weder mathematische Gültigkeit noch Produktionsvertrauen.

## Versionierte Grundlage

Die Implementierung liegt in `regelsuche-learning` unter
`de.regelsuche.evolution`.

- `EvolutionGenome` ist der kanonische Kandidatenumschlag.
- `EvolutionGenomeCodec` führt striktes JSON-Replay aus.
- `EvolutionGenomeValidator` erzeugt einen content-addressed Preflight mit
  benannten Blockern.
- `EvolutionGenomeCompiler` erzeugt ausführbare `RewriteRule`-Objekte.
- `DeterministicGenomeMutator` enumeriert begrenzte, reproduzierbare
  Mutationenvorschläge.
- Population-, Scheduler-, Checkpoint- und TRAIN-Diagnostikverträge binden die
  tatsächliche Ausführungssemantik.

Die wichtigsten JSON-Verträge sind im
[Schema-Katalog](schema-catalog.md) unter „Evolution und Flagship-Experiment“
verzeichnet.

## Identität und Provenienz

Ein Genome besitzt zwei unterschiedliche Identitäten:

- `contentHash` bindet den vollständigen Payload einschließlich TRAIN-Scope und
  Seed-Lineage;
- `alphaStructuralHash` abstrahiert von Platzhalternamen, Gene-IDs und
  Trainingsprovenienz, behält aber die ausführbare Struktur und Policy.

Abgeleitete Kandidaten referenzieren ihre Eltern. Mutationsbatches behalten
Proposal-Reihenfolge, Mutationstyp, Child-Hashes und sämtliche Preflight-
Blocker. Dadurch kann strukturelle Vielfalt gemessen werden, ohne die konkrete
Herkunft zu verlieren.

## Informationsgrenze

Genome-Formation ist auf `TRAIN` beschränkt. Der `TrainingScope` bindet Corpus-,
Familien-, Signatur- und Feature-Schema-Hashes. Das Genome besitzt keine Felder
für versteckte Referenzformen, VALIDATION-/FINAL-TEST-Ergebnisse oder post-hoc
Reviewlabels.

Diese Typgrenze ist notwendig, aber nicht hinreichend. Campaign- und
Evaluation-Runner müssen weiterhin belegen, dass ihre Adapter keine späteren
Splits in Formation, Mutation, Fitness oder Survivor-Auswahl einspeisen.

## Harte Preflight-Blocker

`EvolutionGenomeValidator` lehnt vor jeder Fitnessbewertung unter anderem ab:

- unparsebare Pattern und ungebundene Platzhalter;
- Identitätsregeln, strukturelle Duplikate und Rewrite-Zyklen;
- Verletzungen von AST-, Wachstum-, Anwendung- oder Programmlimits;
- deaktivierte Cycle-, Growth-, Applicability-, Duplicate- oder
  Determinismus-Guards;
- targetgerichtete Features in Open-Target-Genomen;
- widersprüchliche Fitnessrichtungen;
- fehlende Validation-, Counterexample-, Proof- oder Holdout-Obligationen;
- unbekannte Annahmentypen;
- Annahmenentfernung ohne Discharge-Certificate.

Diese Blocker können nicht durch einen höheren Fitnesswert kompensiert werden.

## Rohe Ausführung bleibt untrusted

```java
EvolutionGenomeCompiler.CompiledProgram program =
    new EvolutionGenomeCompiler().compile(genome);
```

Die daraus erzeugten `CompiledGenomeRule`s sind ausführbar, deklarieren aber
weiterhin:

```java
isEquivalencePreservingByConstruction() == false
```

Das ist eine bewusste Trust-Grenze. Suche und Experimente können rohe
Kandidaten testen, aber der sichere Regelkoordinator und das autoritative
Regelinventar dürfen sie nicht als bewiesene Regeln behandeln.

## Implementierter enger Promotionsadapter

`LearnedPatternRulePromoter` implementiert den ersten fail-closed Übergang zu
einer neuen, registration-eligible `PatternRewriteRule`.

Promotion v1 verlangt:

1. ein akzeptiertes Genome ohne Preflight-Blocker;
2. ein konkretes assumption-free `RewriteGene`;
3. einen exakten Identitätsnachweis durch
   `ExactPolynomialPatternIdentityVerifier`;
4. gebundene Identitäten für Semantic Validation, Counterexample Search,
   Holdout, Leakage-Audit und Repository-Revision;
5. ein content-addressed Promotion-Receipt;
6. eine neue Regel- und Applicability-Schema-Identität.

Der exakte Verifier unterstützt ausschließlich ein begrenztes kommutatives
Polynomfragment mit ganzzahligen Koeffizienten, Addition, Subtraktion,
Multiplikation und begrenzten nichtnegativen ganzzahligen Potenzen. Division,
Funktionen, bedingte Regeln und nicht exakte Koeffizienten bleiben
`UNSUPPORTED`.

Die referenzierten Validation-, Counterexample-, Holdout- und Leakage-Hashes
werden im v1-Promoter **gebunden, aber nicht von ihm geladen oder semantisch
verifiziert**. Diese Prüfung bleibt Aufgabe des übergeordneten Qualification-
und Release-Lifecycles.

Nach Promotion kann die Regel dieselbe Vorbereitungsinfrastruktur wie eine
handgeschriebene Pattern-Regel verwenden. Der Testfall zeigt eine promoted
Differenz-von-Quadraten-Regel, die zunächst eine gewöhnliche Kürzung verwendet:

```text
((x^2 * a) / a) - y^2
  -> x^2 - y^2            unter a != 0
  -> (x - y) * (x + y)
```

Die vollständige Grenze steht unter
[Promotion exakt bewiesener gelernter Pattern-Regeln](learned-pattern-rule-promotion.md).

## Was diese Promotion nicht bedeutet

Der implementierte Adapter ist ein Mechanismus, kein ausgeführter
Produktionsclaim:

- `PROMOTION` bleibt im Capability-Status `NOT_EVALUATED`;
- kein realer Flagship-VALIDATION- oder FINAL-TEST-Kandidat wurde dadurch
  ausgewählt;
- die gebundenen Evidence-Root-Hashes sind noch kein Ersatz für deren
  unabhängige semantische Prüfung;
- externe Neuheit und fachliche Interessantheit werden nicht abgeleitet;
- bedingte Regeln und komplette `RewriteProgram`s werden nicht promoviert.

Ein `RewriteProgram` kann `Choice`, `Sequence`, `Repeat`, `Require` und
Priorisierung enthalten. Es besitzt deshalb nicht notwendig ein einziges
linkes Pattern. Dafür ist ein programmbasierter Applicability-/Replay-Vertrag
mit vollständiger primitiver Lineage erforderlich.

## Populationen, Mutationen und Checkpoints

Die vorhandene evolutionäre Infrastruktur umfasst inzwischen:

- deterministische Populationen und begrenzte Mutationspläne;
- versionierte Proposal-Scheduler einschließlich stratifizierter Mutationstypen;
- TRAIN-only Fitness und Diagnostik;
- content-addressed retained Runs;
- execution-bound Checkpoints mit Resume-Prüfung;
- separate TRAIN-/VALIDATION-/FINAL-TEST-Verträge für das Flagship-Experiment.

Diese Infrastruktur belegt Reproduzierbarkeit und Policy-Bindung. Sie belegt
noch keine erfolgreiche held-out Selbstverbesserung.

## Prüfung aus dem Checkout

```bash
./gradlew :regelsuche-learning:test

./gradlew :regelsuche-learning:test \
  --tests de.regelsuche.evolution.LearnedPatternRulePromoterTest

./gradlew --no-configuration-cache ciCheck
```

## Noch offene Schritte

- reale, getrennte VALIDATION- und FINAL-TEST-Fälle erzeugen und versiegeln;
- den Flagship-TRAIN-Lauf unter eingefrorenem Vertrag ausführen;
- ausschließlich über VALIDATION auswählen;
- FINAL TEST genau einmal konsumieren;
- bedingte Regeln mit typisierten Annahmen und Discharge-Evidence promovieren;
- programmbasierte Applicability-/Replay-Schemata für gelernte
  `RewriteProgram`s entwickeln;
- Evidence-Root-Artefakte im Promotionsadapter oder im übergeordneten Gate
  tatsächlich laden und semantisch verifizieren;
- positive wie negative Ergebnisse vollständig retainen.

## Siehe auch

- [Flagship Freeze Execution](evolution-rewrite-program-flagship-freeze-execution.md)
- [Evolution Study Contracts](evolution-study-contracts.md)
- [Deterministische TRAIN-Populationen](evolution-population-engine.md)
- [Reale TRAIN-Suchfitness](evolution-train-fitness.md)
- [Rewrite-Program-Mutationen](evolution-rewrite-program-mutations.md)
- [Discovery- und Forschungsstand](discovery-status.md)
