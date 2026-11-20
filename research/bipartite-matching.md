# Endliche Hall-Defizienz in bipartiten Graphen

`BipartiteMatching` berechnet ein Matching maximaler Größe für einen
endlichen bipartiten Graphen. Das Zertifikat bindet das Matching und eine
linke Knotenmenge an den Graphen. Damit lässt sich die Anzahl der linken
Knoten, die in jedem Matching unzugeordnet bleiben müssen, unabhängig von
der Suche nachprüfen.

## Quelle und Beispiel

Eine Anfrage gibt die Größen der linken Seite \(U\) und der rechten Seite
\(V\) sowie die Kanten zwischen beiden Seiten an. Jede Seite verwendet
einen eigenen nullbasierten Indexraum. Die Anfrage behält isolierte Knoten
über die Seitengrößen bei, prüft die Endpunkte und speichert die Kanten
sortiert und ohne Wiederholungen. Gleiche Seitengrößen und dieselbe Kantenmenge
bilden dieselbe Quelle, unabhängig von der Reihenfolge oder Wiederholung
der Eingabekanten.

Im Graphen mit den vier linken Nachbarschaften `{0}, {0}, {0}, {1,2,3}`
haben beide Seiten vier Knoten. Dennoch lassen sich höchstens zwei linke
Knoten zuordnen. Die Menge \(W=\{0,1,2\}\) hat nur den Nachbarn \(0\) und
damit Defizienz zwei. Das Matching \(\{(0,0),(3,1)\}\) erreicht die obere
Schranke von zwei Zuordnungen.

## Suche und unabhängige Prüfung

Die Suche nimmt je einen linken Knoten in aufsteigender Reihenfolge hinzu.
Ausgehend vom neuen Knoten sucht sie in Breitensuche einen alternierenden
Pfad zu einem freien rechten Knoten. Ein solcher Pfad vergrößert das
Matching um eine Kante. Ein Pfad, der an einem zuvor verarbeiteten freien
linken Knoten beginnt, könnte den neuen linken Knoten nur als inneren
Knoten nutzen, wenn dieser bereits zugeordnet wäre. Deshalb reicht ein
Suchlauf pro neuem linken Knoten, um das Maximum für das jeweilige Präfix
zu erhalten.

Nach dem letzten Präfix markiert die Suche alle linken Knoten, die von
freien linken Knoten entlang alternierender Pfade erreichbar sind. Sie
bilden \(W\). Die erreichbaren rechten Knoten bilden genau \(N(W)\).
Jede ungematchte Kante aus \(W\) wird verfolgt, und der rechte Partner
eines gematchten Knotens in \(W\) wurde auf dem Weg zu ihm erreicht.
Ein freier rechter Knoten wäre das Ende eines vergrößernden Pfads.
Daher sind alle rechten Knoten in \(N(W)\) gematcht. Ihre Partner sind
genau die gematchten Knoten in \(W\), sodass
\(|W|-|N(W)|\) der Anzahl der freien linken Knoten entspricht.

Der Prüfer gleicht die erwartete Quelle mit der Quelle im Zertifikat
ab. Er prüft, dass alle Kanten des Matchings \(M\) im Graphen liegen und
paarweise verschiedene linke sowie verschiedene rechte Endpunkte haben.
Er bildet \(N(W)\) aus der vollständigen Kantenliste und prüft

\[
 |M|=|U|-|W|+|N(W)|.
\]

Die rechte Seite zählt die Knoten der Überdeckung
\((U\setminus W)\cup N(W)\). Jede Kante trifft diese Überdeckung, und die
disjunkten Kanten eines Matchings benötigen je mindestens einen ihrer
Knoten. Das vorgelegte Matching erreicht diese obere Schranke. Dasselbe
Überdeckungsargument für jede andere linke Teilmenge \(S\) ergibt
\(|S|-|N(S)|\le |U|-|M|\). Somit erreicht \(W\) die maximale Hall-Defizienz
\(|U|-|M|\). Bei einem Matching, das alle linken Knoten belegt, kann \(W\)
leer sein.

## Ressourcen und Reproduktion

Der mathematische Kern stellt `search` und `verify` bereit.
`BipartiteMatchingSdk` reicht Anfrage, Budget und Abbruchsignal weiter.
`maximumStates` begrenzt im SDK die Summe beider Seitengrößen und der
Zahl unterschiedlicher Kanten. Im Kern gilt zusätzlich eine Obergrenze
von einer Million für dieselbe Summe. Beide Aufrufe prüfen diese Grenze
vor der ersten größenabhängigen Allokation und rechnen Knotenarbeit
auch bei kantenlosen Graphen auf `maximumWork` an. Erreicht die Suche
eine Grenze oder wird sie abgebrochen, liefert sie kein Zertifikat.
Ein unterbrochener Prüflauf bestätigt keinen Beleg.

Die Prüfung liest die Kanten und das Zertifikat linear und benötigt
zusätzlichen Speicher linear in der Knotenzahl. Die Suche kann die Kanten
in mehreren Breitensuchen durchlaufen. Die Konstruktion der Anfrage
validiert und sortiert die Eingabekanten vor den budgetierten Aufrufen.
Die Anwendung verantwortet die Herkunft und Vollständigkeit der
gelieferten Kanten sowie die fachliche Deutung einer Zuordnung.

Die Tests enumerieren alle 689 Graphen mit bis zu drei Knoten pro Seite
und vergleichen die Defizienz jeweils mit einer vollständigen Enumeration
der linken Teilmengen. Weitere Kontrollen prüfen die lokale Engstelle,
das Umhängen einer bestehenden Zuordnung, manipulierte Belege und die
Ressourcengrenzen. Mit Java 25 lassen sich Kern- und SDK-Tests gemeinsam
ausführen.

```sh
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=BipartiteMatchingTest,BipartiteMatchingSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
