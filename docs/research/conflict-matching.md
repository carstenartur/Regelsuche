# Matching-Schranken mit unvereinbaren Kanten

## Fragestellung und Vertrag

Ein gewöhnliches bipartites Matching kennt die Kapazität eins jedes linken und
rechten Knotens. Manche Anwendungen wissen zusätzlich, dass zwei bestimmte
Zuordnungen nicht gleichzeitig gelten können. `ConflictMatching` nimmt solche
Konflikte ausdrücklich entgegen und erzeugt eine exakte obere Schranke für die
Größe jedes mit ihnen verträglichen Matchings.

Der allgemeine mathematische Code liegt in `regelsuche-math-algorithms`.
`ConflictMatchingSdk` reicht lediglich Budget und Abbruchsignal weiter. Die
Anwendung muss die Vollständigkeit ihres Graphen, die Kapazitäten und jeden
zusätzlichen Konflikt selbst begründen. Insbesondere darf sie keine tatsächlich
mögliche Kante weglassen, um eine kleinere Schranke zu erhalten. Das Weglassen
eines wahren Konflikts kann dagegen nur eine schwächere Schlussfolgerung liefern.

Die Kantennummern beziehen sich auf `BipartiteMatching.Request.edges()` **nach**
dessen Sortierung und Entfernung doppelter Kanten. Konflikte sind ungeordnete
Paare verschiedener solcher Nummern. Der Request sortiert und dedupliziert sie;
Schleifen und ungültige Indizes sind unzulässig. Isolierte Knoten bleiben erhalten.

## Kurzer, unabhängig prüfbarer Beleg

Für jeden linken Knoten sei `a_l`, für jeden rechten Knoten `b_r` und für jedes
Konfliktpaar `c_j` ein nichtnegatives rationales Gewicht gegeben. Der Prüfer
verlangt für jede Kante `e=(l,r)`:

```text
a_l + b_r + Summe(c_j für Konfliktpaare j, die e enthalten) >= 1.
```

Summiert man diese Ungleichungen über ein verträgliches Matching, wird jeder
linke Knoten, jeder rechte Knoten und jedes Konfliktpaar höchstens einmal
benutzt. Wegen der Nichtnegativität der Gewichte gilt deshalb

```text
|M| <= B = Summe(a_l) + Summe(b_r) + Summe(c_j).
```

Die ganzzahlige Folgerung lautet `|M| <= min(nL,nR,floor(B))`; mindestens
`nL-min(nL,nR,floor(B))` linke Knoten bleiben unzugeordnet. `B` selbst bleibt
als exakte rationale Zahl in der Ausgabe erhalten. Ein externer Beleg muss
nicht optimal sein, um gültig zu sein.

`ConflictMatchingVerifier` prüft Quelle, Form, Vorzeichen und jede einzelne
Kantenungleichung direkt. Er baut kein LP auf und ruft keinen LP-Löser auf.
Er vertraut auch nicht einer vom Erzeuger behaupteten Schranke. Ein geändertes
Konfliktpaar, eine umgehängte Kante oder ein kleineres Gewicht wird gegen die
erwartete Originalquelle geprüft.

Eingereichte Zähler und Nenner müssen echte `BigInteger`-Werte sein. Unterklassen
werden vor jeder numerischen Methode abgewiesen: Überschriebene Methoden
könnten sonst beispielsweise die abschließende Abrundung verfälschen. Zwei
unabhängig beigesteuerte Regressionen prüfen diesen früheren Fehlerfall.

## Begrenzter Erzeuger

Der Erzeuger benutzt den bestehenden [exakten LP-Suchpfad](linear-program-search.md)
mit einer Variablen `x_e` je Kante:

```text
maximiere Summe(x_e)
Summe(x_e am linken Knoten l)  <= 1
Summe(x_e am rechten Knoten r) <= 1
x_i + x_j                     <= 1  für jedes Konfliktpaar
-x_e                          <= 0  für jede Kante
```

Die letzte Zeile ist notwendig, weil der LP-Vertrag freie Variablen zulässt.
Aus dem geprüften Dualoptimum übernimmt der Erzeuger die Gewichte der Knoten-
und Konfliktzeilen. Die nichtnegativen Dualgewichte der letzten Zeilen werden
weggelassen; die übrigen Gewichte decken dadurch jede Kante mit mindestens
eins. Vor Ausgabe wird der graphbezogene Beleg nochmals unabhängig geprüft.
Ein Graph ohne Kanten benötigt keinen LP-Aufruf und erhält Nullgewichte.

Dies ist eine Anwendung klassischer LP-Dualität auf zusätzliche Konflikte.
Der Bezug zu Familie 380 von `openai/math` ist der bereits übernommene Vertrag
für exakte primal-duale Zertifikate, nicht ein neuer Matching-Satz und nicht
die Implementierung des dort beschriebenen stark polynomialen LP-Algorithmus.

## Kosten und Grenzen

Die Konstruktion der unveränderlichen Requests liegt wie beim bestehenden
Matching-API außerhalb des Aufrufbudgets. Alle Suchphasen, die verschachtelte
LP-Suche und die abschließende unabhängige Prüfung teilen danach einen
Arbeitsvorrat. Bereits verbrauchte verschachtelte Arbeit wird vor der erneuten
Abbruchprüfung angerechnet. Ein letzter Abbruch oder eine Fristüberschreitung
veröffentlicht weder Teilbeleg noch Teilgrenze.

Der Termvorrat zählt logische Speicherzellen für Quelle, Gewichte, dichte
LP-Daten und Tableau, mit der bestehenden harten Millionengrenze. Jede dichte
Allokation wird vorher geprüft; während des verschachtelten Aufrufs bleibt
die ursprüngliche Graphquelle reserviert. Unveränderliche Kopien werden beim
Aufbau mitgerechnet. Dies ist keine genaue JVM-Byte- oder Echtzeitgarantie.
Rationale Zwischenwerte unterliegen zusätzlich der bestehenden Bitgrenze.

Der Simplex kann teuer sein. Für große konfliktfreie Graphen bleibt das
spezialisierte [bipartite Matching](bipartite-matching.md) die passende Methode.
Die Konfliktrelaxation kann trotz erfolgreichem Lauf eine echte ganzzahlige
Defizienz übersehen; `VERIFIED` bedeutet eine gültige obere Schranke, nicht
eine exakte Lösung des ganzzahligen Konfliktproblems. `INVALID` kennzeichnet
ungültig eingereichte Evidenz; `UNAVAILABLE` bleibt mathematisch offen.

## Reproduzierte Kontrollen

| Eingabe | Gewöhnliches Matching | Neue obere Schranke | Unabhängiges ganzzahliges Optimum |
| --- | ---: | ---: | ---: |
| Zwei disjunkte Kanten mit gegenseitigem Konflikt | 2 | 1 | 1 |
| Zusätzliche verträgliche Alternative | 2 | 2 | 2 |
| Drei disjunkte, paarweise unvereinbare Kanten | 3 | 1 aus `B=3/2` | 1 |
| Vier disjunkte, paarweise unvereinbare Kanten | 4 | 2 aus `B=2` | 1 |

Der letzte Fall bewahrt bewusst die sichtbare Lücke der Relaxation. Die Tests
enumerieren außerdem alle 113 Kombinationen eines 2×2-Graphen mit sämtlichen
möglichen Konfliktmengen. Ein unabhängiger Teilmengenprüfer bestätigt in jedem
Fall, dass keine echte verträgliche Zuordnung unterschätzt wird; ohne Konflikte
stimmt die Schranke mit dem Optimum überein. Weitere Kontrollen betreffen
isolierte Knoten, manipulierte Quellen und Gewichte, kanonische Indizes,
die exakt nötige Arbeit gegenüber einer Einheit weniger sowie Abbruch beim
letzten Prüfschritt. Der SDK-Test verlangt denselben Beleg und dieselben
Arbeitskosten wie der direkte Kernaufruf.

```bash
mvn -B -ntp -pl regelsuche-optimization-sdk -am \
  -Dtest=ConflictMatchingTest,ConflictMatchingAdversarialTest,ConflictMatchingSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Die regulären Maven-Phasen kompilieren die neuen Produktions- und Testdateien
zusätzlich mit `-Xlint:all -Werror`. Der vollständige betroffene Reaktor läuft mit
`mvn -B -ntp -pl regelsuche-optimization-sdk -am verify`.

## Primzahlbezogener Consumer

Primachsenraum kann Zuordnungen ausschließen, wenn eine Blockerannahme einen
früheren zwingenden Greedy-Punkt benötigt, den eine zweite Blockerannahme in
ihre global fehlende Restklasse legt. Diese Übersetzung und die dazu nötigen
kanonischen Quellenprüfungen gehören in den Consumer. Der Regelsuche-Kern
enthält keine Primzahlen, Katalogwerte oder ausgewählten Zielkandidaten.
Auch ein strikt stärkerer endlicher Beleg beweist noch keine uniforme
Greedy-Frist. Die genaue Instanz und deren authentifizierte Quellrechnung
müssen separat reproduziert werden.
