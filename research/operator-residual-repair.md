# Exakte Operatorreparatur und belegter Hilfsspeicher

## Vom Fourier-Verfahren zum endlichen Test

Die Arbeit stellt ein Produkt kommutierender Operatoren in einer gemeinsamen
Eigenbasis dar. Ein Prädiktor trifft viele Multiplikatoren. Eine zusätzliche
Transformation repariert die abweichenden Komponenten. Die Abweichungen können
in der Arbeit für jede Frequenz andere Zeilen eines ganzen Stapels betreffen.
Der Java-Kern untersucht den begrenzten Fall einer einzelnen rationalen Matrix
mit explizit gegebener Basis. Stapelverfahren, zyklotomische Erweiterungen,
Basisfindung und Fourier-Komplexität bleiben außerhalb dieses Modells.
[Originalverfahren, Abschnitt „Prediction and exact spectral repair“](https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/preprints/The-logarithmic-exponent-of-finite-Fourier-transforms-November-24-2026/build/repair.tex)

Für eine Quellmatrix $F$, einen als geordneten Ausdruck gegebenen Prädiktor
$P$ und zwei eingereichte Matrizen $S,T$ prüft `spectral` beide
Inversenidentitäten und sämtliche Nebendiagonaleinträge von $TFS$ und $TPS$.
Für die Menge $J$ der verschiedenen Diagonaleinträge bildet der Kern

$$
U=S_{:,J}\,\mathrm{diag}\big((TFS-TPS)_{jj}\big)_{j\in J},
\qquad V=T_{J,:}.
$$

Damit nehmen die Zeilen von $V$ nur die benötigten Eigenkoordinaten ab.
Die Spalten von $U$ setzen sie einschließlich ihrer Differenzfaktoren in
die ursprünglichen Koordinaten zurück. Der Prüfer expandiert anschließend
$P+UV$ und vergleicht jeden Eintrag mit $F$. Bei leerem $J$ genügt $P$.

`verify` akzeptiert auch unabhängig konstruierte, rechteckige Pläne.
Die eingereichte Quelle muss der vom Aufrufer erwarteten Matrix entsprechen.
Die Identität $F=P+UV$ belegt zugleich, dass das Bild der Differenz im
Spaltenraum von $U$ liegt und der Rang höchstens die Spaltenzahl von $U$ ist.
Ein manuell eingereichtes $U$ darf abhängige Spalten besitzen. Ein Einreicher
kann daher durch das Beifügen zusätzlicher Spalten keinen kleineren Rang
bescheinigen. Der Prüfer benötigt keine Behauptungen über die Erzeugung der
Faktoren. `Plan.expression()` gibt die geordnete Summe bzw. das geordnete
Produkt in der bestehenden Matrixausdruckssprache zurück.

## Was die Kostenentscheidung umfasst

`select` vergleicht alle eingereichten Pläne mit der dichten bereits
materialisierten Quelle. Für eine $m\times n$-Matrix zählt das deklarierte
Vektormodell $mn$ Multiplikationen und $m(n-1)$ Additionen. Die Akkumulation
beginnt beim ersten Produkt. Ein Summenknoten mit $m$ Ausgängen kostet weitere
$m$ Additionen. Produkte rechnen von rechts nach links, Einheiten kopieren den
Vektor ohne Skalararithmetik. Die Kosten enthalten bei einer Reparatur beide
Faktoren und die Addition des Korrekturvektors. Wiederholte Ausdrucksvorkommen
kosten bei jeder Anwendung erneut. Bei Gleichstand bleibt der zuerst
betrachtete Plan erhalten. Ein ungültiger Kandidat oder ein Stopp liefert
keinen Gewinner.

| Eingabe | Reparatur, Multiplikationen + Additionen | Dichte Quelle | Günstigster Plan |
| --- | ---: | ---: | --- |
| $F=\mathrm{diag}(4,1,1)$, $P=I$ | $6+5=11$ | $9+6=15$ | Reparatur der ersten Koordinate |
| Dichtes $F=I+uv^T$ in vier Dimensionen, $P=I$ | $8+7=15$ | $16+12=28$ | Rang-eins-Reparatur |
| Drei konjugierte Involutionen auf acht Koordinaten | $224+214=438$ | $64+56=120$ | Dichte Quelle |

Der dichte Rang-eins-Fall verwendet $u=(1,2,3,4)^T$ und $v=(2,3,5,7)^T$.
Hier hat jede Komponente der Quellmatrix einen von null verschiedenen Wert.
Im dritten Fall tragen die acht Basiskoordinaten alle Vorzeichentripel.
Für ein Tripel ist der Quellwert das Produkt der drei Vorzeichen.
$P=-(Q_1+Q_2+Q_3)$ stimmt außer bei $(+,+,+)$ und $(-,-,-)$ mit diesem Wert
überein. Zwei rationale Basiswechsel $S=I+t e_{01}$ mit $t=1/2,-3$
prüfen die Konstruktion außerhalb der diagonalen Darstellung. Die Tests
vergleichen $UV$ unabhängig mit $4(E_+-E_-)$ für
$E_\pm=(I\pm Q_1)(I\pm Q_2)(I\pm Q_3)/8$. Die drei negierten
Operatoren liegen dem Prädiktor als dichte Blätter vor. Die geordnete
Ausmultiplizierung allein würde sogar bei nichtkommutierenden Involutionen
die Summenidentität ergeben. Ein Kontrollfall zeigt dort $E_+^2\ne E_+$ und
die Zurückweisung der spektralen Annahme.

Die Zahlen betreffen eine Vektoranwendung mit bereitgestellten
Koeffizienten. Dichte Verarbeitung zählt auch Nullen und Einsen. Ein
diagonalspezifischer Direktalgorithmus kann im ersten Beispiel günstiger
sein. Das Modell umfasst weder Speicherzugriffe noch Bitkosten, Laufzeit
oder eingehende Materialisierung von $F,S,T$ oder der negierten Blätter.
Der Aufbau prüft beide Basiswechsel, prüft die Diagonalformen und rechnet die
Operatoridentität nach. Die Verifikation verwendet die bestehende
polynomiale Matrixarithmetik. Deren logische Prüfarbeit wird im Ergebnis
separat ausgewiesen. In der ersten und letzten Tabellenzeile benötigen Aufbau, frische
Verifikation und Auswahl jeweils eines Kandidaten 796/150/163 bzw.
9786/1664/1738 Arbeitseinheiten (letzte Zeile mit $t=1/2$).
Das sind drei getrennte API-Aufrufe.

## Komposition mit belegtem Hilfsregister

`OccupiedWorkspace.composition(A1,A2)` liefert vier lineare Aktualisierungen
disjunkter Wertregister. Für $x\in\mathbb Q^n$, $z\in\mathbb Q^h$ und
$y\in\mathbb Q^m$ führen sie der Reihe nach
$z\gets z+A_1x$, $y\gets y+A_2z$, $z\gets z-A_1x$ und
$y\gets y-A_2z$ aus. Die Zwischenwerte von $y$ enthalten den Term
$A_2 z_{\rm alt}$. Der letzte Schritt zieht diesen Term nach der
Wiederherstellung von $z$ ab. Das Ergebnis ist
$(x,y+A_2A_1x,z)$ für **jeden** Anfangswert aller drei Register.
[Quelle, Abschnitt „Using and restoring occupied workspace“](https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/preprints/The-logarithmic-exponent-of-finite-Fourier-transforms-November-24-2026/build/layers.tex)

Der Prüfer akzeptiert auch andere endliche Folgen von Scherungen. Er bindet
beide Quellmatrizen, prüft Richtung, Vorzeichen und Abmessungen und konstruiert
den resultierenden Blockoperator für alle drei Eingabeblöcke. Ein Zugriff
auf dasselbe Register als Quelle und Ziel ist unzulässig. Die unveränderliche
Wertsemantik erlaubt gleiche Anfangswerte verschiedener Register. Das SDK
kann den geprüften Plan auf konkreten rationalen Spalten wiedergeben.

`Comparison.borrowedCells` bezeichnet die deklarierte Kapazität des
Hilfsregisters `Z`. Bei einer alternativen gültigen Folge, die `Z` gar nicht
anspricht, ist dies keine Messung tatsächlich benutzter Zellen.

Für $n=3,h=2,m=1$ benötigt der Vier-Schritt-Plan 16 Multiplikationen und
16 Additionen/Subtraktionen. Die direkte Folge $t=A_1x$, $y\gets y+A_2t$
benötigt 8 und 6 und schreibt zwei freie Zwischenwerte. Die vier Scherungen
dürfen zwei **bereits belegte** Werte leihen und stellen sie wieder her.
Beide Zählungen verwenden zeilenweise Skalarprodukte mit einem
Skalarakkumulator. Verifikation und funktionale Java-Wiedergabe verwenden
zusätzliche Matrixobjekte. Eine konkrete JVM-Speicherersparnis oder ein
asymptotischer Gewinn wurde nicht gemessen. Die Blockverifikation im
rechteckigen Test benötigt 755 Arbeitseinheiten.

## Zugang, Grenzen und Reproduktion

Der SDK-Einstieg `OperatorPlanSdk` bietet `spectral`, `verify`, `select`,
`verifyWorkspace` und `applyWorkspace`. Die Nutzung setzt konstante rationale
Einträge in `SymbolicLinearSystem.PolynomialMatrix` voraus. Der Prädiktor
unterstützt `Matrix`, `Identity`, `Sum` und geordnete `Product`-Knoten.
Die Matrixalgebra begrenzt Dimensionen auf 16, Ausdruckstiefe auf 32 und
Koeffizientenbreite auf 2048 Bits. Das SDK übersetzt `maximumStates` in die
Zellengrenze. Der Prüfer reserviert die zur Expansion und Blockprüfung
angeforderten Matrixzellen kumulativ. Die Zählung ist konservativ und kann
auch vor einer technisch möglichen JVM-Allokation stoppen. `maximumCandidates`
begrenzt die Zahl eingereichter Alternativen. `INVALID` bezeichnet eine
verletzte Gleichheit oder Formbedingung, `UNSUPPORTED` einen Eintrag oder
Knoten außerhalb des Fragments. `STOPPED` trägt den jeweiligen
Ressourcengrund. Jeder Erfolg durchläuft eine abschließende Abbruchprüfung.

Die fokussierten Tests laufen mit Java 25 im Repository-Stamm.

```sh
mvn -B -ntp -pl regelsuche-optimization-sdk -am \
  -Dtest=OccupiedWorkspaceTest,OperatorResidualPlansTest,OperatorPlanSdkTest,MatrixOperatorRulesTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
