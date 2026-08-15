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

## Claim-Grenze

Der Extraktor identifiziert eine Sharing-Gelegenheit. Er:

- führt noch keinen `let`-Ausdruck oder Alias in die Sprache ein;
- beweist noch keine entsprechende Umformung des Gesamtausdrucks;
- entscheidet nicht, ob Sharing für ein konkretes Ziel vorzuziehen ist;
- promoted den Kandidaten nicht zu einer ausführbaren Regel;
- behauptet keine externe mathematische Neuheit.

Weitere Slices aus #663 können aus der bewahrten Evidence explizite
Gesamtausdruck-Kandidaten bilden, Äquivalenz und Annahmen binden, freigeschaltete
Fähigkeiten messen und das vollständige Dossier über #669 sichtbar machen.
