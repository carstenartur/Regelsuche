# Exakte Sharing-Repräsentation

`ExactSharingRepresentation` ist die stärkere, fail-closed Folgestufe eines
materiellen `RepeatedStructureExtractionCandidate` aus #663.

Der Extraktionskandidat darf semantisch gleiche, aber unterschiedlich notierte
Vorkommen zusammenfassen. Für eine **exakte Definition/Referenz-Repräsentation**
reicht diese semantische Gleichheit allein nicht aus: Jedes ersetzte Vorkommen
muss denselben normalisierten AST wie die bewahrte Definition besitzen. Damit
kann die Sharing-Überlagerung wieder zum unveränderten normalisierten
Ausgangsbaum expandiert werden.

## Gebundene Evidence

Das content-addressed Artefakt bindet:

- die Identität des vollständigen Extraktionskandidaten;
- den normalisierten Ausgangsausdruck;
- die gemeinsame semantische `ValueKey`-Identität;
- den exakt bewahrten Definitionsausdruck;
- alle sortierten `ExpressionOccurrencePath`s;
- die eingefrorene Anti-Alias-Kostenpolicy;
- rohe Baum-, Definitions-, Referenz- und Nettoeinsparungskosten;
- den Hash des Ausgangsbaums und denselben Hash nach Expansion;
- die unveränderliche Claim-Grenze.

Der Konstruktor vertraut diesen Feldern nicht. Er führt den
`RepeatedStructureExtractor` mit derselben Policy erneut auf dem Ausgangsausdruck
aus und verlangt, dass genau die gebundene Kandidatenidentität reproduziert
wird. Danach werden Definition, semantische Identität, Kosten und Pfade mit dem
reproduzierten Kandidaten verglichen.

## Exakte Rekonstruktion

Für jeden Referenzpfad wird der Teilbaum im erneut geparsten Ausgangsausdruck
aufgelöst. Er muss strukturell exakt dem geparsten Definitionsausdruck
entsprechen. Doppelte oder überlappende Referenzpfade werden abgewiesen.

Sind diese Bedingungen erfüllt, ist die Definition/Referenz-Darstellung eine
verlustfreie Überlagerung des vorhandenen AST:

```text
normalisierter Ausgangsbaum
  -> ausgewählte identische Teilbäume als Referenzen auffassen
  -> dieselbe bewahrte Definition an jeder Referenz expandieren
  -> exakt derselbe normalisierte Ausgangsbaum
```

`reconstructSourceExpression()` wiederholt die Pfad- und AST-Prüfung und gibt nur
dann den gebundenen normalisierten Ausgangsausdruck zurück.

## Bewusst fail-closed bei AC-äquivalenter Notation

Ein Extraktionskandidat kann beispielsweise `x + y` und `y + x` unter derselben
semantischen Identität führen. Das bleibt wertvolle Discovery-Evidence, reicht
aber nicht für die hier behauptete exakte Syntaxrekonstruktion. Solche
presentation-different Vorkommen werden mit
`exact sharing requires presentation-identical occurrences` abgewiesen.

Eine spätere duale Syntax-/Wert-DAG-Repräsentation könnte unterschiedliche
lokale Präsentationen explizit modellieren. Diese Erweiterung darf deren
Metadatenkosten nicht als kostenlose Kompression verstecken.

## Schutz gegen gefälschte Evidence

Die Tests charakterisieren unter anderem:

- stabile kanonische JSON- und SHA-256-Identität;
- Kandidaten-, Policy-, Kosten- und Pfadbindung;
- Ablehnung nicht-materieller Kandidaten;
- Ablehnung eines Kandidaten, der nicht aus seinem behaupteten Quellbaum
  reproduziert werden kann;
- Ablehnung von semantisch gleichen, aber syntaktisch verschiedenen Vorkommen;
- Ablehnung manipulierter Schema-, Kandidaten-, Baumhash- und Claim-Felder.

## Claim-Grenze

Das Artefakt belegt eine exakte, normalisierte AST-Sharing-Repräsentation und die
bereits eingefrorenen Sharing-Kosten. Es:

- führt noch keine `let`- oder Alias-Syntax in die Ausdruckssprache ein;
- ist keine ausführbare mathematische Rewrite-Regel;
- behauptet keine Nützlichkeit für ein bestimmtes Suchziel;
- ersetzt keine Äquivalenz-, Annahmen- oder Proof-Policy für andere Kandidaten;
- belegt keine held-out Überlegenheit oder externe mathematische Neuheit.

Die nächste stärkere Stufe ist die Einbindung dieses Artefakts in ein
vollständiges Repräsentationskandidaten-Dossier und den Run-Workspace aus #669.
