# Gemeinsame Potenzen mit expliziten Beobachtungen

Stand: 26. November 2026. `SharedPowerSearch` erzeugt gemeinsame Rechenpläne
für mehrere Potenzen derselben Gruppenbasis. `SharedPowerVerifier` prüft
ihre Exponenten unabhängig. Die gesamte Mathematik liegt im Modul
`regelsuche-math-algorithms`; `SharedPowerSdk` delegiert.

## Mathematischer Vertrag

Die Quelle besteht aus einer positiven ganzen Zahl `n` und geordneten
Ausgaben `(e,k)`, wobei `e` ganzzahlig und `k` ein positiver Teiler von `n` ist.
Die Voraussetzung lautet

\[
x^n=1.
\]

`n` ist ein Vielfaches der tatsächlichen Ordnung, keine Behauptung ihrer
Exaktheit. Für jede Ausgabe ist die Beobachtung

\[
y^k=(x^e)^k
\]

freigegeben. `k=1` fordert den exakten Wert. `k=2` bedeutet in einem Körper
ungerader Charakteristik Gleichheit bis auf das Vorzeichen. Über einem
zusammengesetzten Modulus gilt diese Vorzeicheninterpretation im Allgemeinen
nicht: modulo 15 haben 1 und 4 gleiche Quadrate, sind aber nicht gleich bis
auf das Vorzeichen.

Die Ausgaben bleiben Potenzen von `x`. Die Suche durchsucht nicht sämtliche
Wurzeln in der umgebenden Gruppe. Sie erhält auch keine implizite Korrelation
zwischen mehreren zulässigen Vorzeichen und keine kanonische Normalisierung.

## Unabhängiger Nachweis

Die Knoten 0 und 1 bezeichnen Eins und Basis. Weitere Schritte sind eine
nichtnegative Potenz der Basis, ein Produkt früherer Knoten oder eine
Inverse. Der Prüfer rekonstruiert den tatsächlich realisierten Exponenten
`v` jedes Knotens modulo `n`. Für jede Ausgabe prüft er

\[
n\mid k(v-e).
\]

Induktion über die Schritte zeigt, dass jeder Knoten den Wert `x^v` hat.
Die Teilbarkeit beweist anschließend die geforderte Beobachtung für jedes
Gruppenelement mit `x^n=1`. Die Suche wird dazu nicht aufgerufen. Der Prüfer
bindet die unveränderte Quelle einschließlich Ausgabereihenfolge und `k`,
weist ungültige Referenzen ab und berechnet alle Kosten selbst neu.
Gewichte sind keine mathematische Aussage des Zertifikats; ein bereits
gültiger Plan kann unter anderen Gewichten erneut bewertet werden.

`SharedPowerEvaluation` prüft zusätzlich die konkrete Voraussetzung
`x.modPow(n,m)==1` vor der Ausführung. Damit sind Null und Nicht-Einheiten
ausgeschlossen. Der Modulus darf zusammengesetzt sein. Der Adapter gibt
die rohen beobachtungsäquivalenten Werte zurück.

## Suchraum und stärkere Vergleichsrechnung

Das endliche Portfolio vergleicht die ursprünglichen Vertreter `e mod n`
mit den freigegebenen Vertretern `e mod (n/k)`. Für beide Varianten verwendet
es dieselben drei Ausgabereihenfolgen und dieselben Regeln:

- bereits vorhandene tatsächliche Potenzen wiederverwenden;
- eine vorhandene komplementäre Potenz invertieren;
- beschränkt oft mit der Basis multiplizieren;
- eine neue Potenz direkt berechnen oder den komplementären Exponenten
  potenzieren und danach invertieren.

Jeder Kandidat wird geprüft. Der beste gefundene exakte gemeinsame Plan
bleibt im Portfolio. Die Vergleichsrechnung verwendet daher bereits
gemeinsame Werte und Inverse. Es wird weder eine minimale Additionskette
noch ein global optimaler Plan behauptet. Die Kandidaten hängen von den
Exponenten und dem freigegebenen Vertrag ab, nicht von Algorithmusnamen oder
bestimmten Primzahlen.

Die Gewichte betreffen Multiplikation, Quadrierung und Inversion. Für einen
`POWER`-Schritt schätzt das Modell die innere Binärkette anhand von
Bitlänge und gesetzten Bits. Das ist ausdrücklich kein Modell der genauen
Fensterstrategie von `BigInteger.modPow` und keine Laufzeitmessung.

## Übertragung auf eine quadratische Erweiterung

Für eine Primzahl `p=4q+3` haben die Anforderungen
`e=(p+1)/4` und `-e`, jeweils mit `k=2`, die freigegebenen Vertreter
`q+1` und `q`. Der gemeinsame Plan kann deshalb `t=x^q` berechnen und
`x*t` wiederverwenden. Diese Exponenten entstehen aus der allgemeinen
Reduktion modulo `(p-1)/2`.

Für die Anwendung auf eine Fp²-Wurzel sind zusätzliche, konkrete
Consumer-Pflichten nötig. Angenommen `i²=-1`, `b!=0` und
`g²=a²+b²`. Setze

\[
\delta=(a+g)/2,\qquad \delta_-=(a-g)/2,
\qquad t=\delta^{(p-3)/4}.
\]

Dann ist `delta!=0`, denn `delta=0` würde `b²=0` erzwingen. Weiter gilt
`delta*delta_-=-b²/4` und nach dem Euler-Kriterium
`epsilon=delta*t²` gleich 1 oder -1. Für

\[
c=\delta t,\qquad d=bt/2
\]

folgen `c²=epsilon*delta`, `d²=-epsilon*delta_-` und

\[
(c+di)^2=\epsilon(a+bi).
\]

Im positiven Zweig ist `(c,d)` die Wurzel; im negativen Zweig ist es
`(d,-c)`. Ein bloßes Vertauschen wäre falsch. Der Fall `b=0` braucht
weiter seine gesonderte Behandlung. Eine Anwendung mit kanonischer
Vorzeichenwahl muss diese anschließend beibehalten.

Die einzelnen Quadratbeobachtungen allein beweisen diesen größeren
Programmvertrag noch nicht. Der Consumer muss insbesondere die tatsächlich
gewählten Exponenten prüfen; ein günstigerer exakter Plan kann statt `q`
einen anderen erlaubten Vertreter liefern. Der Integrationstest tut dies
ausdrücklich und prüft anschließend beide Rotationszweige über Fp43.
Die Bouncy-Castle-Quelländerung und ihre Messung sind separate Consumer-Arbeit.

## Ausgeführter Strukturvergleich

Der [reproduzierbare Vergleich](../../research/benchmarks/shared-power-relations/README.md)
verwendet dieselbe Suche auf sechs Fällen. Bei Multiplikations- und
Quadrierungsgewicht 1 sowie Inversionsgewicht 8 sinkt die Modellarbeit des
Quadratpaares modulo 1019 von 22 auf 14, die des Kubikpaares modulo 103 von
7 auf 2. Exakte benachbarte Potenzen bleiben beim bereits gemeinsamen Plan
mit 19 Einheiten. Bei `p=31` und Inversionsgewicht 1 gewinnt die exakte
Vergleichsrechnung mit 4 Einheiten gegenüber den reduzierten Vertretern.

Der 381-Bit-Eingabefall wählt eine Potenz und ein zusätzliches Produkt ohne
Inverse; im ausdrücklich gewählten Modell stehen 614 gegen 606 Einheiten.
Die CSV führt sämtliche Such- und Prüfarbeit gesondert. Sie vergleicht
keine vollständigen Kryptografieoperationen und autorisiert keine
Laufzeitprozentzahl.

## Grenzen und Ressourcen

Eingabewerte sind auf 4096 Bit, Ausgaben auf 64 und Schritte auf 4096
begrenzt. Die Eingabewerte kopieren Listen mit einer festen Obergrenze.
`IterationLimits` begrenzt die zusätzliche Arbeits-, Zellen- und Zeitbilanz.
Alle sechs Kandidaten einschließlich ihrer Prüfung teilen dieselbe Bilanz.
Ein kleineres Kandidatenlimit begrenzt dieses Portfolio; die exakten
Vorschläge kommen zuerst. Das SDK übernimmt auch `maximumCandidates`.
Bei Abbruch wird kein partielles Zertifikat freigegeben; ein solcher
Abbruch widerlegt keine mathematische Aussage. Einzelne begrenzte
`BigInteger`-Operationen sind nicht mitten in der Operation unterbrechbar.

Die Auswertung prüft die Gruppenannahme bei jeder Anwendung erneut und
belastet damit ihre Arbeitsbilanz. Diese enthält außerdem die Zertifikatsprüfung und die
modularen Operationen. Diese Kosten fehlen bewusst im reinen Planmodell
und können bei wenigen Anwendungen dominieren. Ein vertrauenswürdiger
spezialisierter Consumer kann Voraussetzungen anders begründen; diese
API überspringt sie nicht.

Die neuen Haupt- und Testquellen werden im regulären Maven-Lebenszyklus
zusätzlich mit `-Xlint:all -Werror` übersetzt. Ältere Quellen der Module
besitzen unabhängig davon bestehende Warnungen. Es werden keine
Warnkategorien abgeschaltet.

## Reproduktion

Mit Java 25 und Maven 3.9.9 oder neuer aus der 3.x-Reihe:

```sh
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=SharedPowerSearchTest,SharedPowerSdkTest clean test
```

Die Tests prüfen 864 nichtverschwindende Feldbasen in den 16 Primkörpern
`p=3 mod 4` bis 127, 3528 nichtreelle Wurzelfälle einschließlich beider
Normvorzeichen über Fp43, exakte und kubische Verträge, zusammengesetzte
Moduln, verfälschte Quellen/Referenzen und Ressourcenabbrüche. Das SDK wird
gegen die identische Kernantwort samt Arbeitsbilanz verglichen.
