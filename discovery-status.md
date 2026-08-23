# Discovery- und Forschungsstand

**Stand: 23. August 2026**

Diese Seite fasst den gegenwärtigen Forschungsstand zusammen. Sie trennt
implementierte Softwarefähigkeiten, reproduzierte Projektergebnisse,
vergleichende Benchmarks und noch nicht ausgeführte Experimente.

## Zusammenfassung

Regelsuche besitzt eine umfangreiche, checkout-lokal verifizierte Infrastruktur
für symbolische Suche, targetfreie Candidate Formation, Holdout-Prüfung,
Counterexample Search, Proof-Obligationen, reproduzierbare Evidence und mehrere
mathematische Objekttypen.

Seit dem Stand vom 21. August sind zusätzliche Kernfähigkeiten implementiert:

- vollständiger begrenzter Reachability-Oracle;
- strukturierte Partial-Match-Analyse und bounded lokale Pattern-Bridges;
- explizite Applicability-Schemata mit typisierten Guards;
- content-addressed Registry der nativen Exact-Vorbereitungsspezialisten;
- Unified Coordinator für direkte, native exakte und lokale Vorbereitung;
- exakte Darstellung affiner Gleichungssysteme als `A*x=b`, Blockzerlegung,
  RREF und Lösungsklassifikation;
- symbolische Eigenproblem-Erkennung bei expliziten Rollen;
- ein enger Promotionsmechanismus für exakt bewiesene assumption-free
  gelernte Polynom-Pattern;
- generationengetrenntes, proof-gated Regelmining mit eingefrorenen
  Schatteninventaren und nachgewiesener kumulativer Wiederverwendung;
- eine allgemeine exakte Polynomzerlegungssynthese für einen begrenzten
  Quartikbereich auf Basis semantischer AST-Atome.

Diese Implementierungen erweitern Mechanismus, mathematische Reichweite und
diagnostische Evidenz. Sie qualifizieren weder automatisch das
Produktdefaultprofil noch den öffentlichen Claim `PROMOTION`. Die
Generationskampagne verändert ausschließlich experimentelle Schatteninventare;
der Syntheseoperator ist keine vollständige Polynomfaktorisierung.

Das stärkere Flagship-Ziel — ein aus primitiven Operationen erlerntes,
interpretierbares `RewriteProgram`, das auf einem genau einmal verwendeten
FINAL TEST die Suche verbessert — ist **noch nicht ausgeführt**. Die
reversiblen technischen Vorarbeiten sind weit fortgeschritten; reales privates
VALIDATION-/FINAL-TEST-Material, das endgültige `FROZEN_NOT_RUN`-Receipt und die
anschließenden TRAIN-/VALIDATION-/FINAL-TEST-Ergebnisse fehlen noch.

## Status auf einen Blick

| Bereich | Stand | Aussagegrenze |
| --- | --- | --- |
| Produkt und Build | Web-Workbench, CLI, Docker, Full Mode, Proof-Image und checkout-eigener `ciCheck` sind vorhanden | Kein Produktions- oder Sicherheitszertifikat |
| Affine Gleichungssysteme | Exaktes `A*x=b`, Blöcke, RREF, Lösungen und Inkonsistenzwitnesses sind implementiert | Kein allgemeiner nichtlinearer oder operatoralgebraischer Solver |
| Symbolische Eigenprobleme | Explizite Rollen können ein Eigenproblem und charakteristisches Polynom freischalten | Namen allein erzeugen keine Physik- oder Quanteninterpretation |
| Regelvorbereitung | Direkter Replay, native Exact-Registry, lokale Pattern-Bridges, Guards und Zertifikate sind implementiert | Noch kein allgemeines Workbench-/CLI-Defaultprofil und keine globale Vollständigkeit |
| Enge Lernregel-Promotion | Assumption-free Polynom-Pattern können nach exaktem Identitätsnachweis eine neue Regelidentität erhalten | Evidence-Roots werden in v1 nur gebunden; `PROMOTION` bleibt `NOT_EVALUATED` |
| Generationenbasiertes Regelmining | Drei eingefrorene Generationen, proof-gated Akzeptanz, Inventar-Hashkette und kumulativer Reuse-Audit sind implementiert | Nur experimentelle Schatteninventare; keine same-generation Nutzung und keine Produktionspromotion |
| Polynomzerlegungssynthese | Semantische AST-Atome und exakte Koeffizientenbedingungen erzeugen zertifizierte quadratisch-mal-quadratische Quartikzerlegungen | Begrenzte binäre homogene und homogenisierte univariate Quartiken; keine vollständige multivariate Faktorisierung |
| Autonome Referenz-Campaign | Für den eng definierten internen Claim qualifiziert und reproduzierbar gebunden | Keine externe mathematische Neuheit |
| Mehrdomänen-Discovery | Expression Rewrite und endliche Differenzen sind getrennt qualifiziert | Kein universeller domänenunabhängiger Discovery-Nachweis |
| Targetfreie Simplification | Acht getrennte Konfigurationen; primär Regelsuche 6/7 gegenüber SymPy `simplify` 7/7 | Negatives Portfolio-Ergebnis; keine allgemeine Rangfolge oder nachträgliche Best-of-Auswahl |
| SymPy-Regelamplifikation | Vier zusätzliche lokal vorbereitete Anwendungen über drei Regelfamilien | Begrenzter Applicability-Nachweis, kein allgemeiner Performancevergleich |
| Flagship-Präregistrierung | Work Accounting, Corpus-/Reveal-/Split-/Freeze-Werkzeuge und Baseline-Verträge sind implementiert | Reales Experiment noch nicht freigegeben |
| TRAIN | Kein Flagship-Populationsresultat | Keine Aussage über erlernte Verbesserung |
| VALIDATION | Nicht geöffnet und nicht zur Auswahl verwendet | Keine ausgewählte Flagship-Konfiguration |
| FINAL TEST | Nicht reserviert oder konsumiert | Kein Flagship-Erfolg und kein Nullresultat |
| Externe Neuheit | `BLOCKED` | Keine weltweite Neuheitsbehauptung |
| Formaler Beweis des retained Produktionskandidaten | `NOT_EVALUATED` | Symbolische Validierung ist kein formaler Beweis |
| Promotion / Public Evidence | `NOT_EVALUATED` | Kein autoritativer Produktionsregelimport oder externer Evidenzclaim |

<!-- capability-status:start -->
## Maschinengebundener Capability-Status

Die folgende Kurzmatrix wird aus den kanonischen Release-, Domain- und Trust-Verträgen erzeugt. Die vollständige Matrix mit Evidence-Roots steht in [`capability-status.md`](generated/capability-status.md).

| Capability | Status |
|---|---|
| `AUTONOMOUS_CAMPAIGN` | `QUALIFIED` |
| `DOMAIN_GENERIC_DISCOVERY` | `QUALIFIED` |
| `EXTERNAL_NOVELTY_REVIEW` | `BLOCKED` |
| `FORMAL_PROOF_OF_RETAINED_CANDIDATE` | `NOT_EVALUATED` |
| `PLUGIN_ARTIFACT_TRUST` | `IMPLEMENTED` |
| `PLUGIN_INDEX_AUTHENTICATION` | `IMPLEMENTED` |
| `PLUGIN_TRUST_STATE_REVISIONS` | `IMPLEMENTED` |
| `PROMOTION` | `NOT_EVALUATED` |
| `PUBLIC_EVIDENCE` | `NOT_EVALUATED` |
| `PUBLIC_PLUGIN_DISTRIBUTION` | `BLOCKED` |

`QUALIFIED` autorisiert nur den jeweils benannten Claim. Externe mathematische Neuheit, formaler Beweis, Promotion und Public Evidence werden nicht aus einem anderen erfolgreichen Profil abgeleitet.
<!-- capability-status:end -->

## Bereits belegte Projektergebnisse

### Zielgerichtete Suchsteuerung

Eine dokumentierte TEST-Auswertung reduzierte die Zahl erkundeter Zustände von
sieben auf fünf, ohne den Zielpfad zu verändern. Dies belegt eine begrenzte,
erklärbare Suchsteuerung für die eingefrorene Suite, nicht mathematische
Discovery.

### Hidden-Rule Rediscovery

In der retained Referenz wurden 19 von 20 entfernten bekannten Regeln über vier
Familien wiederaufgebaut. 38 negative Holdouts erzeugten keine False Positives;
zwei Prüfungen blieben explizit übersprungen. Die bekannten Referenzregeln
wurden erst post hoc zur Klassifikation verwendet. Das ist Rediscovery, keine
externe Neuheit.

### Open-Target Candidate Formation

Aus mehreren alpha-distinkten, targetfreien Suchbeobachtungen wurde eine
parametrisierte Hypothese gebildet, kompiliert und durch getrennte Stufen
geführt. Dies zeigt Candidate Formation ohne vorgegebenen Zielausdruck, nicht
allgemeine Wahrheit oder fachliche Bedeutung.

### Autonome Referenz-Campaign

Die qualifizierte Campaign bindet Research Brief, Seed-Familien, zwölf
Observations, Aggregate Mining, Kandidaten-Lineage, Validation,
Counterexample Search, Projekt-Novelty, Proof-Obligation, Lifecycle-Handoff,
Ressourcenbilanz und mehrere reproduzierte Läufe.

Der retained Kandidat lautet:

```text
(A + 2) * x + A * x → (2 * A + 2) * x
```

Dieser Kandidat ist gegenüber dem damaligen Projektinventar neu und innerhalb
der gebundenen Suite qualifiziert. Er ist weder als extern neu noch als
mathematisch bedeutend bewertet.

### Mehrdomänen-Discovery

Expression Rewrite und eine Zahlenfolgen-Domäne durchlaufen dieselben
domänenneutralen Generation-, Such-, Validierungs- und Evidence-Grenzen. Das
separate Profil `DOMAIN_GENERIC_DISCOVERY` ist qualifiziert, erweitert aber den
algebraischen Autonomie-Claim nicht automatisch.

## Neue mathematische Infrastruktur

### Exakte Gleichungssystem-Repräsentation

Affine skalare Gleichungen werden als ein mathematisches System statt als lose
Einzelgleichungen behandelt:

```text
skalare Gleichungen
  -> exaktes A*x=b
  -> unabhängige Zeilen-/Variablenblöcke
  -> exakte Gauss-Jordan-RREF
  -> eindeutige Lösung / parametrisierter Raum / Widerspruch
```

Variablenordnung, rationale Koeffizienten, Zeilenprovenienz, Rang,
Round-trip-Prüfung und konkrete Zeilenoperationen bleiben retained. Ein
matched-work Vergleich gegen unabhängig implementierte direkte skalare
Elimination behält identische Konsequenzen und getrennte Arbeitsbilanzen.

### Symbolische Eigenproblem-Erkennung

Bei ausdrücklich deklarierten Vektorkoordinaten und Parametern kann ein System
als

```text
A*v = lambda*v
```

erkannt und bis zu `(A-lambda*I)*v=0` sowie dem exakten charakteristischen
Polynom weitergeführt werden. Eine quantenmechanische Interpretation wird nur
bei explizitem Modellkontext zugelassen.

### Regelgerichtete Vorbereitung

Die aktuelle Vorbereitungspipeline lautet:

```text
konkreter direkter Executor
  -> typisierte Guards
  -> nativer Exact-Spezialist
  -> bounded pattern-targeted local bridge
  -> konkreter Principal-Replay
  -> unabhängige Verifikation
```

Die Exact-Registry bindet die vorhandenen Spezialsolver an ihre native
Principal-ID. Fremde ähnliche Regeln erhalten nicht stillschweigend denselben
Solververtrag. Technische Exceptions erzeugen sichtbare Fehlerstatus.

Der Unified Coordinator ist implementiert, aber noch nicht als allgemeiner
Produktdefault ausgewählt. Eine gemeinsame Multi-Principal-Frontier, geteilte
AST-/Value-Traversierung und direkte Integration typisierter
Repräsentationsbrücken bleiben offen.

### Enge Promotion gelernter Pattern-Regeln

Ein assumption-free gelerntes Pattern kann im begrenzten kommutativen
Polynomfragment exakt normalisiert und bewiesen werden. Nach Genome-Preflight
und Bindung von Evidence-Root-Identitäten entsteht eine neue
`PatternRewriteRule`, ein Applicability-Schema und ein Promotion-Receipt.

Der charakterisierte Kandidat ist die Differenz-von-Quadraten-Identität. Nach
Promotion kann sie die allgemeine lokale Kürzungsvorbereitung wiederverwenden.
Rohe `CompiledGenomeRule`s bleiben untrusted.

Die Validation-, Counterexample-, Holdout- und Leakage-Artefakte werden im
v1-Promoter noch nicht geladen oder semantisch verifiziert; nur ihre Identitäten
werden gebunden. Daher ist dies ein implementierter Mechanismus, keine
qualifizierte Produktionspromotion.

### Generationenbasiertes Regelmining

Die Campaign führt Seed-, erste und zweite Lernregelgeneration strikt getrennt
aus. Jede Generation erhält ein eingefrorenes Inventar und darf die während
ihres eigenen Laufs gebildeten Kandidaten noch nicht verwenden. Erst nach
Validation, Counterexample Search, positiven und negativen Holdouts,
Leakage-Prüfung, exakter Identitätsverifikation und ausführbarer Kompilation
entsteht die nächste content-addressed Schatteninventar-Revision.

Ein strenger kumulativer Audit vergleicht `0+1` mit `0+1+2` unter demselben
maximalen Suchdepth. Der positive Kontrollfall muss dabei im retained Pfad eine
Regel aus Generation 2 tatsächlich verwenden. Rejected candidates, terminale
Gründe, Generationsbarrieren und Inventar-Hashkette bleiben deterministisch
reproduzierbar.

Das belegt einen begrenzten Mechanismus zur kumulativen Wiederverwendung
proof-gated experimenteller Regeln. Es belegt weder Produktionstauglichkeit,
autonome externe Neuheit noch den öffentlichen Claim `PROMOTION`. Vollständige
Abgrenzung und Reproduktion:
[Generational Rule Mining](generational-rule-mining.md).

### Semantische Polynomzerlegungssynthese

`PolynomialSemanticView` interpretiert vollständige AST-Teilbäume wie `x + 1`
oder `sin(t)` als strukturelle Polynom-Atome. Der
`PolynomialDecompositionSynthesisOperator` löst anschließend exakt die
Koeffizientenbedingungen der begrenzten Form

```text
(a*A^2 + b*A*B + c*B^2) * (d*A^2 + e*A*B + f*B^2)
```

für ganzzahlige binäre homogene Quartiken. Begrenzte univariate Quartiken werden
über eine explizite strukturelle Einheit homogenisiert. Ergebnis,
Koeffizientenbelegung, Domain, Budget und Rekonstruktionsprüfung werden in einem
content-addressed Zertifikat gebunden.

Dasselbe Verfahren deckt den historischen Sophie-Germain-Fall, andere
Quartikfamilien und unterstützte AST-Substitutionen ab. Der frühere benannte
Spezial-Bridge bleibt nur als deaktivierter historischer Kontrollpfad erhalten.
Die aktuelle Domäne ist keine vollständige multivariate Faktorisierung.
Vollständige Abgrenzung und Reproduktion:
[Polynomial Decomposition Synthesis](polynomial-decomposition-synthesis.md).

## Aktuelle vergleichende Benchmarks

### Targetfreie Simplification

Der Track `target-free-simplification-operation-portfolio` führt acht getrennte,
targetfreie Konfigurationen aus: Regelsuche mit untargeted Best-First, einen
deterministischen randomized-valid Kontrolllauf sowie die sechs einzeln
gebundenen SymPy-Operationen `simplify`, `factor`, `cancel`, `together`, `apart`
und `trigsimp`. Keine Konfiguration erhält die gepinnte Referenzform als Suchziel
oder verborgene Auswahlhilfe.

| Fall | Familie | Regelsuche | SymPy `simplify` |
| --- | --- | --- | --- |
| `(x + 0) * 1 → x` | Identität | erreicht | erreicht |
| `x * 0 + y → y` | Annihilator | erreicht | erreicht |
| `(a + b) * (a + b) → (a + b)^2` | Potenzfaltung | erreicht | erreicht |
| `x + x → 2 * x` | Linearkombination | erreicht | erreicht |
| `(2 * x + 4) / 2 → x + 2` | rationale Reduktion | erreicht | erreicht |
| `(x^2 - 1) / (x - 1) → x + 1` | rationale Kürzung | erreicht | erreicht |
| `(x^3 - 1) / (x - 1) → x^2 + x + 1` | Polynomdivision | **nicht erreicht** | erreicht |

Regelsuche erreicht im Primärvergleich sechs von sieben Referenzformen, SymPy
`simplify` sieben von sieben. Das vollständige Portfolio bleibt mit Status
`NEGATIVE` retained. Exakte Polynomdivision ist als deaktiviertes Pack
verfügbar; sie wird nicht nachträglich in das gemessene Default-Inventar
hineindefiniert.

### Drei-Familien-Regelamplifikation

Ein getrenntes Experiment misst direkte gegenüber lokal vorbereiteten
Anwendungen unveränderter importierter Regeln für Trigonometrie, Polynome und
rationale Teleskopierung. Unter einem gemeinsamen eingefrorenen
Kürzungsinventar entstehen vier zusätzliche vorbereitete Anwendungen; drei
Near-Misses bleiben konklusiv negativ.

Dies belegt begrenzte Applicability-Amplifikation, nicht allgemeine
Überlegenheit oder bessere Laufzeit gegenüber SymPy.

Vollständige Verträge und Reproduktion:
[Comparative Discovery Benchmarks](discovery-benchmarks.md) und
[Sicherer Regelvorbereitungskoordinator](safe-rule-preparation-coordinator.md).

## Flagship-Experiment: proof-carrying self-improvement

Das primäre nächste Forschungsziel ist in
[Issue #521](https://github.com/carstenartur/Regelsuche/issues/521) beschrieben:
Aus primitiven, ausführbaren Operationen und ausschließlich TRAIN-basierter
Evidence soll ein echtes Strategieprogramm mit Sequenzen, Entscheidungen,
Guards, Prioritäten und explizitem Pruning gelernt werden.

Ein positives Ergebnis darf nur folgende begrenzte Aussage stützen:

> Unter der eingefrorenen Grammatik, dem Regelinventar, dem Corpus, der
> Informationsgrenze und dem Ressourcenbudget hat Regelsuche ein ausführbares
> Rewrite-Programm synthetisiert, das die held-out symbolische Suche ohne
> Korrektheitsregression verbessert.

### Implementierte reversible Voraussetzungen

- getrennte primitive, programminterne, äußere Such- und Exact-Audit-
  Arbeitszähler;
- öffentliche numerische Erfolgs- und Nullresultat-Schwellen;
- eingefrorene Baseline- und Ablationsidentitäten;
- Performance-Messplan mit semantischer Paritätsanforderung;
- konkreter öffentlicher TRAIN-Corpus mit rationalen und polynomialen Fällen;
- privater Reveal-Vertrag mit split-spezifischer Autorisierung;
- strikter Loader sowie lokale exakte Prüfung vor dem Versiegeln;
- öffentliche Hash-Commitments ohne private Ausdrücke;
- Split-Manifest mit Case-, Family-, Exact-, Alpha-, Input- und Target-
  Kollisionskontrollen;
- öffentlicher Freeze-Assembler mit `FROZEN_NOT_RUN`-Semantik;
- Regel-Tiers, Profile, content-addressed Regelinventar und Ablationsfähigkeit;
- enger exakter Pattern-Promotionsadapter als getrennte Mechanik;
- generationengetrennte Schatteninventare als vorab charakterisierter
  Mechanismus, jedoch nicht als Ersatz für das reale Flagship-Protokoll.

### Noch fehlende irreversible Schritte

1. reale VALIDATION- und FINAL-TEST-Fälle außerhalb des Repositorys erstellen;
2. diese Fälle mit dem vertrauenswürdigen lokalen Werkzeug exakt prüfen und
   versiegeln;
3. Commitments, Split-Manifest und sämtliche Experimentverträge in einem realen
   `FROZEN_NOT_RUN`-Receipt binden;
4. unabhängig bestätigen, dass zu diesem Zeitpunkt keine TRAIN-, VALIDATION-
   oder FINAL-TEST-Ergebnisse existieren;
5. die TRAIN-Population unter dem eingefrorenen Vertrag ausführen;
6. ausschließlich mit VALIDATION eine Konfiguration auswählen und einfrieren;
7. den FINAL TEST genau einmal reservieren und konsumieren;
8. alle Baselines, Ablationen, Fehler und Nullresultate vollständig retainen.

Bis Schritt 4 abgeschlossen ist, darf die Flagship-TRAIN-Ausführung nicht
beginnen. [Issue #533](https://github.com/carstenartur/Regelsuche/issues/533)
bleibt daher sachlich offen.

## Claim-Stufen richtig lesen

Regelsuche unterscheidet mindestens:

1. **Search Improvement:** Ein bekanntes Ziel wird unter einer Suite effizienter
   erreicht.
2. **Hidden-Rule Rediscovery:** Eine bekannte, vor dem Lauf entfernte Regel wird
   ohne Referenz-Leakage wiederaufgebaut.
3. **Open-Target Candidate Formation:** Ohne Zielausdruck entsteht eine
   projektintern neue Hypothese.
4. **Autonomous Campaign Qualification:** Ein autonom erzeugter Kandidat besteht
   die eingefrorene interne Qualifikation und Reproduktion.
5. **Bounded Semantic Synthesis:** Ein allgemeiner, exakt begrenzter
   Theorieoperator konstruiert eine zertifizierte Darstellung oder Zerlegung;
   dies ist weder Vollständigkeit noch externe Neuheit.
6. **Proof-gated Generational Reuse:** Exakt qualifizierte Regeln einer
   abgeschlossenen Generation verbessern einen späteren Schatteninventar-Lauf;
   dies ist noch keine Produktionspromotion.
7. **Mechanische Pattern-Promotion:** Eine vorliegende assumption-free
   Polynomidentität erhält nach exaktem Proof eine neue Regelidentität; dies ist
   noch kein ausgeführter öffentlicher Promotionsclaim.
8. **Flagship Held-out Improvement:** Ein erlerntes Strategieprogramm verbessert
   einen genau einmal verwendeten FINAL TEST.
9. **Externally Novel Mathematics:** Literatur-, Datenbank- und unabhängige
   fachliche Prüfung stützen eine externe Neuheitsentscheidung.

Keine Stufe impliziert automatisch die nächste. Wahrheit, Projekt-Neuheit,
externe Neuheit, Interessantheit, Suchnutzen, formaler Beweis, Promotion und
Public Evidence bleiben getrennte Achsen.

## Reproduktion des vorhandenen Stands

Autoritativer Repository-Lebenszyklus:

```bash
./gradlew --no-configuration-cache ciCheck
```

Fokussierte Vorbereitung und Promotion:

```bash
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.transform.SafePreparationEngineRegistryTest

./gradlew :regelsuche-search:test \
  --tests de.regelsuche.search.reachability.UnifiedRulePreparationCoordinatorTest

./gradlew :regelsuche-learning:test \
  --tests de.regelsuche.evolution.LearnedPatternRulePromoterTest
```

Generationenbasiertes Regelmining und Polynomzerlegungssynthese:

```bash
./gradlew :app:test \
  --tests de.regelsuche.docs.GenerationalRuleMiningCampaignTest

./gradlew :app:test \
  --tests de.regelsuche.docs.PolynomialDecompositionDiscoveryIntegrationTest
```

Qualifizierte autonome Referenz-Campaign:

```bash
./gradlew :regelsuche-release:runQualifiedReleaseReadinessWithHiddenRuleEvidence
```

Vergleichende Benchmarks:

```bash
bash scripts/run-comparative-benchmarks-verification.sh
```

Unabhängiges Reproduktionspaket und Claim-Grenzen:
[Independent Reproduction](independent-reproduction.md).

## Nächste Prioritäten

1. Unified Preparation unter matched work gegen `DIRECT_V1` qualifizieren und
   über das Produktdefault entscheiden;
2. gemeinsame Multi-Principal-Traversierung und typisierte
   Repräsentationsbrücken integrieren;
3. die semantische Synthese auf weitere ausdrücklich preregistrierte
   Darstellungsfamilien ausweiten, ohne den aktuellen Quartik-Claim umzudeuten;
4. reale Evidence-Root-Artefakte im Promotions-/Qualification-Lifecycle laden
   und semantisch prüfen;
5. reales Flagship-`FROZEN_NOT_RUN`-Receipt erzeugen und #533 abschließen;
6. TRAIN-Populationen, VALIDATION-Auswahl und genau einmaligen FINAL TEST für
   #521 ausführen;
7. information-paritäre Baselines und Ablationen aus #235 vervollständigen;
8. Performance-Optimierungen nur bei Evidence- und Work-Accounting-Parität
   aktivieren;
9. externe Interestingness- und Novelty-Prüfungen als getrennte reale Studien
   durchführen.

## Verbindliche Grenzen

- Oracles und Prover validieren oder widerlegen; sie erzeugen nicht den zu
  bewertenden Kandidaten.
- Target-, Referenz-, Familien-, VALIDATION-, FINAL-TEST- und Review-
  Informationen dürfen nicht in eine frühere Formation einfließen.
- Eine Generation darf ihre eigenen neu gebildeten Regeln nicht während
  desselben Laufs aktivieren.
- Semantische Repräsentationserkennung ist kein Beweis; jeder autorisierende
  Synthese- oder Brückenschritt besitzt eine eigene Verifikation.
- Konfigurierte, ausgeführte, übersprungene und verbleibende Arbeit wird
  vollständig bilanziert.
- Fehlende Evidence führt zu `BLOCKED` oder `NOT_EVALUATED`, niemals zu einem
  impliziten Erfolg.
- Laufzeitmessungen ersetzen keine kanonische mathematische Arbeitsbilanz.
- Ein negatives oder null Ergebnis bleibt ein veröffentlichbares Ergebnis.
- Externe mathematische Neuheit benötigt eine eigenständige externe Prüfung.
