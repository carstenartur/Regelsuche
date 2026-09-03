# Exaktes Replay-Receipt für endliche Polynompläne

## Zweck

Der in #877 gemergte Resolver kann einen vollständigen endlichen
Koeffizienten-/Vorzeichenlauf erneut ausführen und als Java-Wert mit dem
ursprünglichen Lauf vergleichen. `ExactFinitePolynomialPlanReplayVerifier`
macht aus diesem Vorgang nun ein kanonisches, content-addressiertes Receipt.

Der Ablauf lautet:

```text
SchematicProofPlan
  + eingefrorene Formationseingaben
  + erwarteter ExactFinitePolynomialPlanRun
  -> vollständige erneute Solverausführung
  -> erneute Plan- und Topologieprüfung
  -> exakte Gleichheit des gesamten Planlaufs
  -> ReplayReceipt
```

Ein Receipt entsteht nur bei vollständiger Gleichheit. Ein abweichender Plan,
eine andere Quelle, ein anderer Ansatz, andere Hole-Domänen, ein anderer
Retained-Limit oder ein anderer Planlauf werden fail-closed abgelehnt.

## Ausstellungsgrenze

`ReplayReceipt` ist eine versiegelte, nur lesbare öffentliche Schnittstelle.
Ihre einzige zugelassene Implementierung ist ein privater Record innerhalb des
Verifiers. Es gibt keinen öffentlichen oder package-sichtbaren Konstruktor und
keine Factory, die `CONFIRMED_IDENTICAL_REPLAY` ohne den vollständigen Aufruf
von `verify(...)` ausstellen kann.

Damit ist ein Receipt nicht lediglich ein frei konstruierbarer Datenrecord mit
einem Erfolgsetikett. Seine normale Java-Ausstellung bleibt an die tatsächliche
erneute Solverausführung und den exakten Vergleich des vollständigen Planlaufs
gekoppelt.

## Gebundene Informationen

Das Receipt bindet ausdrücklich:

```text
Verifier-ID und -Revision
Plan-Hash
Planlauf-Hash
Solverresultat-Hash
Solverrevision
Laufstatus
vollständige und ausgewertete Belegungszahl
Anzahl passender Belegungen
Anzahl gespeicherter Lösungen
Hashes sämtlicher aufgelöster Kandidaten
Replaystatus
Receipt-Hash
```

Die drei Solver-/Planlaufzustände bleiben unterscheidbar:

```text
COMPLETE_WITHOUT_SOLUTION
COMPLETE_WITH_RESOLUTIONS
COMPLETE_RESOLUTION_SET_TRUNCATED
```

Ein vollständiger Nullfund erhält keine Kandidatenhashes. Ein abgeschnittener
gespeicherter Lösungsraum muss mehr passende Belegungen als gespeicherte
Lösungen ausweisen. Inkonsistente Zähler oder doppelte Kandidatenhashes werden
bei der privaten Konstruktion abgelehnt.

## Vertrauensgrenze

`CONFIRMED_IDENTICAL_REPLAY` bedeutet:

- derselbe kanonische Plan wurde erneut geprüft;
- der vollständige endliche Solverraum wurde erneut ausgewertet;
- der daraus erzeugte `ExactFinitePolynomialPlanRun` ist exakt gleich;
- das Receipt bindet diesen Vorgang an die aktuellen Resolver- und
  Solverrevisionen.

Es bedeutet ausdrücklich nicht:

- formal unabhängige Proof-Evidence;
- Laden und Prüfen externer Evidence-Bytes;
- Replay primitiver Rewrite-Regeln;
- Kompilation in ein `RewriteProgram`;
- Ausführungs-, Promotion- oder Public-Evidence-Autorität;
- Entdeckung der Ansatzgrammatik.

Das Receipt enthält absichtlich weder Quelle noch Ansatz noch Zielausdruck oder
ausführbare Transformation. Diese Formationseingaben bleiben über Plan- und
Planlaufidentitäten gebunden, ohne späteren Ziel-/Holdout-Inhalt in das Receipt
zu kopieren.

## Charakterisierung

```bash
./gradlew \
  :regelsuche-learning:test \
  --tests '*ExactFinitePolynomialPlanReplayVerifierTest'
```

Die Tests decken ab:

- den vollständigen Replaylauf der quadratischen Ergänzung;
- deterministische identische Receipts;
- vollständige Nullresultate;
- abgeschnittene gespeicherte Lösungsmengen;
- veränderte Quelle und Hole-Domänen;
- Plan- und Planlaufsubstitution;
- kanonische Receipt-Inhalte;
- die versiegelte private Implementierungs- und Konstruktionsgrenze;
- das Fehlen von Ziel-, Ausdrucks- und RewriteProgram-Feldern.

## Nächster Schritt

Das Receipt ist die Voraussetzung für eine spätere unabhängige Evidence-Grenze.
Diese muss kanonische Receipt- und Planlaufartefakte aus einem adressierten Store
laden, ihre Bytes selbst hashen, die Replayausführung unter einer eingefrorenen
Runtime wiederholen und erst danach eine stärkere, weiterhin eng begrenzte
Autorität ausstellen.
