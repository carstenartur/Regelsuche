# Target-blinde Ausführungsgrenze der Polynomtheorie-Nutzenstudie

## Status und Claim-Grenze

Auf `main` stehen das Nullprofil, der 30-Run-/600-Zeilen-Runner, die typisierte
Resultat- und Messoberfläche, die Candidate-Freeze-Verträge sowie die
vor Ausführung eingefrorene Roh-zu-kanonisch-Arbeitsprojektion zur Verfügung.

Der zugehörige native Adapter-Slice ergänzt das erste mathematisch aktive Profil:

```text
ON_DEMAND_VERIFIED_FACTORIZATION
regelsuche.polynomial-theory-utility.on-demand-verified-factorization/v1
```

Er führt alle 20 sichtbaren Formation-Fälle an allen sechs Checkpoints durch die
vorhandene target-blinde Run-Grenze. Das ist noch kein vollständiger
Studienlauf: Cache-, Quartikkontroll- und optionaler externer Adapter fehlen,
der 600-Zeilen-Candidate-Freeze wurde noch nicht erzeugt und die Qualifikation
bleibt versiegelt. Daraus folgt weder eine Produktentscheidung noch eine
Behauptung zusätzlicher mathematischer Reichweite oder historischer
Wiederentdeckung.

## Eingabe-, Run- und Ergebnisvertrag

`PolynomialTheoryUtilityProfileAdapter` erhält ausschließlich einen gebundenen
Run-Deskriptor, eine target-blinde Eingabe und den positionsgleichen sichtbaren
Formationsfall. Der native Adapter löst beim Öffnen eines Runs dessen vollständige
20 Eingaben aus dem content-adressierten Freeze auf. Jede Eingabe muss exakt an
der nächsten eingefrorenen Position stehen. Der Run kann nur nach allen 20 Fällen
geschlossen werden.

`PolynomialTheoryUtilityMeasuredExecution` bleibt der einzige Matrixexecutor.
Ein mathematisch aktiver Adapter liefert atomar:

- ein `PolynomialTheoryUtilityCandidateResult`,
- die dazugehörigen Übergangsspuren,
- alle ausgeführten Faktorisierungsversuche,
- alle Cacheereignisse des Profils.

Fehlende, doppelte, fremde oder nicht zum Resultat passende Messungen werden
abgewiesen. Ein Resultat mit Arbeit oder Übergängen darf nicht über die
Zero-Observation-Abkürzung eingeschleust werden.

Der Resultatvertrag bindet:

- den vollständigen wertgleichen Eingang aus der 600-Zeilen-Matrix,
- die unveränderte Quellwurzel aus dem Formation-Korpus,
- terminalen Status und Detailcode,
- den typisierten kanonischen Arbeitsvektor,
- occurrence-gebundene Übergänge in stabiler Pfadreihenfolge,
- das unabhängige Verifikationsergebnis.

`VALIDATED_TRANSITION` erfordert mindestens einen verifizierten Übergang. Alle
anderen terminalen Status dürfen keine Übergangsautorität behalten:
`NO_TRANSITION`, `UNSUPPORTED`, `BUDGET_INCONCLUSIVE` und
`TECHNICAL_FAILURE`.

## Exakter nativer Ausführungspfad

Der On-Demand-Adapter verwendet keine zweite Faktorisierungsimplementierung und
keinen versteckten Zielausdruck. Für jedes zugelassene Vorkommen lautet der
Pfad:

```text
parser-issued ExactParsedTerm
  -> frozen occurrence plan in numeric TreePosition order
  -> exact source-bound subterm projection
  -> ExactParsedUnivariatePolynomialView over Q[x]
  -> native rational univariate factorization engine
  -> common FactorizationVerifier with exact product reconstruction
  -> deterministic exact factor rendering
  -> exact reparse and polynomial reconstruction
  -> verifier-authorized TreePosition replacement
  -> structural replay and certificate
  -> typed transition, trace, attempt and work projection
```

Die native Engine erhält eine eigene, nicht rücksetzbare
`maxEngineWorkUnits`-Grenze aus dem Faktorisierungsanteil des konkreten
Vorkommens. Die äußere Pipeline behält weiterhin ihre Verifikations-, Rendering-,
Reparse-, Rekonstruktions- und Replayautorität. Ein externer Backendwechsel ist
in diesem Profil verboten.

Positive und negative Verifierberichte werden retained. Nicht unterstützte
multivariate Ausdrücke, rationale Funktionen und symbolische Exponenten werden
nicht durch SymPy oder einen anderen Solver ersetzt.

## Versionierte Zulassung vor Ausführung

Die Ausführung verwendet keine nach Betrachtung mathematischer Resultate gewählte
Magic Number. `PolynomialTheoryUtilityOnDemandAdmissionPolicy/v1` rekonstruiert
die Zulassungsgrenze ausschließlich aus bereits versiegelten sichtbaren Daten:

1. Es werden die 20 On-Demand-Zeilen am Checkpoint `CP06_FULL` gebunden.
2. Die zwei schon im sichtbaren `caseId` als `-tiny-budget` markierten
   Negativkontrollen werden ausgeschlossen.
3. Die übrigen Zeilen werden mit dem eingefrorenen Occurrence-Plan in ihre
   konkreten Vorkommensautoritäten zerlegt.
4. Das Minimum über 22 sichtbare Vorkommen wird eingefroren:

```text
minimumMechanicalAuthority    = 256
minimumFactorizationAuthority = 16
```

Die Policy bindet Formation-Hash, Execution-Plan-Hash, Ausschlussregel,
Vorkommensanzahl und beide Minima in einer eigenen SHA-256-Identität. Ändert sich
einer dieser Werte ohne Revisionswechsel, schlägt die Initialisierung fehl.

Zulassung erfolgt pro Zeile **all-or-none**. Sobald ein vorgesehenes Vorkommen
die Policy nicht erfüllt, endet die gesamte Zeile vor Parser- und
Faktorisierungsausführung als `BUDGET_INCONCLUSIVE`. Damit kann ein erfolgreicher
Teil einer wiederholten Struktur kein anderes ausgelassenes Vorkommen
überdecken. Die Policy-Identität ist Bestandteil jedes Resultatdetailcodes; bei
erfolgreichen Übergängen wird sie zusätzlich in der retaineden Ausführungsspur
gebunden.

Die Vorentscheidung verbraucht keine mathematische Transformationsarbeit: Sie
wertet nur bereits eingefrorene Eingabefelder und den content-adressierten
Occurrence-Plan aus. Deshalb bleibt ein vor Ausführung abgewiesenes Resultat im
kanonischen Arbeitsvektor bei null, ist aber über Policy-Hash und Detailcode
vollständig erklärbar.

## Vollständige Roharbeitsprojektion

Die produktive Pipeline führt einen nicht rücksetzbaren
`PolynomialWorkLedger`. `PolynomialTheoryUtilityRawWorkPartitioner` ordnet jede
Stage genau einer vorab eingefrorenen Studienkomponente zu. Die Segmente müssen
den vollständigen Ledger exakt rekonstruieren.

Explizit getrennt bleiben:

- Matching und Positionsprüfung,
- Quell- und Literalvalidierung,
- native Faktorisierung,
- unabhängige Verifikation,
- Rendering, Reparse und Rekonstruktion,
- occurrence-gebundene Ersetzung und Replay,
- Lookup, Insert, Eviction und Replay eines Caches,
- Konstruktion der Studien-Evidenz.

Unbekannte native Stages verschwinden nicht. Sie werden konservativ eins zu eins
als Faktorisierungsarbeit gezählt. Die versionierte kanonische Projektion hebt nur
die vorab dokumentierten Implementierungsmultiplikatoren mit Aufrundung auf. Der
projizierte Vektor muss innerhalb der primitiven, mechanischen und
Faktorisierungsautorität der konkreten Eingabe bleiben.

Für das On-Demand-Profil sind sämtliche Cache-Dimensionen null und jeder
Übergang trägt `CACHE_DISABLED`.

## Wiederholte Vorkommen

Der Formation-Korpus enthält Wurzel-, verschachtelte, zwei identische und vier
identische Vorkommen. Der Occurrence-Plan teilt primitive, mechanische und
Faktorisierungsautorität deterministisch auf und verteilt Restwerte in
kanonischer Pfadreihenfolge.

Jedes zugelassene Vorkommen wird unabhängig gegen die unveränderte Quellwurzel
ausgeführt. Das Resultat bewahrt Übergänge und Faktorisierungsversuche in
numerischer `TreePosition`-Reihenfolge. Für einen validierten wiederholten Fall
muss die Zahl der Übergänge exakt der Zahl der vorgesehenen Vorkommen
entsprechen.

## Matrixcharakterisierung

Die Profilprüfung führt aus:

```text
1 Profil × 6 Checkpoints × 20 Formation-Fälle = 120 Resultate
```

Geprüft werden unter anderem:

- genau sechs vollständige Run-Lebenszyklen mit je 20 Fällen,
- eindeutige Resultatidentitäten,
- Resultat-/Messungs-Rebinding,
- integer- und rationalkoeffiziente Faktorisierungen,
- negative beziehungsweise irreduzible Verifierberichte,
- unsupported und budget-inconclusive Ausgänge,
- verschachtelte und wiederholte Vorkommen in Pfadreihenfolge,
- vollständige Übergangs- und Versuchszahl bei Wiederholung,
- komponentenweise Budgeteinhaltung,
- null Cachearbeit und keine Cacheereignisse,
- keine technischen Terminalfehler in der eingefrorenen 120-Zeilen-Matrix.

Die Prüfung fordert bewusst mehrere terminale Kategorien; ein Adapter, der alle
Zeilen pauschal in denselben Status überführt, besteht sie nicht.

## Verifikation

```bash
./gradlew :regelsuche-experiments:test \
  --tests de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityOnDemandAdmissionPolicyTest \
  --tests de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityOnDemandOccurrencePlanTest \
  --tests de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityRawWorkPartitionerTest

./gradlew :app:test \
  --tests de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapterTest \
  --tests de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityOnDemandProfileMatrixTest
```

Die geschützten Repository-Autoritäten bleiben zusätzlich maßgeblich:
Checkout-lokales Gradle, vollständiger Maven-/Docker-Vertrag, isolierte
SymPy-Laufzeit und isoliertes JMH.

## Nächste Evidenzschritte

Vor Öffnung der Qualifikation müssen noch folgen:

1. `VERIFIED_DERIVED_MACRO_CACHE` mit exakter Cachelineage,
2. `SPECIALIZED_BINARY_QUARTIC_CONTROL`,
3. `OPTIONAL_EXTERNAL_VERIFIED_FACTORIZATION`,
4. eine vollständige Registry für alle fünf Profile,
5. target-blinde Ausführung und content-adressierter Freeze aller 600 Resultate
   samt Messbegleitern,
6. erst danach Qualifikationsöffnung, Vergleich, Kontrollen und Reproduktionen.

Eine Produktvoreinstellung darf erst aus der versionierten Abschlussentscheidung
der vollständigen Studie abgeleitet werden.
