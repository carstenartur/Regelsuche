# Generische Discovery-Domänen

Issue #224 trennt die wissenschaftlichen Rollen einer Discovery-Kampagne von der
bisher dominierenden Darstellung als Ausdrucks-String. Dieser erste
Architektur-Slice liegt in `:regelsuche-discovery` unter
`de.regelsuche.discovery.domain` und führt **keine** zweite Evidence-, Novelty-,
Proof- oder Promotion-Semantik ein.

## Vertrag

`DiscoveryDomain<State, Candidate, Certificate>` bindet für genau eine Domäne:

- einen State-Generator;
- einen kanonischen State-Codec mit stabiler Identität;
- explizite Invarianten;
- deterministisch geordnete Transformationsoperatoren;
- ein Objective, das Kandidatenbereitschaft und reine Suchmetrik trennt;
- einen Candidate-Extractor und einen kanonischen Candidate-Codec;
- eine eigenständige Counterexample-Suche;
- einen Evaluator;
- einen domänenspezifischen Certificate-Codec und Renderer;
- einen Adapter in die gemeinsame Evidence-Hülle.

Jede Rolle besitzt eine versionierte ID. Der kanonische
`regelsuche.discovery-domain-descriptor/v1`-Datensatz bindet alle IDs, die
State-, Candidate- und Certificate-Typen sowie die feste Rollenliste. Dadurch
kann eine Ausführung nicht stillschweigend Generator, Oracle oder Evidence-
Adapter austauschen.

## Gemeinsamer Runner

`DomainDiscoveryRunner` führt eine begrenzte, deterministische Best-First-Suche
aus. Die Reihenfolge ist vollständig durch Objective-Score, Tiefe und
State-Hash bestimmt. Operator- und Successor-Reihenfolgen werden vor der
Ausführung kanonisch sortiert.

Der Runner erzwingt folgende Grenzen:

- maximale Tiefe;
- maximal explorierte Zustände;
- maximal erzeugte Nachfolger;
- maximal betrachtete Nachfolger pro Zustand;
- maximal gebildete Kandidaten;
- ein globales Counterexample-Budget.

Duplikate und Invariant-Verletzungen bleiben als abgelehnte Transitionen mit
Blockern erhalten. Für jede Ressourcenart gilt:

```text
configured = executed + skipped + remaining
```

Ein fehlender Counterexample ist keine Bestätigung. Erst ein separater
Evaluator kann `CONFIRMED` liefern; dafür muss er ein domänenspezifisches
Certificate-Objekt bereitstellen. `proofStatus`, `externalNoveltyStatus`,
`promotionStatus` und `publicEvidenceStatus` bleiben in diesem Vertrag immer
`NOT_EVALUATED`.

## Algebraischer Adapter

`ExpressionRewriteDiscoveryDomain` verwendet unverändert:

- `ExpressionParser` und `ExpressionFormatter`;
- `AstRewriteTransformationEngine`;
- `ExpressionScorer`;
- `ExpressionCanonicalizer`.

Die Referenzcharakterisierung startet mit `x + 0`, übernimmt die bestehende
Regel `ast_add_zero_right` und erreicht `x`. Der Adapter bestätigt nur eine
kanonisch äquivalente, als semantikerhaltend markierte Rewrite-Spur. Das
Certificate trägt daher ausdrücklich die Stärke
`VALIDATION_EVIDENCE_NOT_FORMAL_PROOF`.

Dieser Slice ersetzt den App-Level-`ScientificDiscoveryWorkflow` noch nicht.
Er stellt den Adapter und byte-deterministische Charakterisierung bereit, auf
denen die schrittweise Portierung bestehender Kampagnen aufbauen kann.

## Zweite Domäne: endliche Differenzen

`FiniteDifferenceSequenceDomain` arbeitet nicht mit Ausdrucks-Rewrites. Ein
Seed enthält einen beobachteten Präfix und einen davon getrennten Holdout, zum
Beispiel:

```text
observed=1,4,9,16;holdout=25,36
```

Der Operator bildet nacheinander endliche Differenzen. Sobald eine konstante
Differenzzeile erreicht ist, entsteht ein Kandidat mit Ordnung und initialer
Differenzdiagonale. Die Counterexample-Suche rekonstruiert zuerst jeden
beobachteten Term in exakter `long`-Arithmetik. Der Evaluator darf erst danach
den zurückgehaltenen Suffix prüfen.

Für die Quadratzahlen entsteht die Diagonale `[1,3,2]` der Ordnung 2; sie
reproduziert `1,4,9,16` und sagt `25,36` voraus. Ein absichtlich falscher
Holdout `26` wird als `REFUTED` behalten und erhält kein Certificate. Das
`FINITE_DIFFERENCE_WITNESS` ist Validierungsevidenz für den endlichen Datensatz,
kein formaler Beweis einer unendlichen Folge und keine externe
Neuheitsbehauptung.

## Kanonische Artefakte

Die dedizierte CI schreibt für beide Domänen:

```text
regelsuche-discovery/build/reports/domain-discovery/
  expression/
    domain.json
    report.json
  sequence/
    domain.json
    report.json
```

Veröffentlichte Schemas:

- `docs/schemas/regelsuche-discovery-domain-descriptor-v1.schema.json`
- `docs/schemas/regelsuche-domain-discovery-evidence-v1.schema.json`

Die CI validiert beide Schemas, alle Hash-Verknüpfungen und Ressourcenbilanzen,
führt negative Schema-Checks aus und verlangt byte-identische wiederholte
Evidence-Ausgaben.

Lokale Reproduktion:

```bash
./gradlew :regelsuche-discovery:test \
  --tests 'de.regelsuche.discovery.domain.*'
```

## Verbleibender Umfang von #224

Dieser PR avanciert #224, schließt es aber nicht. Noch erforderlich sind:

1. die schrittweise Portierung der produktiven algebraischen Generation-,
   Mining-, Validation-, Counterexample-, Proof- und Lifecycle-Komposition;
2. domänenneutrale Candidate-/Lifecycle-Handoffs in den heute noch
   ausdrucksspezifischen App- und Persistenzklassen;
3. dieselben Ablation-, Telemetrie- und Release-Evidence-Grenzen für beide
   Domänen;
4. ein eigenständiges Evidence Profile für den stärkeren Claim
   „domain-generic mathematical discovery“;
5. eine reproduzierbare Mehrdomänen-Kampagne, ohne daraus externe
   mathematische Neuheit abzuleiten.

Plugin-Distribution, öffentliche Kataloge und Signaturprüfung bleiben #104.
