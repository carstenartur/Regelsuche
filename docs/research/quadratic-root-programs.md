# Geprüfte Rechenpläne für quadratische Körperwurzeln

Die Quell-API `power.QuadraticRootSdk` sucht gemeinsame Potenzen in einem
ausführbaren skalaren Wurzelprogramm und lässt den vollständigen Kandidaten
unabhängig prüfen. Sie gehört zu `0.5.4-SNAPSHOT`; das veröffentlichte SDK
0.5.3 enthält sie nicht. Java-Quelltexte, Bindungen, Seiteneffekte und die
Ersetzung selbst bleiben Aufgaben des Consumers.

## Eingabe und Beobachtung

Ein `QuadraticRootContract` bindet eine unabhängig bestimmte Quellidentität,
den beobachteten Modul samt exaktem Primzahlzeugnis, das ursprüngliche
`QuadraticRootProgram` und eine ausdrückliche Ausgabebeobachtung. Das Programm
besteht aus skalaren Registern, einer Gleichheitsverzweigung und zwei
geordneten Ausgabepaaren. Der Aufrufer liefert keinen Zielrechenplan.

Der mathematische Bereich ist fest:

- bewiesene Primzahl `p ≡ 3 (mod 4)` und Erweiterungsbasis `i² = −1`;
- kanonische Eingaben `0 ≤ a,b,g < p`, dabei `b ≠ 0`;
- Normgleichung `g² = a² + b² (mod p)`.

`EXACT_PAIR` verlangt dieselben beiden Koordinaten wie das ursprüngliche
Programm. `UP_TO_JOINT_SIGN` erlaubt eine gemeinsame Negation. Eine
unabhängige Vorzeichenänderung nur einer Koordinate ist nicht freigegeben.
Der Verifier darf eine exakte Anfrage nicht auf die schwächere Beobachtung
zurückstufen. Der aufrufende Compiler muss sämtliche Bereichsbedingungen
beweisen oder tatsächlich ausgeführte Bedingungen mit unverändertem
Originalpfad erzeugen.

## Suche und unabhängige Prüfung

Aus dem ursprünglichen Programm gewinnt die Suche die Potenz und ihren
reziproken Bedarf. `SharedPowerSearch` sucht gemeinsame Zwischenwerte mit
expliziter Quadratbeobachtung. Anschließend werden signierte Permutationen
des Koordinatenpaares je Verzweigungsarm betrachtet. Eine mögliche
gemeinsame Vorzeichennormalisierung ist Teil des ausführbaren Programms.
Die Auswahl einer Permutation ist ein Suchergebnis.

Die Basiserkennung erkennt Multiplikation mit eins, Addition oder
Subtraktion von null und doppelte Negation an Wurzel- und Guardregistern.
Das Quadrat im Vergleich erkennt sie als Quadratoperation, Multiplikation
der Wurzel mit sich selbst oder Potenz mit Exponent zwei. Der Beleg bindet
weiterhin das unveränderte Original. Die unabhängige Prüfung muss beide
vollständigen Programme erklären.

`SharedPowerSearch.portfolio` behält unterschiedliche geprüfte Potenzpläne.
`QuadraticRootSearch.portfolio` beziehungsweise die dünne SDK-Fassade
`QuadraticRootSdk.portfolio` liefern daraus unveränderliche Listen
vollständiger, unabhängig geprüfter Programme. Der globale Kandidatenetat
wird zwischen den Potenzplänen verteilt. Für kleine Etaten wird der
günstigste Plan zuerst untersucht; es gibt keinen Vollständigkeitsanspruch.
Bei den besuchten Ausgängen werden nur solche Kombinationen verworfen,
die auf beiden Pfaden mindestens ebenso viele Negationen und
Charakterauswahlen benötigen wie eine andere Kombination.

Die voreingestellte Ordnung bewertet den vollständigen Gleichheits- und
Ungleichheitspfad jeweils einmal. Sie zählt die binäre Potenzarbeit nach
den angegebenen Gruppenoperationsgewichten, einen Punkt je Potenzaufruf,
Guard, Addition, Subtraktion oder Negation und acht Punkte je
Charakterauswahl. Der Einzelaufruf `search` liefert nur eine gegenüber
dem Original streng günstigere Variante; andernfalls lautet der Grund
`NO_COST_IMPROVEMENT`. Das Portfolio hält auch andere geprüfte Varianten
für die eigene Kostenbewertung des Consumers bereit. Java-Schutzprüfungen,
Speicherallokation und konkrete JDK-Potenzalgorithmen sind in dieser
mathematischen Voreinstellung nicht bepreist.

Beim 381-Bit-Original mit dem Nenner `2*r` und den Gewichten `(1,1,10)`
liefert der Etat von 64 Ausgabepaaren vier geprüfte Programme. Der
Strukturwert sinkt von 1855 auf 1235. Die Suche benötigt 1003408 statt
der historischen 504157 Arbeitseinheiten; die unabhängige Wiederprüfung
421108 statt 421099. Die zusätzlichen Alternativen kosten Sucharbeit.
Diese Zahlen beschreiben genau diese Quelle und Konfiguration und
belegen keinen Laufzeitgewinn des erzeugten Java-Codes.

Die Prüfung rekonstruiert sowohl das Original als auch den Kandidaten.
Mit `X=(a+g)/2` und `H=b/2` gilt unter den Eingabebedingungen

```text
b = 2H
a = X − H²/X
g = X + H²/X
(a−g)/2 = −H²/X.
```

`X` und `H` sind Einheiten. Eine beschränkte Laurent-Normalform speichert
Monome in diesen beiden Variablen mit beliebig großen, aber begrenzten
Exponenten. Die vier möglichen Paare der quadratischen Charaktere
`(χ(X),χ(H))` erlauben die Reduktion modulo `(p−1)/2`. Der Verifier prüft
Verzweigungen, die Wurzelgleichungen `A²−B²=a` und `2AB=b` und die
angeforderte Gleichheit der Ausgaben in jedem Fall. Ein nicht entscheidbarer
Zweig liefert keine Freigabe.

Die Suche ist endlich und durch Arbeits-, Speicher-, Zeit- und
Kandidatenlimits begrenzt. Sie ist keine globale Minimierung beliebiger
Java-Programme oder Additionsketten. Ein Abbruch liefert keine geprüfte
Ersetzung. Das gemeldete Modell für Potenzen ist von der tatsächlichen
Laufzeit des Consumers zu unterscheiden.

## Exakte Vorzeichenwahl

Das übliche ursprüngliche Verfahren wählt den quadratischen Rest unter
`(a+g)/2` und `(a−g)/2` und potenziert ihn mit `(p+1)/4`. Seine nichtnull
reelle Ausgabekoordinate ist damit selbst ein quadratischer Rest. Wegen
`χ(−1)=−1` hat genau eine der beiden gemeinsamen Orientierungen diese
Eigenschaft. Ein binärer Jacobi-Schritt kann diese Orientierung ohne weitere
modulare Potenzierung oder Inversion wählen.

Diese Eigenschaft wird am gelieferten Originalprogramm geprüft. Sie ist
keine vorausgesetzte Kennzeichnung eines als Wurzelverfahren benannten
Aufrufs. Bei `EXACT_PAIR` muss auch die normalisierte Ausgabe mit beiden
Originalkoordinaten übereinstimmen. Der Consumer muss die tatsächliche
Java-Implementierung des Charakteroperators und ihre Aufrufbedingungen
separat an die geprüfte Operation binden.

## Exakte Primzahlbelege

`PrimeEvidenceVerifier` prüft gegen einen separat angegebenen erwarteten
Modul. `SmallPrime` verwendet deterministische Prüfung im positiven
`int`-Bereich. `Lucas` verlangt die vollständige, rekursiv belegte
Faktorisierung von `n−1` und einen Zeugen, dessen Ordnung sämtliche
Primfaktoren enthält.

`EllipticOrder` verlangt eine nichtsinguläre Kurve modulo `n`, einen
kanonischen affinen Punkt und eine belegte Primzahl `r` mit
`2*floor(sqrt(n))+1 < r < n` und `[r]P=O`. Jede Division bei der
Punktaddition verlangt eine geprüfte Einheit; nicht eindeutig reduzierbare
Ausnahmefälle werden abgelehnt. Für jeden Primteiler `ℓ` von `n` hat der
reduzierte affine Punkt dann Ordnung `r`. Eine Kurve über `Fℓ` hat höchstens
`2ℓ+1` Punkte. Ein Teiler `ℓ ≤ floor(sqrt(n))` wäre damit unmöglich,
also ist `n` prim. Dieses Argument benötigt keine vermutete Primzahl und
keine vorausgesetzte Kurvenordnung.

Der Katalog enthält Daten für einen 381-Bit-Modul und dessen Lucas-belegten
255-Bit-Untergruppenprimteiler. Ein Katalogtreffer ist kein Beweis: dieselbe
unabhängige Prüfung bleibt erforderlich. Andere große Moduln benötigen
ebenfalls ein akzeptiertes Zeugnis; der Katalog ist kein allgemeiner
Primzahlbeweiser. Klassen-, Methoden- und Quelltextnamen beeinflussen die
mathematische Freigabe nicht.

## Consumer-Verantwortung

Eine mathematische Paaridentität beweist noch keine Java-Ersetzung. Ein
Consumer muss insbesondere die konkreten Argumentversionen, tatsächlichen
Konstanten und Modulooperationen, die Originalverzweigung, die geordneten
Ausgaben, Ausnahmen und Seiteneffekte binden. Wenn ein nachfolgender Aufruf
Objektidentität beobachten kann, genügt numerische Gleichheit nicht.
Abhängige Quelldefinitionen müssen vor Apply erneut geprüft werden.

Für nichtkanonische, nullwertige oder normwidrige Eingaben kann ein
bewachter schneller Pfad zum unveränderten Original zurückfallen. Das
Original für reelle und Null-Eingaben bleibt außerhalb des nichtreellen
Vertrags. Ein erfolgreicher mathematischer Test ersetzt weder diese
Quellprüfung noch die Überprüfung des tatsächlich erzeugten Java-Codes.

## Reproduktion

Im Java-25-Checkout:

```bash
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=PrimeEvidenceTest,QuadraticRootTest,QuadraticRootSdkTest test
```

Die regulären Modul-POMs kompilieren die hinzugefügten Haupt- und
Testquellen außerdem mit `-Xlint:all -Werror`. Die fokussierten Tests
trennen algebraische Äquivalenz, konkrete Ausführung, verfälschte Zeugen
und Ressourcenabbruch. Die Quellcode- und Laufzeitqualifikation gehört
zum jeweiligen Consumer.

Siehe auch: [Gemeinsame Potenzen](shared-power-relations.md) und
[Strategien-Index](strategies.md).
