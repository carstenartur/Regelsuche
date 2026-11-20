# Gemeinsame bilineare Vorschläge im mathematischen Kern

## Vertrag und Zuständigkeit

`BilinearPairCandidates` erkennt gemeinsam vorliegende Ausdrücke
`a*c - b*d` und `a*d + b*c`. Es schlägt für den zweiten Ausdruck
`(a+b)*(c+d) - a*c - b*d` vor. Bei gemeinsamer Auswertung können die beiden
bereits benötigten Produkte wiederverwendet werden. Das ist die klassische
Drei-Produkt-Identität; ein neuer Satz oder eine allgemeine Tensorrangsuche
wird nicht behauptet.

Der Kern arbeitet über einen typisierten `Algebra<E>`-Adapter. Dieser liefert
binäre Operationen und konstruiert Ausdrücke in der Sprache des Aufrufers.
Ein leerer Operationsblick schließt einen Ausdruck aus der Mustersuche aus.
Die Erkennung der Faktoren, ihrer Orientierungen und der gemeinsamen
Modulhülle sowie die algebraische Konstruktion liegen vollständig im Kern.

Der SDK-Adapter `BilinearCandidates` lässt ausschließlich die passenden
`BigInteger`-Operationen zu und reicht die gemeinsame Arbeitsabrechnung
weiter. Primitive Java-Zahlen und ihre Überlaufsemantik werden dadurch nicht
als kommutative exakte Ganzzahlarithmetik behandelt. Die bestehende
`PolynomialProof`-Prüfung entscheidet weiterhin über die Annahme eines
Vorschlags. Eine Kandidatenliste ist kein Gleichheitszertifikat.

## Grenzen und Abbruch

Ausgaben bleiben an ihren ursprünglichen Positionen. Die Modulhüllen beider
Teilrechnungen müssen strukturell übereinstimmen. Ergebnislisten sind
unveränderlich. Das Kandidatenlimit beendet die Erzeugung unmittelbar, ohne
weitere vollständige Ausgabelisten aufzubauen.

Der Aufrufer stellt einen `LongConsumer` zur Verfügung, der vor Scans,
Paarprüfungen und Ergebnisallokationen Arbeit verbucht und bei Erschöpfung
oder Abbruch werfen darf. Im SDK ist dies das bestehende gemeinsame
`VerificationWork`. Der Adapter muss eine stabile Sicht der Ausdrücke liefern.
Der Kern besitzt keine Java-Typprüfung und keinen eigenen Akzeptanzbeweis.

## Prüfvergleich

Der neue Kerntest wertet alle erzeugten Orientierungen über 625 exakte
Vierertupel aus `{-2,-1,0,1,2}` unabhängig aus. Weitere Kontrollen prüfen
verschiedene Moduln, nicht benachbarte Ausgabepositionen, unveränderliche
Listen, das Kandidatenlimit und den linearen Scan von 1.500 irrelevanten
Ausgaben. Die bestehenden SDK-Kontrollen prüfen die Vorschläge anschließend
mit dem unabhängigen Polynomprüfer, einschließlich modularer Ausgaben und
Abbruchweitergabe.

```sh
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=BilinearPairCandidatesTest,ValueReviewBoundaryTest,ValueArithmeticTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Der Test schlug vor Einführung des Kerneinstiegs wegen des fehlenden Typs
fehl und bestand nach der Auslagerung. Der Befund belegt die erhaltene
Funktion und die korrigierte Abhängigkeitsrichtung. Eine zusätzliche
Laufzeitbeschleunigung durch die Auslagerung wurde nicht gemessen.
