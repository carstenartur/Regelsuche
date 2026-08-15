# Wiederholte semantische Strukturen extrahieren

Die Representation Discovery behandelt wiederholte semantische Strukturen als
eigenen Kandidatentyp. Sie verschwinden damit nicht mehr unbemerkt in einem
einzigen aggregierten Einfachheitswert.

`RepeatedStructureExtractor` parst einen Ausdruck, projiziert jedes
AST-Vorkommen in die vorhandene owner-scoped `ExprValueFactory` und gruppiert
nicht-blattförmige Vorkommen nach ihrem stabilen semantischen `ValueKey`. Der
erzeugte `RepeatedStructureExtractionCandidate` bewahrt:

- den normalisierten Ausgangsausdruck;
- die gemeinsame semantische Wertidentität;
- jeden exakten `ExpressionOccurrencePath`;
- den aus dem AST deterministisch gerenderten Ausdruck jedes Vorkommens;
- einen deterministischen Repräsentanten aus den tatsächlich beobachteten
  Vorkommen;
- die content-addressed Extraktions- und Kosten-Policy;
- rohe Baumkosten, Definitionskosten, Referenzkosten und Nettoeinsparung;
- einen stabilen Content-Hash der vollständigen Kandidatenevidence.

## Schutz gegen Schein-Kompression durch Aliase

Ein neu eingeführter Name ist nicht kostenlos. Die Standard-Policy vergleicht:

```text
Kosten der wiederholten Bäume
  = Summe der AST-Knoten aller Vorkommen

Kosten expliziten Sharings
  = AST-Knoten des Repräsentanten
  + ein Bindungsknoten
  + ein Referenzknoten je ersetztem Vorkommen

Nettoeinsparung
  = Kosten der wiederholten Bäume - Kosten expliziten Sharings
```

Ein Kandidat ist nur dann materiell, wenn er die eingefrorene minimale
Nettoeinsparung erreicht. Zwei Vorkommen von `x + 1` erhalten daher keinen
Kompressionsgewinn: Definition und beide Referenzen kosten ebenso viel wie die
beiden kleinen Teilbäume. Größere oder häufiger wiederholte Strukturen können
dagegen qualifizieren.

## Semantische Gleichheit und Vorkommensidentität

Die Gruppierung verwendet `ValueKey`. Daher können AC-äquivalente Vorkommen wie
`x + y` und `y + x` zum selben Kandidaten gehören. Ihre unabhängig gerenderten
AST-Formen und ihre jeweiligen Pfade bleiben erhalten. Ursprüngliche Leerzeichen
und redundante Klammern werden ausdrücklich nicht als bewahrter Quelltext
behauptet.

Die Klassifikation vereinigt außerdem keine AST-Vorkommen und entscheidet nicht
automatisch, dass alle semantisch gleichen Werte gemeinsam ersetzt werden
müssen.

## Exakte Folgestufe

Ein materieller Kandidat mit presentation-identischen, nicht überlappenden
Vorkommen kann nun durch `ExactSharingRepresentation` in eine content-addressed
Definition/Referenz-Überlagerung überführt werden. Diese Folgestufe regeneriert
den Kandidaten aus dem Quellbaum, prüft jeden Pfad gegen denselben exakten AST
und bindet Ausgangs- und Expandiert-Baumhash. Semantisch gleiche, aber anders
notierte Vorkommen bleiben bewusst auf der schwächeren Extraktionsstufe.

Details stehen unter [Exakte Sharing-Repräsentation](exact-sharing-representation.md).

## Claim-Grenze

Der Extraktor identifiziert zunächst eine Sharing-Gelegenheit. Er:

- führt noch keinen `let`-Ausdruck oder Alias in die Sprache ein;
- beweist allein noch keine entsprechende Umformung des Gesamtausdrucks;
- entscheidet nicht, ob Sharing für ein konkretes Ziel vorzuziehen ist;
- befördert den Kandidaten nicht zu einer ausführbaren Regel;
- behauptet keine externe mathematische Neuheit.

`ExactSharingRepresentation` verschärft ausschließlich die normalisierte
AST-Rekonstruktions- und Evidence-Bindung. Äquivalenz-/Annahmenpolitik für andere
Kandidatentypen, Capability-Unlocks, held-out Transfer und das vollständige
Dossier aus #669 bleiben eigenständige Gates.
