# Exakte Fourier-Pläne für periodische Teilbarkeitssignale

Die Discovery-Domäne `periodic-divisibility-signals/v1` wählt auf Trainingsdaten
zwischen zwei bekannten Auswertungsplänen und prüft den eingefrorenen Plan auf
getrennten Aufgaben. Sie verwendet den vorhandenen `DomainDiscoveryRunner` über
das Java Discovery SDK. Es gibt keinen zusätzlichen Suchkern.

## Ausführen

Java 25 und der Repository-Wrapper genügen:

```sh
./gradlew :regelsuche-discovery-sdk:periodicSignalExample
./gradlew :regelsuche-discovery-sdk:periodicSignalExample --args="build/periodic-evidence"
```

Das Beispiel führt einen positiven Transfer mit vielen gleichen Perioden und
eine negative Kontrolle mit einem einzelnen Term aus. Der optionale Pfad ist
relativ zum SDK-Modul; dort entstehen zwei kanonische JSON-Nachweise. Die Ausgabe
zeigt Aufbau, Auswertung, Auswahl, Vergleich und unabhängige Prüfung getrennt.

Mit dem eingebauten Datensatz ergibt das Arbeitsmodell:

| Holdout | Fester direkter Plan | Fester zusammenfassender Plan | Auf TRAIN ausgewählter Plan |
| --- | ---: | ---: | ---: |
| Wiederholte Perioden | 244 | 66 | 66 |
| Negative Transferkontrolle | 6 | 10 | 10 |

Die Zahlen enthalten den jeweiligen Aufbau und die Auswertung. Pro Experiment
kommen 284 Einheiten Trainingsauswahl und 56 Einheiten erneute Auswertung für
das Trainingsaudit hinzu; die unabhängigen Prüfungen kosten 1.938 Einheiten auf
TRAIN sowie 5.234 beziehungsweise 338 auf HOLDOUT. Der Vergleich führt beide
festen Holdout-Pläne aus (310 beziehungsweise 16 Einheiten insgesamt); die
ausgewählte Auswertung ist darin bereits enthalten. Diese zusätzlichen Kosten
werden nicht als Einsparung verbucht.

## Mathematischer Vertrag

Für eine positive Länge `L`, bekannte Teiler `d` von `L` und rationale Gewichte:

\[
s(k)=\sum_d w_d[d\mid k],\qquad
\widehat{s}(j)=\frac1L\sum_{k=0}^{L-1}s(k)e^{-2\pi i jk/L}
             =\sum_d\frac{w_d}{d}[(L/d)\mid j].
\]

Die letzte Gleichheit folgt aus der endlichen geometrischen Summe. `d` ist eine
bekannte Periode und muss keine Primzahl sein. Die Frequenzposition ist ein
Vielfaches von `L/d`, nicht automatisch die Primzahl `d`. Gleiche Perioden dürfen
doppelt vorkommen; negative und verschwindende Gewichte sind erlaubt. Auch der
Nullpunkt `k=0` gehört zu jedem Teilbarkeitssignal.

`DIRECT` skaliert jeden Term einmal und prüft die angefragten Frequenzen.
`MERGE_PERIODS` addiert vorher Gewichte gleicher Perioden und entfernt Nullen.
Beide Pläne verwenden dieselbe bekannte Fourier-Identität. Gelernt wird hier
ausschließlich die Auswahl eines dieser beiden Pläne für spätere Aufgaben.
Die Domäne entdeckt keine neue Fourier-Regel und faktorisiert keine unbekannte
Zahl. Ein schnellerer allgemeiner FFT- oder Faktorisierungsalgorithmus wird
daraus nicht abgeleitet.

## Java-Aufruf

```java
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.signal.FourierQuery;
import de.regelsuche.discovery.signal.PeriodicSignal;
import de.regelsuche.discovery.signal.PeriodicSignal.Term;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.discovery.RegelsucheDiscovery;
import de.regelsuche.sdk.discovery.signal.PeriodicSignalDomain;
import de.regelsuche.sdk.discovery.signal.PeriodicSignalStudy;
import java.util.List;

var train = new FourierQuery(new PeriodicSignal(12, List.of(
    new Term(3, ExactRational.integer(2)),
    new Term(3, ExactRational.integer(5)))), List.of(0, 4, 8));
var holdout = new FourierQuery(new PeriodicSignal(20, List.of(
    new Term(5, ExactRational.integer(3)),
    new Term(5, ExactRational.integer(7)))), List.of(0, 4, 8, 12, 16));

var run = RegelsucheDiscovery.forDomain(PeriodicSignalDomain.typedDomain())
    .campaign("periodic-study")
    .seed("separated-queries", new PeriodicSignalStudy(List.of(train), List.of(holdout)),
          "my-experiment/v1")
    .budget(new DiscoveryBudget(1, 4, 4, 2, 2, 128))
    .run();
var evidence = run.evidence();
var certificate = run.selectedCertificate(); // present only on confirmation
```

Die wiederverwendbare Mathematik liegt in `de.regelsuche.discovery.signal`, der
typisierte Domain-Adapter in `de.regelsuche.sdk.discovery.signal`. Für eine
einzelne Auswertung ist `PeriodicFourier.evaluate(query, plan)` ausreichend.
Ein gewählter Plan kann auf weitere gültige `FourierQuery`-Objekte angewendet
werden. Jede Auswertung baut ihre vorbereiteten Terme neu auf; ein versteckter
Cache wird im Kostenvergleich nicht vorausgesetzt.

## Auswahl, Prüfung und Grenzen

Die Eingabe enthält explizite `training`- und `holdout`-Listen. Exakt identische
Aufgaben innerhalb oder zwischen den Listen sind unzulässig. Das ist ein Schutz
gegen direkte Duplikate, keine automatische Garantie statistischer Unabhängigkeit.
Beide Pläne werden ausschließlich auf TRAIN bewertet. Weniger gezählte Einheiten
ergeben höhere Priorität; bei Gleichstand gewinnt `DIRECT`. HOLDOUT verändert
diese Auswahl nicht. Es ist eine begrenzte Auswahl aus zwei Plänen, keine Suche
über beliebige Programme und keine Heuristik, die jede neue Aufgabe neu klassifiziert.
Das Budget muss beide Nachfolger zulassen (`maxCandidatesPerState >= 2`,
`maxGeneratedSuccessors >= 2`, `maxDepth >= 1`), damit beide Pläne in die
Prioritätsauswahl gelangen. Ein engeres Budget kann bereits vorher beschneiden;
eine Bestätigung belegt dann weiterhin die Korrektheit des erreichten Plans,
nicht seine Kostenoptimalität. Die Evidence bewahrt die Suchressourcen.

Der unabhängige Prüfer baut das Signal im Zeitbereich auf und stellt den DFT-Wert
als Polynom in einer primitiven `L`-ten Einheitswurzel dar. Er zieht den behaupteten
rationalen Koeffizienten ab und berechnet den Rest modulo dem Kreisteilungspolynom
`Phi_L`. Ein Rest ungleich null ist ein exaktes Gegenbeispiel mit Frequenz,
Behauptung und Restkoeffizienten. Der Prüfer ruft den Fourier-Auswertungsplan nicht
auf. Er teilt lediglich die vorhandene rationale Arithmetik mit ihm.

Ein Counterexample-Versuch entspricht einem geprüften Trainingskoeffizienten.
Reicht das Budget nicht für alle angefragten Trainingskoeffizienten, bleibt der
Lauf `INCONCLUSIVE`; die Holdout-Auswertung wird dann nicht als Bestätigung
ausgegeben. Die Holdout-Prüfung ist durch die Eingabehülle begrenzt und gehört
nicht zum Trainings-Counterexample-Budget. Das Zertifikat enthält die ursprüngliche
Aufteilung, den Plan, Trainingsprofile, Holdout-Koeffizienten und Kosten.
`proofStatus` und `externalNoveltyStatus` bleiben `NOT_EVALUATED`:
`EXACT_FINITE_DFT_WITNESS_NOT_UNIVERSAL_PROOF` bestätigt nur die geprüften Instanzen.

Die erste Version begrenzt `L` auf 1–128, Terme auf 32 pro Signal, Zähler- und
Nennerbeträge der Eingabegewichte auf jeweils 64 Bit und jeden Split auf 1–8
Aufgaben. Frequenzen müssen innerhalb `[0,L)` liegen, verschieden und nicht leer
sein. Zwischenwerte bleiben beliebig genaue rationale Zahlen. Die Begrenzungen
dienen der endlichen unabhängigen Prüfung, nicht einer Behauptung über große FFTs.

## Was die Kosten bedeuten

| Messung | Enthaltene Arbeit |
| --- | --- |
| Konstruktion | Periodengruppierung, Nullentfernung, Skalierung, vorbereitete Koeffizienten |
| Auswertung | Teilbarkeitstests je angefragtem Bin und vorbereitetem Term, rationale Addition, Ergebnisfelder |
| Auswahl | Beide vollständigen Trainingsprofile, einschließlich Konstruktion |
| Trainingsaudit | Erneute Auswertung des ausgewählten Plans und unabhängige Prüfung; separat in der Evidence |
| Holdout-Vergleich | Beide festen Pläne einschließlich Konstruktion; der ausgewählte Lauf wird darin wiederverwendet |
| Holdout-Prüfung | Zeitbereichssignal, Konstruktion der Kreisteilungspolynome und exakte Polynomreste |
| Suchressourcen | Unveränderte Ereigniszähler des bestehenden Discovery-Runners |

`SignalWork.units()` addiert explizit instrumentierte ganzzahlige Prüfungen/
Divisionen, rationale Operationen und logische Koeffizientenfelder mit Gewicht 1.
`operandBits` und `maxOperandBits` messen die Größen der Operanden rationaler
Operationen. Sie sind zusätzliche Diagnostik und verändern die Auswahl nicht.
Interne GGT-Algorithmen, Hash-Tabellen, Schleifenverwaltung, Serialisierung,
Speicherbytes, Garbage Collection und Laufzeit sind nicht modelliert. Diese
Einheiten sind weder Bitkomplexität noch gemessene CPU-Zeit. Insbesondere ist der
unabhängige Prüfer eine Korrektheitsreferenz und keine konkurrenzfähige FFT-Baseline.

Ein Vorteil gegenüber dem festen direkten Plan kann schon durch bekanntes
Zusammenfassen entstehen. Gegenüber dem festen zusammenfassenden Plan entsteht
auf solchen Aufgaben kein zusätzlicher Auswertungsvorteil durch die Suche;
Auswahl und Audit kosten zusätzlich. Die negative Kontrolle zeigt außerdem,
dass die Auswahl auf eine neue Verteilung schlechter übertragen werden kann.
Für eine Laufzeitbehauptung wären ein eigener Benchmark, größere geeignete
Aufgaben und ein Vergleich mit optimierten FFT-/Sparse-FFT-Verfahren nötig.
