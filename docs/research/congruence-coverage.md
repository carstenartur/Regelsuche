# Endliche Überdeckungen durch aktivierte Kongruenzen

`CongruenceCoverage` im Modul `regelsuche-math-algorithms` entscheidet, welche
Elemente einer endlichen arithmetischen Progression von gegebenen Kongruenzen
überdeckt werden. Jede Kongruenz kann erst ab einem eigenen Zahlenwert aktiv
sein. Die Berechnung liefert die exakte Anzahl überdeckter Elemente und,
falls vorhanden, das kleinste nicht überdeckte Element. Ein gesonderter
Prüfaufruf rekonstruiert die Überdeckung aus der ursprünglichen Anfrage.
Für die Anzahl freier Elemente bietet der Kern außerdem überprüfbare
Schranken aus abgeschnittenen Inklusions-Exklusions-Summen.

Diese Fähigkeit ist unabhängig von Primzahlen und Greedy-Folgen. Sie eignet
sich beispielsweise für periodische Termine, Sperrzeiten und modulare Siebe.
Anwendungen bestimmen die Regeln, Aktivierungen und die fachliche Bedeutung
eines freien Elements.

## Vertrag und Zuständigkeit

Eine Anfrage enthält die Progression

\[
 n_k=b+s k,\qquad 0\le k<N,
\]

mit ganzzahligem Anfang \(b\), positiver Schrittweite \(s\) und nichtnegativer
Anzahl \(N\). Die Regel \((m,r,\tau)\) überdeckt genau die Werte mit

\[
 n_k\equiv r\pmod m\quad\text{und}\quad n_k\ge\tau.
\]

Der Modulus ist positiv; Anfang, Rest und Aktivierung dürfen negativ sein.
Alle Zahlen sind `BigInteger`. Die obere Schranke ist über die Anzahl der
Elemente definiert. Die Aktivierungsgrenze ist einschließlich.

| Teil | Verantwortung |
| --- | --- |
| `CongruenceCoverage` | Normalisierung, verallgemeinerter chinesischer Restsatz, exakte Vereinigungszählung, kleinster freier Wert, Schranken für die Anzahl freier Elemente, Zertifikate und deren Prüfung |
| `CongruenceCoverageSdk` | Übergabe der Anfrage und Abbildung von SDK-Budget und Abbruchsignal auf den Kern |
| Anwendung | Herkunft und Vollständigkeit der Regeln, fachliche Grenzen, Auswahl von Vermutungen und Interpretation des Ergebnisses |

Das SDK enthält keine Kopie des mathematischen Algorithmus. Es verwendet
`maximumStates` als Obergrenze für die Terme jeder Vereinigungsdarstellung
und für die gespeicherten Regelpositionen bei der Schrankenberechnung. Es reicht
`maximumWork`, `timeoutMillis` und `CancellationToken` weiter. Da die Anfrage
höchstens einen kleinsten Zeugen liefert, bestimmt `maximumCandidates` hier
keine zusätzliche Suchdimension. Der Kern verwendet die vorhandenen
`IterationLimits` und benötigt keine SDK-Abhängigkeit.

## Exakte Rechnung

### 1. Normalisierung auf den Index

Setze \(d=\gcd(s,m)\). Wenn \(d\nmid(r-b)\), kann die Regel kein Element
treffen. Andernfalls reduziert sie sich auf genau eine Indexkongruenz

\[
 k\equiv a\pmod {m/d},\qquad
 a\equiv\frac{r-b}{d}\left(\frac{s}{d}\right)^{-1}\pmod {m/d}.
\]

Für den reduzierten Modulus eins gilt die Kongruenz für jeden Index. Die
Aktivierung setzt die untere Indexgrenze

\[
 L=\max\left(0,\left\lceil\frac{\tau-b}{s}\right\rceil\right).
\]

Das erste passende \(f\ge L\) und die Periode \(h=m/d\) beschreiben somit
eine Menge

\[
 R(f,h)=\{f+jh:j\ge0\}\cap[0,N).
\]

Leere Mengen entfallen. Eine Menge mit nur einem Element wird für diese
endliche Anfrage einheitlich mit Periode \(N\) gespeichert. Diese
Normalform darf nicht als Periode einer unendlichen Fortsetzung verwendet
werden.

### 2. Schnitte und Vereinigungen

Der Schnitt zweier solcher Mengen ist entweder leer oder wieder von dieser
Form. Der verallgemeinerte chinesische Restsatz entscheidet die Verträglichkeit
auch bei nicht teilerfremden Perioden. Die erste Lösung wird mindestens auf
das Maximum der beiden Anfangsindizes angehoben und am gemeinsamen Ende
\(N\) abgeschnitten.

Eine vorzeichenbehaftete Termliste stellt den Indikator der bisher gebildeten
Vereinigung dar. Für eine weitere Menge \(B\) gilt exakt

\[
 \mathbf1_{U\cup B}=\mathbf1_U+\mathbf1_B-\mathbf1_{U\cap B}.
\]

Deshalb wird ein Term für \(B\) ergänzt und von jedem vorhandenen Term der
Schnitt mit \(B\) mit umgekehrtem Koeffizienten hinzugefügt. Gleiche Mengen
werden zusammengefasst und Koeffizienten null entfernt. Induktion über die
Eingaberegeln beweist, dass die resultierende Summe an jedem Index genau den
Überdeckungsindikator ergibt.

### 3. Zählen und kleinsten freien Index finden

Ein Term trägt in einem Präfix der Länge \(H\le N\) die Anzahl

\[
 C(f,h,H)=\begin{cases}
 0,&f\ge H,\\
 1+\lfloor(H-1-f)/h\rfloor,&f<H
 \end{cases}
\]

bei. Die gewichtete Summe dieser Anzahlen liefert die exakte
Vereinigungsgröße \(U(H)\). Die Anzahl freier Indizes ist \(H-U(H)\).
Wenn \(U(N)<N\), bestimmt binäre Suche das erste Präfix mit einem freien
Element. Dessen letzter Index ist der kleinste freie Index. Die Suche
enumeriert keine Folge von \(N\) Einzelpunkten.

## Ergebnis, Prüfung und Grenzen

`COMPLETE_COVER` bedeutet, dass alle Elemente der angegebenen endlichen
Progression überdeckt sind. Das gilt auch für die leere Progression.
`FOUND_UNCOVERED` enthält zusätzlich den kleinsten freien Index und über
`leastUncovered()` seinen Wert \(b+s k\). Beide Ergebnisse tragen ein
Zertifikat mit der ursprünglichen Anfrage, der normalisierten Termliste,
der Überdeckungsanzahl und dem optionalen Index.

`verify` verlangt die erwartete Anfrage zusätzlich zum Zertifikat. Es
prüft die Quellenbindung, baut die Vereinigungsdarstellung erneut aus den
Eingaberegeln auf, vergleicht die kanonischen Terme und zählt selbst nach.
Bei einem freien Zeugen prüft es zusätzlich die ursprünglichen Regeln und
die Vollständigkeit des Präfixes vor dem Zeugen. Ein übernommener
Ergebnisstatus oder eine passende Anzahl allein genügt nicht. Der Prüfer
verwendet dieselben exakten arithmetischen Bausteine; eine unabhängige
direkte Punktprüfung in den Tests ergänzt diese Prüfung.

Arbeitsbudget, Termzahl jeder Vereinigungsdarstellung, Zeit, Abbruchsignal und die
vorhandene Grenze für ganzzahlige Zwischenwerte beschränken den Lauf.
Ein nicht fertig gerechneter Suchlauf liefert `UNAVAILABLE` mit Grund und
Arbeitszähler, ohne ein positives oder negatives Überdeckungszertifikat.
Eine abgebrochene Prüfung bestätigt kein Zertifikat. Die gezählte Arbeit
gewichtet arithmetische Schritte nach der Wortlänge ihrer Operanden; sie
ist keine Messung einzelner Bitoperationen. Zeitgrenzen sind kooperative
Abbruchgrenzen zwischen Rechenschritten. Bei der Aufnahme einer Regel
liegen die bisherige und die nächste Vereinigungsdarstellung gleichzeitig
im Speicher; die Termgrenze gilt für jede der beiden Darstellungen.

Die Termzahl kann bei ungünstigen Regelfamilien exponentiell wachsen.
Große endliche Intervalle allein erzwingen dagegen keine Enumeration.
Das Ergebnis gilt für die mitgelieferten Regeln. Es beweist weder deren
Vollständigkeit in einer Anwendung noch eine Aussage über alle Primzahlen
oder eine unendliche Progression.

## Bonferroni-Schranken für freie Elemente

Für die normalisierten Treffermengen \(A_i\) sei \(S_j\) die Summe der
Größen aller Schnitte von jeweils \(j\) verschiedenen Regelpositionen.
Leere Treffermengen können entfallen. Wiederholte Regeln behalten ihre
einzelnen Positionen. Mit \(S_0=N\) gilt für die Teilsumme

\[
 T_k=\sum_{j=0}^{k}(-1)^j S_j.
\]

Ein freier Index trägt zu jeder Teilsumme genau eins bei. Trifft ein
Index \(t>0\) Regeln, beträgt sein Beitrag \((-1)^k\binom{t-1}{k}\) für
\(k<t\) und null für \(k\ge t\). Ungerade Ordnungen liefern daher
untere Schranken und gerade Ordnungen obere Schranken für die Anzahl
freier Elemente.

`bounds` berechnet alle Schichtensummen bis zur angeforderten Ordnung
oder bis zur Anzahl wirksamer Regeln. Die Rechnung nutzt dieselben
Indexkongruenzen, Schnitte und Zählformeln wie die exakte Überdeckung.
Eine Tiefensuche hält den aktuellen Schnittpfad und überspringt
Erweiterungen leerer Schnitte. Die Anzahl der berechneten Schnitte
kann mit der Ordnung kombinatorisch wachsen. Arbeitsbudget, Termgrenze,
Zeit und Abbruchsignal beschränken auch die Schrankenberechnung. Bei
einem Abbruch liefert sie `UNAVAILABLE` ohne Zertifikat.

Das Intervall beginnt bei \([0,N]\). Für jede vollständige Schicht
wird die passende Grenze verschärft, sofern ihre Teilsumme eine
bessere Schranke liefert. Bei Ordnung null bleibt \([0,N]\), solange
eine Regel mindestens ein Element trifft. Sobald alle wirksamen Regeln
einbezogen sind, geben beide Endpunkte die exakte Anzahl freier
Elemente an. Das gilt auch bei null wirksamen Regeln.

Das Zertifikat bindet die ursprüngliche Anfrage und die angeforderte
Ordnung. Es enthält die Summen \(S_1,\ldots,S_k\) und beide Endpunkte.
`verifyBounds` prüft die Bindung und berechnet Summen und Endpunkte
erneut. Für einen kleinsten freien Wert steht `search` zur Verfügung.

### Kontrolle über eine Periode aus acht Primzahlen

Auf der Progression \(0,\ldots,N-1\) mit \(N=35\,336\,848\,261\)
streichen wir ab null alle Vielfachen der Primzahlen
\(11,13,17,19,23,29,31,37\). Die exakte Vereinigungsdarstellung hat
255 Terme und überschreitet die im Test gesetzte Grenze von 128.
Unter derselben Grenze ergeben sich folgende Schranken.

| Ordnung | Untere Schranke | Obere Schranke |
| --- | ---: | ---: |
| 1 | 20 617 871 161 | 35 336 848 261 |
| 3 | 22 976 886 861 | 23 236 997 481 |
| 8 | 22 992 076 800 | 22 992 076 800 |

Die unabhängige Kontrolle zählt pro Primzahl \(p\) genau \(p-1\)
freie Restklassen. Über die volle Produktperiode gibt es deshalb
\(\prod_p(p-1)=22\,992\,076\,800\) freie Elemente. Der Kerntest
prüft die zwei unteren Schranken, die exakte Ordnung und die
Ablehnung geänderter Aktivierungen, Ordnungen, Schichtensummen und
Endpunkte. Der alternierende Baum in
[OpenAI Math, Familie 021, Abschnitt 3](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/A-quadratic-bound-for-Jacobsthals-function-September-25-2026/build/sections/tree.tex#L29-L64)
gab den Anstoß zur Anwendung der klassischen endlichen
Bonferroni-Ungleichungen. Die punktweise Rechnung oben begründet
das Zertifikat vollständig.

## Zweite Anwendung: periodische Termine

Betrachte eine Billion Termine \(n_k=2+6k\). Die Regeln

\[
 n\equiv2\pmod{12},\ n\ge2,
 \qquad
 n\equiv8\pmod{12},\ n\ge20
\]

sperren alle Termine außer \(n_1=8\). Eine zusätzliche Regel
\(n\equiv1\pmod4\) trifft wegen des gemeinsamen Teilers keinen Termin.
Wird die zweite Aktivierung auf acht gesetzt, ist die ganze Anfrage
überdeckt. Der SDK-Test prüft beide Varianten und verwirft das Zertifikat
der ersten Variante bei der veränderten Anfrage.

Weitere Kerntests vergleichen kleine Anfragen mit einer direkten
Punktenumeration, einschließlich negativer Werte, nicht teilerfremder
Moduln, Aktivierungsgrenzen, doppelter Regeln und leerer Intervalle. Ein
dyadisches Beispiel über einer Billion Indizes prüft einen weit entfernten
kleinsten Zeugen; ein vollständig überdecktes Intervall mit \(10^{100}\)
Indizes prüft die Unabhängigkeit von einer Enumeration. Manipulierte
Zertifikate sowie ausgeschöpfte Budgets und Abbrüche sind Negativkontrollen.

## Reproduktion

Mit Java 25 und Maven 3.9.9 oder neuer aus der 3.9-Reihe im Repository-Stamm:

```bash
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=CongruenceCoverageTest,CongruenceCoverageSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Der Primachsenraum-Consumer hält Greedy-Erzeugung, Sättigungszeiten,
Vermutungen und Referenzprüfungen in seinem eigenen Repository. Er bezieht
diese Fähigkeit über das Maven-Artefakt `regelsuche-optimization-sdk` und
die transitive Abhängigkeit `regelsuche-math-algorithms`.
