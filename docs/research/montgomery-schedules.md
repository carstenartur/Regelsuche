# Geprüfte Montgomery-Rechenpläne

Diese Entwicklerreferenz beschreibt die bedingte Korrektheit von
Reduktionsverschiebungen in endlichen ganzzahligen Rechenplänen. Der mathematische
Kern ist von Bouncy Castle und Eclipse unabhängig; das SDK reicht typisierte
Anfragen, Budgets und Abbruchsignale weiter.

## Vertrag

Ein Plan enthält benannte Eingaben mit vorzeichenbehafteten Intervallen,
`SHORT`-, `INT`- oder `LONG`-Worten, eine Folge typisierter Operationen und
explizite Ausgaben. Operanden verweisen nur auf Eingaben und frühere Schritte.
Addition, Subtraktion und Multiplikation müssen im angegebenen Java-Rechenwort
liegen. Ein `LONG`-Produkt bedeutet, dass die Operanden vor der Multiplikation
entsprechend erweitert werden. Ein nachträglicher Cast eines bereits
übergelaufenen `INT`-Produkts erfüllt diesen Vertrag nicht. `CAST` darf nur
werttreu sein. Der Consumer begründet die Eingabeintervalle und die Zuordnung
zu tatsächlichen Java-Operationen.

Für den Modulus `q` gilt `3 <= q < R/2`, `q` ungerade und `R=2^16` oder `2^32`.
Die Parameterprüfung verlangt `q * inverse = 1 (mod R)`; eine Primzahlannahme
ist nicht nötig. Der einzige bewusst umlaufende Schritt ist die Bildung des
vorzeichenbehafteten niederwertigen Wortes:

\[
u=\operatorname{signedLowWord}(a\,inverse),\qquad M(a)=(a-uq)/R.
\]

Damit ist der Zähler durch `R` teilbar und `M(a) = a R^{-1} (mod q)`.
Der Prüfer verwendet diese Skalierung an **jeder** Reduktion, auch bei
verschachtelten Reduktionen. Er prüft die Zählergrenzen vor dem Teilen und die
Ergebnisgrenzen vor dem Cast. Beim 32-Bit-Ergebnis ist auch der Java-Ausdruck
`(int)(numerator >>> 32)` korrekt: Wenn der exakte Quotient in `int` passt,
enthält das obere Wort genau dessen Zweierkomplementdarstellung.

Für `a` in `[l,h]` liegt der Zähler im Intervall
`[l-(R/2-1)q, h+(R/2)q]`. Erst wenn beide Grenzen im doppelt breiten Wort
liegen, wird das Ergebnisintervall mit aufgerundeter unterer und abgerundeter
oberer Grenze gebildet. Andere Operationen verwenden die gewöhnlichen exakten
Intervallregeln. Konservative Intervalle dürfen gültige Pläne ablehnen.

Eine beschränkte Polynomnormalform mit Koeffizienten modulo `q` prüft die
Ausgabeidentitäten. Der Prüfer benutzt die vorhandenen Monome und verwirft
unterschiedliche Ausgaben, ungültige Referenzen, Wortverengungen und Überläufe.
Das Zertifikat bindet die gesamte Anfrage und alle hergeleiteten Intervalle.
Die Wiederholungsprüfung berechnet diese Belege neu; sie ruft den Planer nicht
auf und vertraut keinen angegebenen Operationszahlen.

## Beobachtungen und Java-Grenze

`CONGRUENCE` besagt ausschließlich Gleichheit modulo `q`.
`CANONICAL_RESIDUE` besagt exakte Gleichheit nach dem mathematischen
`floorMod(value,q)`. Das ist keine Behauptung über rohe Zwischenwerte und auch
keine automatische Java-Umschreibberechtigung. Ein nachfolgendes Programm darf
andere Repräsentanten nur verarbeiten, wenn seine eigenen Zwischenwerte sicher
bleiben und seine abschließende Beobachtung ausschließlich von der Restklasse
abhängt. Eine Reduktion auf `[0,q]` ist noch keine eindeutige Normalisierung.

Quelltext-Bindungen, vollständige Aufrufer, Array-Aliase, Lese- und
Schreibreihenfolge, Exceptions, Speicherzugriffe und Nebenläufigkeit sind
Verantwortung des Consumers. Die bestehenden strikten Java-Gleichheitsprüfer
werden durch diese Fähigkeit nicht verändert. Eine konstante algebraische
Operationsfolge beweist keine konstante Laufzeit des JIT-erzeugten Codes.

## Zwei endliche Planungsschemata

Der Planer schlägt `M(sum a_i*b_i)` für `sum M(a_i*b_i)` vor. Der ursprüngliche
Summenworttyp ist ein expliziter Parameter. Das zweite Schema berechnet
Produkte von Paaren mit einem gemeinsamen, Montgomery-skalierten
Erweiterungskoeffizienten `zeta/R`:

\[
r_0=\sum_i\bigl(M(a_{i0}b_{i0})+M(zeta\,M(a_{i1}b_{i1}))\bigr),\quad
r_1=\sum_i\bigl(M(a_{i0}b_{i1})+M(a_{i1}b_{i0})\bigr).
\]

Mit `A00=sum a0*b0`, `A11=sum a1*b1` und
`Across=sum(a0*b1+a1*b0)` benötigt der erste Vorschlag drei Reduktionen:
`v=M(A11)`, `r0=M(A00+zeta*v)`, `r1=M(Across)`.
Der zweite verwendet `Aplus=sum(a0+a1)*(b0+b1)`,
`u=M(A00)`, `v=M(A11)`, `r0=u+M(zeta*v)`,
`r1=M(Aplus)-u-v`. Er benötigt vier Reduktionen, aber weniger gewöhnliche
Produkte. Jeder Vorschlag wird anschließend durch denselben allgemeinen
Prüfer zugelassen oder verworfen. Die Auswahl trifft keinen Laufzeit-Claim.

## Kontrollen und Reproduktion

Die Tests verwenden die Parameter von ML-DSA und ML-KEM aus
[Bouncy Castle bb81bfa](https://github.com/bcgit/bc-java/tree/bb81bfa75c9fa81c7fde467a6314374d22715472/core/src/main/java/org/bouncycastle/crypto).
ML-DSA wird für die Längen 4, 5 und 7 geprüft. Für den angegebenen
Aufruferbereich `a in [0,8380416]`, `b in [-34162429,34162429]` liegt der
längste Akkumulator in `[-2004067566133248,2004067566133248]`.
ML-KEM wird für die Längen 2, 3 und 4 sowie beide Paarpläne geprüft, mit
`a in [0,4095]`, `b in [0,3329]`, `zeta in [-1664,1664]`.
Diese Werte sind Eingabeannahmen des Kernversuchs, keine universelle Aussage
über beliebige Bouncy-Castle-Arrays.

Unabhängige konkrete Java-Auswertung vergleicht 9.600 feste und zufällige
Eingaben einschließlich Intervallgrenzen. Eine zusätzliche vollständige
16-Bit-Kontrolle prüft 65.536 niederwertige Worte gegen die Restklassengleichung
und das zertifizierte Intervall. Gegenproben verändern Skalierung, Quelle,
Beobachtungsvertrag und Belege sowie Überlauf-, Grad-, Arbeits- und Termgrenzen.
Zusammengesetzte ungerade Moduln werden gesondert geprüft.

```bash
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=MontgomeryScheduleTest,MontgomeryScheduleSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Der Vergleich ist eine Korrektheitskontrolle. Er ersetzt weder vollständige
Kryptotestvektoren noch Messungen von Schlüsselerzeugung, Signatur und KEM.
Die Arbeit ist durch das übergebene Budget begrenzt, zusätzlich durch 128
Eingaben, 4.096 Schritte, 8.192 Terme, Grad 64 und 64 Produkte je Vorschlag.
Der Planer verwendet dasselbe Arbeitsbudget und dieselbe Frist für Vorschlag
und Prüfung. Abbruch oder ausgeschöpfte Ressourcen liefern einen typisierten
Grund und die bis dahin verbuchte Arbeit. Ein Zertifikat setzt einen erfolgreich
beendeten Planerlauf voraus. Die Gegenproben prüfen beide
Planungsschemata mit 128 und 129 Eingaben sowie einen Abbruch an der letzten
Abfrage nach abgeschlossener Prüfarbeit.
