# Gleichheit nach einer Wurzelnormalisierung

## Auftrag und Zweck

Carsten hat am 27. November 2026 die Umsetzung des zuvor beschriebenen
Kontextnachweises beauftragt: unterschiedliche Zwischenwurzeln sind zulässig,
wenn eine nachfolgende Normalisierung nachweisbar dieselbe beobachtbare Ausgabe
erzeugt. Allgemeine Mathematik gehört in den Regelsuche-Kern, das SDK bleibt
ein Zugang; Quellcode, Effekte und beobachtbare Verwendungen gehören in Sandbox.

## Ausgangslage

`QuadraticRootContract` unterscheidet bereits `EXACT_PAIR` und
`UP_TO_JOINT_SIGN`. Die bestehende Suche und ihr Laurent-Prüfer können ein
Programm ohne Jacobi-Korrektur für den zweiten Vertrag liefern. Daraus allein
folgt keine Gleichheit einer nachfolgenden Java-Ausgabe.

## Gewählter Ansatz

Ein eigenständiger zusammengesetzter Beleg bindet den bestehenden Wurzelbeleg
an einen unabhängig rekonstruierten Normalisierervertrag. Der Normalisierer
wählt für ein kanonisches Paar über einem ungeraden Primkörper ein Vorzeichen
und negiert beide Koordinaten gemeinsam, wenn dieses vom unveränderten Zielbit
abweicht. Das Ziel ist exakt ein Bit (0 oder 1). Die Negation erfolgt kanonisch
modulo derselben Primzahl. Das Nullpaar bleibt durch Negation fest.

Der Vorzeichenwähler ist eine 9-Bit-Wahrheitstabelle. Der Index lautet
`3 * realClass + imaginaryClass`; jede Koordinate hat die Klassen ZERO=0,
NONZERO_EVEN=1 oder ODD=2. Für jede der acht Nichtnull-Zellen muss der Wähler
unter gemeinsamer Negation sein Bit wechseln. Diese endliche Bedingung beweist
`N_t(x,y) = N_t(-x,-y)` für jedes unveränderte Zielbit. Beide Reihenfolgen der
ersten von null verschiedenen Koordinate sind Beispiele, keine Sonderfälle
des Prüfers. Eine vorangehende Multiplikation mit einem unveränderten Faktor
kann eine spätere Anwendung durch einen gesonderten Ring-/Quellnachweis
begründen; dieser API erteilt keine Freigabe für undurchsichtige Java-Methoden.

## Erwogene Alternativen

- Ein neues Beobachtungs-Enum wäre klein, würde den tatsächlichen Normalisierer
  aber nicht prüfen und wird deshalb nicht verwendet.
- Die vorhandene exakte Paarprüfung bleibt der sichere Weg ohne ausreichenden
  Kontextnachweis; ihr Verhalten ändert sich nicht.
- Ein Vergleich beider tatsächlich ausgeführten Beobachtungen könnte einen
  bedingten Laufzeitpfad begründen. Dessen Java-Effekt-, Eigentums- und
  Ausnahmebeweis ist ein eigener Ausbau und gehört nicht in diese Änderung.

## API

`QuadraticRootNormalization` enthält `ParitySelector(int mask)`,
`Contract(String sourceIdentity, BigInteger modulus, ParitySelector selector)`
und `Certificate(QuadraticRootSearch.Certificate root, Contract normalizer)`.
`verifyNormalized(expectedRoot, expectedNormalizer, supplied, limits, cancel)`
gibt einen `QuadraticRootVerifier.Result` zurück. Das SDK bietet denselben
Aufruf mit `OptimizationBudget` und `CancellationToken`.

Ein Zertifikat ist untrusted. Der Prüfer bindet die vollständigen erwarteten
Verträge einschließlich Quellenidentität, Modul und Wähler. Er spielt den
echten Wurzelbeleg und die endliche Normalisiererbedingung mit demselben
Arbeits-/Speicher-/Zeitbudget nach. Ein Abbruch erteilt keine Freigabe. Der
Consumer muss Zielbit, Entscheidung, gemeinsame Negation, Zwischenverwendungen
und deren Quellen selbst aus dem ursprünglichen und erzeugten Code binden.

## Grenzen und Kompatibilität

- Keine Bouncy-Castle-Namen, Beispielprimzahlen oder optimierten Methodenrümpfe
  in Produktionscode; mehrere Primzahlen und umbenannte Consumer sind Kontrollen.
- Die Raw-Such-, Prüf- und Auswertungs-APIs bleiben unverändert.
- Ein verifiziertes mathematisches Normalisierungslemma ist kein Beweis über
  fremde Montgomery-Limb-Methoden, Java-Identitäten oder veränderliche Arrays.
- Geänderte Java-Produktions- und Testdateien werden mit Java 25 und
  `-Xlint:all -Werror` geprüft. Keine Warnungsausnahmen oder neuen Abhängigkeiten.
- Ergebnisse und Grenzen werden im Strategien-Index und in der Forschungs-
  und SDK-Dokumentation sichtbar gemacht. Kein Laufzeitgewinn ohne Messung.

## Abnahme

Unabhängige kleine Körper liefern alle konkreten Paare und beide Zielbits.
Alle 512 Wählertabellen werden gegen direkte Normalisierung modulo 3 geprüft;
genau 32 erfüllen die universelle Bedingung. Achsen, Null, falsche gemeinsame
Negation, falscher Modul, vertauschte Quelle, manipulierte Belege, nicht echte
BigInteger-Werte und Abbruch-/Budgetgrenzen werden gesondert geprüft.
