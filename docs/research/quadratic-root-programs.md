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

## Exakte Beobachtung nach gemeinsamer Vorzeichenwahl

Die Suche mit `UP_TO_JOINT_SIGN` darf eine andere Zwischenwurzel liefern.
`QuadraticRootSdk.verifyNormalized` prüft, ob beide Wurzeln nach einem
bestimmten nachfolgenden Schritt dasselbe geordnete Koordinatenpaar ergeben.
Der Aufrufer rekonstruiert dafür neben dem Wurzelvertrag unabhängig einen
`QuadraticRootNormalization.Contract` mit eigener Quellidentität, Modul und
Vorzeichenwähler. Der zusammengesetzte Beleg bindet diesen Vertrag an den
Wurzelbeleg. Der Kern gleicht beide Verträge und den gemeinsamen Modul ab
und prüft Primzahlzeugnis, ursprüngliches Programm, Kandidat und Wähler
innerhalb desselben Etats.

Der Normalisierer behält das kanonische Paar, wenn dessen Vorzeichenbit dem
unveränderten Zielbit aus `{0,1}` entspricht. Andernfalls negiert er beide
Koordinaten modulo der belegten Primzahl. Der Wähler enthält neun Bits.
Sein Bit an Position `3 * realClass + imaginaryClass` gehört zum jeweiligen
Koordinatenpaar. Die Klassen sind null (0), gerade und von null verschieden
(1) sowie ungerade (2). Die Fabriken `realFirst()` und `imaginaryFirst()`
verwenden die Parität der ersten von null verschiedenen Koordinate in der
angegebenen Reihenfolge.

Bei ungeradem Modul hat `p−x` für jedes `0<x<p` die entgegengesetzte
Parität. Somit vertauscht die kanonische Negation die Klassen 1 und 2 und
erhält die Klasse 0. Der Prüfer verlangt für jedes der acht von (0,0)
verschiedenen Klassenpaare einen Bitwechsel unter gemeinsamer Negation.
Damit wird von den beiden Orientierungen für beide möglichen Zielbits genau
dieselbe ausgewählt. Das Nullpaar bleibt bei jeder Wahl gleich. Die acht
Zellen bilden vier Zweiergruppen. Einschließlich der freien Wahl am Nullpaar
erfüllen genau `2⁵ = 32` der 512 Wähler diese Bedingung.

Ein unabhängiger Test führt alle 512 Wähler auf allen Paaren modulo 3 mit
beiden Zielbits aus und vergleicht das Ergebnis mit dem Prüfer. Über den
Primkörpern 3, 7, 11 und 19 vergleicht ein zweiter Test alle 464 zulässigen
nichtreellen Eingabetripel mit der ursprünglichen Zweigformel. Er erhält
tatsächlich verschiedene Zwischenwurzeln und prüft die exakten normierten
Ergebnisse für beide Koordinatenprioritäten und Ziele. Die Multiplikation
mit allen Faktoren des jeweiligen Erweiterungskörpers umfasst auch das
Nullpaar und Ergebnisse auf beiden Achsen.

Die Freigabe gilt für diese mathematische Beobachtung. Ein Compiler muss
die tatsächliche Bitberechnung, den unveränderten Zielwert, die gemeinsame
Negation sowie sämtliche Verwendungen des Zwischenwerts an die Quellen
binden. Liegen dazwischen etwa Multiplikationen, muss er deren Verträglichkeit
mit gemeinsamer Negation und die Gleichheit der übrigen Operanden nachweisen.
Die Kosten der erzeugten Ausführung wurden in diesem Versuch nicht gemessen.

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

Bleibt das Portfolio nach einer unvollständigen Paaraufzählung leer, lautet
der Grund `CANDIDATE_LIMIT`. Dies unterscheidet ein zu kleines Suchbudget
vom vollständigen negativen Ergebnis `NO_PROVED_BRANCH_PAIR`. Bereits
vollständig geprüfte Funde bleiben bei einem kleinen Kandidatenetat
`VERIFIED`; daraus folgt keine globale Minimalität. Fehler im ursprünglichen
Wurzelvertrag behalten ihren eigenen Ablehnungsgrund.

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
Strukturwert sinkt von 1855 auf 1235. Die frisch reproduzierte Suche
benötigt weiterhin 1003408 statt der historischen 504157 Arbeitseinheiten;
die unabhängige Wiederprüfung weiterhin 421108 statt 421099. Die zusätzlichen
Alternativen kosten Sucharbeit. Die geänderte konkrete Jacobi-Auswertung
ändert diese symbolischen Such- und Prüfzahlen nicht.
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

### Begrenzte allgemeine Jacobi-Auswertung

`QuadraticCharacter.jacobi` behält vorzeichenbehaftete Zähler, positive
ungerade Nenner einschließlich zusammengesetzter Zahlen und den Wert eins
für Nenner eins. Nach der kanonischen Anfangsreduktion entfernt der
Mehrwortpfad Zweierpotenzen und wendet beim Vertauschen quadratische
Reziprozität an. Bei einem Bitlängenabstand von mindestens 32 reduziert
er den größeren ungeraden Operanden modulo dem kleineren; sonst subtrahiert
er einmal. Beide Schritte erhalten das Jacobi-Symbol. Der feste Abstand
ist eine allgemeine Operandenbedingung, keine Modulus- oder Quelltexterkennung
und kein Anspruch auf eine optimale Schwelle.

Der anschließende positive `long`-Pfad verwendet den euklidischen Rest.
**Beide** aktiven Operanden müssen höchstens 63 Bit besitzen. Nach einer
Subtraktion kann der Zähler größer als der Nenner sein; ein Test nur des
Nenners würde große Zähler abschneiden. Die Rückgabe behält die abschließende
Prüfung auf ggT eins und liefert bei Nicht-Einheiten null. Die mathematisch
bereits bewiesene Primkörper-Einheiten-Spezialisierung eines Consumers ist
von dieser allgemeinen API zu unterscheiden.

Der optionale `CertificateWork`-Etat wird vor jeder Phase belastet; jede
Belastung prüft auch Abbruchsignal und Zeitgrenze. Mit
`w(x)=1+floor(bitLength(abs(x))/64)` gilt das explizite Modell:

| Phase | Arbeitseinheiten vor der Operation |
|---|---:|
| Anfangsreduktion des gegebenen Zählers modulo Nenner | `1 + w(Zähler)*w(Nenner)` |
| Mehrwortrunde: Verschiebung, Vorzeichen und Vergleich | `1 + max(w(a),w(n))` |
| Restreduktion nach einer großen Bitlängendifferenz | `1 + w(a)*w(n)` |
| Subtraktion bei vergleichbaren Operanden | `1 + max(w(a),w(n))` |
| Eine positive native euklidische Runde | `2 = 1 + 1*1` |

Bereichs- und exakte Klassenprüfung bleiben vor dem Etat in ihrer bisherigen
Reihenfolge. Auch Zähler null, Vielfache des Nenners und Nenner eins bezahlen
die Anfangsreduktion und beachten einen bereits erschöpften oder abgebrochenen
Etat. Für negative Zähler zählt die Bitlänge des Betrags, insbesondere an
Grenzen wie `-2^63`. Der öffentliche Aufruf ohne Etat bleibt verfügbar.
Dies sind deterministische Ressourcenmodelle, keine CPU-Zeit, Allokationsbytes
oder JDK-internen Operationszähler.

`QuadraticCharacterTest` verwendet unabhängige Euler-Orakel mit exakt
belegten großen Primzahlen und multiplikative Orakel bekannter
Faktorisierungen bis zur 4096-Bit-Grenze. Er prüft Vorzeichen, null,
Nenner eins, Nicht-Einheiten, 62/63/64/65-Bit-Übergänge, Abstand 31/32/33,
große Zweierpotenzen und den Fall eines 64-Bit-Zählers über einem kleinen
Nenner nach Subtraktion. Exakter Etat gegen eine Einheit weniger,
Abbruch an jeder belasteten Phase und die unveränderte Fehlerreihenfolge
sind eigene Gegenproben. Der Algorithmus allein belegt noch keine
Beschleunigung eines vollständigen erzeugten Wurzel- oder Hash-Aufrufs.

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

### Weitere Belege bereitstellen

`PrimeEvidenceProvider.supplied(...)` nimmt bis zu 64 unterschiedliche,
unveränderlich indizierte Belege entgegen und verwendet den Katalog für
nicht belegte Moduli. Die Auswahl ist ausdrücklich untrusted: Der
aufgerufene Prüfer muss den Beleg weiterhin gegen den tatsächlich
beobachteten Modul prüfen. Doppelte Moduli werden abgelehnt.

Das SDK importiert solche Daten mit
`PrimeEvidenceJson.read(expectedModulus, bytes, budget, cancellation)`.
Der versionierte Umschlag hat die Form
`{"schema":"regelsuche-prime-evidence-v1","proof":{...}}`.
Die Belegarten heißen `small`, `lucas` und `elliptic-order`; große
Ganzzahlen sind kanonische dezimale Zeichenketten. Die Beispieldatei
[`mersenne-61.json`](../../regelsuche-optimization-sdk/src/test/resources/prime-evidence/mersenne-61.json)
belegt `2^61−1` durch die vollständige Faktorisierung von `n−1` und den
Lucas-Zeugen 37. Damit ist die Bereitstellung zusätzlicher großer Moduli
ausführbar, ohne den eingebauten Katalog oder den Suchalgorithmus zu ändern.

Import und unabhängige Prüfung teilen sich das angegebene Arbeits-,
Speicher- und Zeitbudget. Zusätzlich gelten 65.536 Eingabebytes, 32
Belegebenen, 256 Faktoren pro Lucas-Knoten und die 4096-Bit-Grenze des
Kernprüfers. Doppelte oder unbekannte Felder, unbekannte Schemata,
abweichende Moduli und ungültige Zeugen werden abgewiesen. Das Format
enthält keine Objektverweise und führt keinen Netzabruf aus.

## Wiederholte Auswertung vorbereiten

`QuadraticRootSdk.prepare(...)` prüft Quellvertrag, Primzahlbeleg und
Programm einmal vollständig. Nur eine erfolgreiche Prüfung erzeugt den
unveränderlichen `QuadraticRootEvaluation.Prepared`-Handle; sein
Konstruktor ist nicht öffentlich. Wiederholte `evaluate`-Aufrufe prüfen
weiterhin die konkreten kanonischen Eingaben und die Normgleichung und
verbrauchen jeweils ein eigenes begrenztes Auswertungsbudget. Fehlerhafte
oder abgebrochene Vorbereitung erzeugt keinen ausführbaren Handle.

Die bisherige `QuadraticRootSdk.evaluate(...)`-Methode bleibt verfügbar
und wiederholt die Belegprüfung bei jedem Aufruf. Der vorbereitete Pfad
spart diese wiederholte Arbeit. Er ist keine Freigabe für eine inzwischen
geänderte Java-Quelle: Ein Compiler-Consumer muss seine Quell- und
Abhängigkeitsbelege vor Apply weiterhin auf Aktualität prüfen. Bereits
erzeugter Java-Code hat ohnehin keine SDK-Belegprüfung pro Ausführung.

Der ergänzende Modellvergleich verwendet jeweils beide Normvorzeichen für
`(a,b)=(0,2)` und das ausführbare Original mit dem Faktor `b/2` vor der
Inversion. Bei den Gewichten `(1,1,1000)` ergeben sich folgende gezählte
Arbeitsmengen; dies sind keine Zeitmessungen:

| Modul | Vorbereitung einmal | Vorbereitete Auswertung (g=2 / g=p−2) | Auswertung mit erneuter Prüfung (g=2 / g=p−2) |
|---|---:|---:|---:|
| 43 | 734 | 24 / 30 | 758 / 764 |
| `2^61−1` | 2427 | 79 / 89 | 2506 / 2516 |
| Katalogmodul mit 381 Bit | 365771 | 14049 / 14107 | 379820 / 379878 |

Die Werte sind mit der oben beschriebenen, vollständig belasteten
Jacobi-Auswertung neu ermittelt. Die Vorbereitung und der positive Normfall
bleiben unverändert. Im negativen Normfall ersetzen 30/89/14107 die früheren
28/87/14093 Einheiten. Insbesondere die zuvor unbezahlte Anfangsreduktion
ist jetzt enthalten; aus dieser Modelländerung folgt keine Aussage über
einen Laufzeitverlust oder -gewinn.

Der Import des 61-Bit-Belegs benötigt zusätzlich 3623 Arbeitseinheiten
und passt in `OptimizationBudget.DEFAULT`. Die Zahlen beziehen sich auf
diese Originaldarstellung und beide genannten Eingaben. Andere Programme
und Suchgewichte können andere Pläne und Prüfkosten ergeben.

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
  -Dtest='PrimeEvidence*Test,QuadraticCharacterTest,QuadraticRoot*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Die regulären Modul-POMs kompilieren die hinzugefügten Haupt- und
Testquellen außerdem mit `-Xlint:all -Werror`. Die fokussierten Tests
trennen algebraische Äquivalenz, konkrete Ausführung, verfälschte Zeugen
und Ressourcenabbruch. Die Quellcode- und Laufzeitqualifikation gehört
zum jeweiligen Consumer.

`QuadraticRootWorkModelTest` rekonstruiert beide hier dokumentierten
Originaldarstellungen samt Gewichten und erzeugt
`regelsuche-math-algorithms/target/quadratic-root-work-model.tsv` mit den
tatsächlich gezählten Such-, Vorbereitungs- und Auswertungswerten. Der Test
prüft zusätzlich die exakten Ergebnisse und die Arbeitsgrenzen; er enthält
keine Zeitmessung und erhöht keinen Budgetdeckel.

Siehe auch: [Gemeinsame Potenzen](shared-power-relations.md) und
[Strategien-Index](strategies.md).
