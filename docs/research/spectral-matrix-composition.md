# Spektrale Reparatur, Matrixzentren und zusammengesetzte Rechenpläne

Stand: 27. November 2026. Die drei Verfahren sind typisierte Java-Kern- und
SDK-Funktionen im Quellstand `0.5.4-SNAPSHOT`. Die veröffentlichte Version
0.5.3 enthält sie nicht. Diese Seite beschreibt den mathematischen Vertrag
und reproduzierbare endliche Vergleiche.

## Zusammenhang mit den Papieren

Die spektrale Konstruktion adaptiert die vollständige Hermite-Reparatur aus
[OpenAI Math, Fourier-Preprint, repair.tex](https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/preprints/The-logarithmic-exponent-of-finite-Fourier-transforms-November-24-2026/build/repair.tex)
auf rationale Involutionen. Die Matrixvergleiche erweitern die vorhandene
affine Realisierung nach
[OpenAI Math, rationale Formeln, encoding.tex](https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/preprints/Polynomial-Hitting-Lists-for-Noncommutative-Rational-Formulas-September-24-2026/build/sections/encoding.tex).
Die Komposition macht die linearen Stufen eines bilinearen Programms zu
weiteren Suchaufgaben. Sie ist eine Regelsuche-Integration dieser vorhandenen
Bausteine. Die Implementierung beansprucht weder den asymptotischen
Fourier-Exponenten noch eine polynomielle universelle Trefferliste.

## 1. Formelvergleich an einem Matrixzentrum

Für die überprüfte Differenzrealisierung $uL(X)^{-1}v$ und ein gemeinsames
definiertes Zentrum $C$ der Ordnung $m$ setzt der Kern

$$K=L(C),\qquad D_{iab}=L_i\otimes E_{ab},\qquad
V=v\otimes I_m,\qquad U=u\otimes I_m.$$

Die Suche beginnt mit allen Spalten von $K^{-1}V$ und schließt den Raum unter
allen $-K^{-1}D_{iab}$. Sie verwendet jede Variable und jede Matrixeinheit,
nicht nur eine simultane skalare Störung des Zentrums. Der Prüfer
rekonstruiert aus den ursprünglichen Formeln und dem Zentrum die Bedingungen

$$\operatorname{im}V\subseteq\operatorname{im}KB,\qquad
\operatorname{im}(D_{iab}B)\subseteq\operatorname{im}KB,\qquad UB=0.$$

Damit verschwinden sämtliche Koeffizienten der formalen Entwicklung. Das
beweist die rationale Identität für alle Ordnungen, die Vielfache von $m$
sind. Eine direkte Summe aus $m$ Kopien einer beliebigen definierten
Belegung überträgt die Aussage auf jede Matrixordnung. Alle ursprünglichen
Inversenvorkommen bleiben dabei verpflichtend, auch in mit null
multiplizierten Zweigen.

Eine nichtverschwindende Beobachtung erzeugt einen blockoberen
Dreiecksgegenzeugen mit wiederholtem Zentrum und Matrixeinheiten. Der
unabhängige Prüfer wertet beide ursprünglichen Formelquellen direkt aus.
Der Beleg bindet jeden Eintrag des Zentrums einschließlich Nebendiagonalen.

`RationalFormulaCenterSearch.MatrixPolicy` enumeriert Einträge generisch
aus einer endlichen Werteliste über vorgegebenen Ordnungen. Beide
Quellrealisierungen werden wiederverwendet; alle Zentren, Fehlschläge und
die Abschlussprüfung teilen ein Budget. Ein erschöpfter Zentrumsetat wird
vor der Reservierung der nächsten Matrixordnung geprüft.

Die Kontrolle $H^{-1}H=I$ für $H=XY-YX$ bleibt an jedem Skalarzentrum
undefiniert, wird aber an einem zulässigen Zentrum der Ordnung zwei
bewiesen. $H^{-1}$ und $-H/4$ stimmen am gewählten Zentrum überein und
erhalten trotzdem einen Gegenzeugen. Die Gegenkontrolle
$[(XY-YX)^2,X]$ verschwindet auf allen Matrizen der Ordnung zwei, aber nicht
universell; die Suche erkennt die Ungleichheit. Ein erfolgreicher Vergleich
bezeichnet Gleichheit auf dem gemeinsamen ursprünglichen Definitionsbereich,
keine Gleichheit der Definitionsbereiche.

## 2. Tatsächlich ausgeführte spektrale Batch-Reparatur

Der Vertrag verlangt rationale Matrizen $F,B,S$ mit $S=B^{-1}$ und
$SFB=\operatorname{diag}(z_\nu)$, $z_\nu\in\{-1,1\}$. Die quellengesteuerte
Variante gewinnt eine Basis durch unabhängige Spalten von $F+I$ und $F-I$.
Für die Batch-Prädiktoren $P_i$ wird auch $SP_iB$ unabhängig auf
Diagonalität geprüft. Sei

$$J_\nu=\{i:p_{i\nu}\ne z_\nu\},\qquad s=\max_\nu |J_\nu|.$$

Die Ausnahmeindizes dürfen sich je Frequenz ändern. Mit einer
Vandermonde-Prüfmatrix $H$ konstruiert der Erzeuger rationale Tabellen
$A_\nu$. Der unabhängige Prüfer kontrolliert direkt

$$H_{J_\nu}^{T}A_\nu H_{J_\nu}
=\operatorname{diag}_{i\in J_\nu}\left(-\frac{p_{i\nu}+2z_\nu}{2}\right).$$

Er rekonstruiert die tatsächlichen Träger aus den Prädiktoren und vergleicht
nicht einfach mit einer erneut erzeugten Tabelle. Auch andere gültige
Decoder werden akzeptiert. Außerhalb des Trägers verschwindet der Faktor
$p_{i\nu}-z_\nu$, sodass dort keine zusätzliche Decoderbedingung nötig ist.

Die Ausführung berechnet zuerst $PX$, bildet $H(PX)$ und $HX$ und wendet
$S$ auf genau $2s$ Prüfreihen an. Aus $a=A(u-zv)$ und $b=za$ entstehen
durch genau $2s$ Anwendungen von $B$ und die Hebung mit $H^T$ die
Korrekturen $Z$ und $FZ$. Weitere Prädiktorläufe berechnen $P^2X$, $P^3X$
und $PZ$. Die Ausgabe lautet

$$FX=\frac{3PX-P^3X}{2}-PZ+FZ.$$

Die Reparatur enthält keinen versteckten vollständigen Aufruf von $F$ auf
dem Eingabebatch. Für $s=0$ werden nur die bereits geprüften Prädiktoren
ausgeführt. Automatische Auswahl vergleicht direkte, spektral direkte und
Reparaturpläne und übernimmt ausschließlich strikte Verbesserungen.

Alle vier Prädiktorläufe, Mischungen, Decoder, Vorzeichen, Synthesen und
Schlusskombinationen zählen. Der direkte Vergleich verwendet denselben
sparsamen Interpreter mit identischen Null- und Einskonventionen. Die
Aufbewahrung eines kompilierten Plans wird vor seiner Anlage im kumulativen
Speicheretat reserviert. Ein noch gehaltener direkter Plan bleibt während
der Prüfung späterer Kandidaten belastet.

## 3. Bilineare Programme als zusammengesetzte Suche

Ein geprüftes bilineares Programm führt $U x$, $V y$, komponentenweise
Produkte und anschließend $W p$ aus. `ComposedBilinearSearch` stellt jede
lineare Stufe als eigenes Operatorproblem bereit. Die endliche Policy
erprobt Residualrezepte und, bei passender Involution, Spektralrezepte.
Rechteckige Stufen bleiben über den Residualpfad ausführbar. Rang null
besitzt einen ausdrücklichen Nullausgabepfad.

Der unabhängige Gesamtprüfer prüft zuerst Tensor, Katalog und bilineares
Programm, rekonstruiert daraus U/V/W und bindet jeden eingereichten
Stufenbeleg einschließlich Batchbreite und Eigenbasis. Der vorbereitete
Plan führt genau diese Stufen aus. Eine Spektralstufe kann dabei tatsächlich
die oben beschriebene Batch-Reparatur ausführen.

Ein gemeinsamer Etat bezahlt Erwerb, Unterversuche und Abschlussprüfung.
Unteraufrufe erhalten nur die Restlimits nach Abzug gehaltener Pläne; ihre
geleistete Arbeit wird vor einer Abbruchprüfung beim Rücksprung verbucht.
Die Kandidatengrenze zählt deklarierte Vorschläge auf oberster Ebene: einen
Erwerbsvorschlag sowie Residual- und geeignete Spektralrezepte je Stufe.
Interne bilineare Teilmengen bezahlen Arbeit, Speicher und Zeit; sie werden
nicht zusätzlich als oberste Vorschläge gezählt. Bei einem gelieferten
Startprogramm wird dessen aktuelle Prüfung bezahlt; unbekannte frühere
Erwerbsarbeit wird nicht als gemessene Null ausgegeben.

## Vergleichsfälle und Aussagegrenzen

| Fall | Starke direkte Kontrolle | Kandidat | Aussage |
| --- | ---: | ---: | --- |
| Drei rationale Eingabevektoren, Ordnung drei, wechselnde Träger `{0}`, `{1}`, `{2}` | 21 Operationen | 162 Operationen | Die Reparatur funktioniert; automatische Auswahl behält direkt. |
| Separate vorgegebene Eigenbasis | 120 Operationen | 110 Operationen | Spektral direkte Ausführung gewinnt; dies ist kein Reparaturgewinn. |
| $(Cx)\odot(Cy)$ mit $C=I-\mathbf1\mathbf1^T/8$ | 248 Operationen je Paar | 54 Operationen je Paar | Beide linearen Vorstufen werden durch generische Residualsuche verbessert. |

Beim letzten Fall kosten U und V jeweils acht konstante Multiplikationen
und 15 Additionen; W kostet null, hinzu kommen acht variable Produkte.
Drei Paare kosten entsprechend 744 gegenüber 162 Operationen. Für
$x=y=(1,\ldots,8)$ ist das unabhängige Ergebnis
$(49/4,25/4,9/4,1/4,1/4,9/4,25/4,49/4)$.

Die Spektralsuche ist auf rationale Involutionen mit Ordnung und Batchbreite
höchstens 16 sowie begrenzte Ausdrucksbäume und exakte Zahlgrößen beschränkt.
Alle Speicherzahlen bezeichnen konservative logische Skalar-/Referenzplätze,
keine gemessenen Heapbytes. Endliche Suchpolicies garantieren keine globale
Minimalität. Die Tabellen zählen arithmetische Operationen, keine gemessene
Wandzeit; Erwerb und Prüfung müssen bei einer Anwendung amortisiert werden.

## SDK und Reproduktion

| Aufgabe | Einstieg | Externes Beispiel |
| --- | --- | --- |
| Matrixvergleich und Zentrumssuche | `RationalFormulaSdk.compare`, `verifyComparison` | `example.MatrixCenterIdentity` |
| Spektralplan konstruieren, prüfen, vorbereiten, suchen und anwenden | `SpectralBatchSdk` | `example.SpectralRepair` |
| Zusammengesetzten Plan suchen, prüfen, vorbereiten und anwenden | `ComposedBilinearSdk` | `example.ComposedBilinearPlans` |

Mit JDK 25 und Maven 3.9.9 oder neuer innerhalb 3.x:

```sh
mvn -pl regelsuche-optimization-sdk -am test
mvn -pl regelsuche-optimization-sdk,regelsuche-bom -am install
mvn -f examples/external-consumers/checked-math-java25/pom.xml clean verify
java regelsuche-core/src/test/java/de/regelsuche/docs/StrategyCodeReferences.java --check
```

Die [externen Beispiele](../../examples/external-consumers/checked-math-java25/README.md)
verwenden ausschließlich das aufgelöste SDK. Ihre Tests prüfen konkrete
Ausgaben; die Programme prüfen ihre Belege zusätzlich unabhängig.

### Ausgeführte Prüfung dieses Änderungsstands

Der lokale Lauf mit OpenJDK 25.0.5+8 und Maven 3.9.16 umfasst die vollständige
SDK-Abhängigkeitsclosure: 1.234 Kerntests, 181 E-Graph-Tests, 811 Suchtests,
45 Validierungstests, 991 Mathematiktests und 374 SDK-Tests. Von den insgesamt
3.636 erfassten Fällen bestanden 3.635 ohne Fehler. Die bestehende optionale
Wandzeit-/Allokationsmessung `ExternalRecurrenceObservationStudyTest` wurde
wegen der nicht gesetzten Eigenschaft `externalObservationMetricsOutput`
übersprungen. Alle neuen Fach- und SDK-Tests wurden ausgeführt.

Das anschließend aus dem installierten Quell-SDK aufgelöste unabhängige
Maven-Projekt bestand alle zehn Ergebnisprüfungen und führte alle zehn
Programme aus. Die oben genannten Ausgaben und Kosten 162/21 sowie 54/248
wurden dabei erneut beobachtet. Zusätzlich bestanden die 17 geänderten
Produktionsklassen, acht Testklassen und fünf geänderten externen
Beispiel-/Testklassen `javac -Xlint:all -Werror`.

Der lokale Reaktorlauf verwendete `-DforkCount=0` und einen JVM-Heap von
768 MiB. Das sind Ausführungsoptionen dieser Umgebung; die Builddateien und
Testgrenzen des Projekts wurden dafür nicht geändert. Drei unabhängige
Quellreviews prüften Matrixzentren, Spektralreparatur und Komposition; es
blieben keine offenen Befunde. Diese Prüfungen ersetzen keine Messung der
JVM-Geschwindigkeit und keine Consumer-Qualifikation einer Veröffentlichung.
