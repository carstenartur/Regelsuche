# Exakte Faktorisierung an verschachtelten AST-Vorkommen

**Implementierungsstand: 1. September 2026**

## Zweck

Der Wurzelpfad aus #780 kann verifier-ausgestellte Faktoren exakt rendern,
erneut parsen und im ursprünglichen Polynomring rekonstruieren. #782 und #783
binden außerdem ein konkretes parsererzeugtes Teilvorkommen an seinen
Quellbereich und projizieren es ohne numerischen Reparse in ein lokales
`ExactParsedTerm`.

`ExactNestedFactorizationTransformationPipeline` verbindet diese Stufen mit dem
in #784 vereinheitlichten `TreePosition`-Vertrag:

```text
ExactParsedTerm der vollständigen Wurzel
  + TreePosition(path, text)
  + FactorizationEngine<ExactRational>
  -> Wurzel-Preflight
  -> ExactParsedSubtermProjector
  -> ExactParsedFactorizationPipeline
  -> ExactFactorizationTransformationPipeline
  -> TreePosition.replaceAt
  -> Prüfung der unberührten Umgebung
  -> Pfad-Replay und struktureller Replay-Hash
  -> occurrence-gebundene Transformationsevidence
```

Damit kann eine allgemeine verifizierte univariate Faktorisierung an einer
verschachtelten Stelle ausgeführt werden, ohne einen zweiten Pfadvertrag, einen
Formatter-Reparse der ursprünglichen Koeffizienten oder eine ungebundene
Stringersetzung einzuführen.

## Modulgrenze

`TreePosition` und `ExactNestedFactorizationTransformationPipeline` liegen im
Modul `regelsuche-search`. Ihre Paketnamen und öffentlichen APIs bleiben
unverändert. Die Verschiebung ist keine zweite Implementierung, sondern entfernt
eine frühere, für wiederverwendbare Suchinfrastruktur falsche physische
Abhängigkeit vom Produktmodul `app`.

Dadurch können sowohl `app` als auch `regelsuche-experiments` dieselbe
Occurrence-, Staleness-, Ersetzungs- und Replayautorität verwenden. Eine
Abhängigkeit von `regelsuche-experiments` zurück auf `app` wäre zyklisch, weil
das Produktmodul die Experimentkomponente bereits konsumiert. Die gemeinsame
Grenze muss deshalb unterhalb beider Verbraucher liegen.

Die umfangreichen Faktorisierungs- und Reserve-Tests verbleiben bewusst im
`app`-Modul. Sie prüfen damit nicht nur die isolierte Pipeline, sondern zugleich,
dass das Produkt die aus `regelsuche-search` bezogene Implementierung über die
reguläre Modulabhängigkeit verwendet. Neue Benchmarkadapter dürfen die Klassen
direkt aus `regelsuche-search` beziehen und keine lokale Kopie oder abweichende
AST-Positionslogik einführen.

## Eine nicht zurücksetzbare Arbeitsautorität

Die globale Policy begrenzt:

- Pfadtiefe;
- Knoten der vollständigen Wurzel;
- Knoten des Ersatzteilbaums;
- Quell-, Formatter- und Renderergrößen der beteiligten Darstellungen;
- Faktorisierungskandidaten und strukturelle Polynomgrenzen;
- gesamte kanonische Arbeit.

Vor dem Start der Projektion wird konservativ geprüft, ob die Gesamtgrenze noch
folgende Obergrenzen deckt:

```text
Wurzel-Preflight
+ maximale Projektionsarbeit
+ Mindestarbeit der exakten Polynomextraktion
+ vollständige Ersetzungs- und Replayreserve
```

Nach der tatsächlichen Projektion wird deren verbrauchte Arbeit von der
globalen Autorität abgezogen. Die verbleibende Faktorisierungsgrenze wird als
`maxTotalWorkUnits` der neu erzeugten `ExactParsedFactorizationPipeline.Policy`
weitergereicht. Rendering, exakter Reparse, Rekonstruktion und
Strukturvergleich setzen innerhalb dieser bereits reduzierten Grenze fort.

Die vorher reservierte Restarbeit deckt anschließend:

- erneute Auswahl des Vorkommens;
- beide Formatter-Snapshots der Anwendungs-Stalenessprüfung;
- Knotenzählung des Ersatzes;
- Navigation und Kopie der Vorfahrenkette;
- Kontrolle aller unberührten Geschwisterreferenzen;
- erneute Pfadauflösung in der neuen Wurzel;
- unabhängige Wiederholung der Ersetzung;
- zwei iterative strukturelle SHA-256-Verpflichtungen;
- die UTF-8-Payload aller in die beiden Struktur-Hashes eingebrachten
  Knotenarten, Operatoren, Namen, Argumentzahlen und numerischen AST-Werte.

Die Payloadreserve verwendet die deklarierten Höchstgrößen von Root-Quelle und
Renderer-Ausgabe. UTF-16-Code-Units werden mit dem konservativen Faktor vier in
UTF-8-Bytes überführt; zusätzlich wird je möglichem Hashknoten ein fester
Payloadrahmen reserviert. Die Result-Evidence enthält dagegen nur tatsächlich
verarbeitete Bytes und Knoten. Die Reserve stellt sicher, dass diese Arbeit vor
ihrer Ausführung autorisiert war.

## Occurrence- und Stalenessvertrag

Der Projektor bindet:

- den Kindindexpfad;
- den erwarteten und aktuellen Formatter-Snapshot;
- den absoluten parserausgestellten `SourceRange`;
- das vollständige Root-Source-Hash;
- lokal verschobene Knoten- und Literalbereiche;
- exakte Literalzertifikate.

Unmittelbar vor der Ersetzung wird derselbe Pfad erneut an der ursprünglichen
AST-Wurzel aufgelöst. Das Vorkommen muss weiterhin dieselbe Parser-Knoteninstanz
sein und denselben Anzeige-Snapshot besitzen. Ein verschwundener Pfad und ein
veraltetes Vorkommen bleiben getrennte Ergebnisse.

`TreePosition.text` ist ausschließlich Staleness-Evidence. Mathematische
Autorität stammt weiterhin aus der parsergebundenen exakten Projektion, dem
`FactorizationVerifier`, dem exakten Faktorenrenderer und der unabhängigen
Ringrekonstruktion.

## Eine gemeinsame strukturelle Autorität

Auswahl und Ersetzung werden in der Pipeline direkt durch
`TreePosition.subtreeAt` und `TreePosition.replaceAt` ausgeführt. Es existiert
keine zweite Navigations-, Ersetzungs- oder Vorfahrenrekonstruktionslogik und
kein textbasierter Kompatibilitätsadapter. Die Pipeline verarbeitet unmittelbar
das autoritative `TreePosition.ReplacementResult`.

Dadurch gelten für lokale Regeln und verifier-autorisierte Faktorisierungen
dieselben Eigenschaften:

- leerer Pfad bezeichnet die Wurzel;
- Binärkinder verwenden `0` und `1`;
- Funktionsargumente verwenden ihren Argumentindex;
- Navigation und Rekonstruktion sind iterativ;
- nur die Vorfahrenkette wird neu aufgebaut;
- alle unberührten Geschwister behalten ihre Objektidentität;
- Text ist nie mathematische oder strukturelle Ersetzungsautorität.

## Strukturelle Ersetzung und Replay

Der Ersatz ist genau die AST-Wurzel des durch #780 erneut exakt geparsten und
rekonstruierten Faktorausdrucks. Die Pipeline prüft anschließend:

- das tatsächlich ausgewählte alte Vorkommen entspricht der autorisierten
  Parseridentität;
- der neue Pfad löst auf exakt die Ersatzinstanz auf;
- jeder nicht ausgewählte Geschwisterknoten bleibt dieselbe Objektinstanz;
- Operatoren, Funktionsnamen und Argumentanzahl der Vorfahren bleiben gleich;
- dieselbe Ersetzung lässt sich aus der ursprünglichen Wurzel erneut ausführen;
- Originalausführung und Replay besitzen denselben iterativ berechneten
  strukturellen SHA-256-Hash.

Die strukturelle Verpflichtung kodiert Knotenart, Operator, Funktionsname,
Argumentzahl, Variablenname und für die syntaktische AST-Form den hexadezimalen
`double`-Wert eines `NumberExpr`. Jedes tatsächlich gehashte UTF-8-Byte wird in
einer eigenen Work-Ledger-Stufe verbucht. Numerische mathematische Autorität
wird aus dem Struktur-Hash nicht abgeleitet; sie bleibt an den exakten Parser-
und Rekonstruktionsnachweis gebunden.

## Getrennte Ergebnisse

Die Pipeline unterscheidet:

```text
TRANSFORMED
POSITION_NOT_PRESENT
POSITION_STALE
NO_CHANGE
NO_CANDIDATE
BACKEND_CLAIMED_IRREDUCIBLE
IRREDUCIBLE
UNSUPPORTED
BUDGET_INCONCLUSIVE
TECHNICAL_FAILURE
SOURCE_EVIDENCE_MISMATCH
```

Ein Engine-Miss, ein Backend-Claim, eine Ressourcenbegrenzung oder ein
technischer Widerspruch erzeugt keine Suchkante. Ein strukturell identischer
Faktorausdruck bleibt `NO_CHANGE`.

## Komplexitätsgrenze

Die Orchestrierung ist entlang ihrer fachlichen Phasen getrennt:

```text
Eingabe- und Positionsprüfung
-> Wurzel-Preflight und Reservierung
-> Projektion, Faktorisierung und exakte Transformation
-> occurrence-gebundene Anwendung
-> Umgebungsprüfung und unabhängiger Replay
```

Insbesondere enthält weder die zentrale Orchestrierung noch die Prüfung
unberührter Referenzen einen neuen Komplexitäts-Hotspot. Diese Zerlegung ändert
keine Statusabbildung, kein Arbeitsbudget, keine Evidence und keine
Qualitätsschwelle.

## Qualifikation

Die fokussierten Tests charakterisieren:

- native exakte rationale Faktorisierung innerhalb eines Funktionsarguments;
- Erhalt eines unberührten zweiten Arguments als identische Objektinstanz;
- gemeinsame Projektions-, Faktorisierungs-, Ersetzungs- und Replayarbeit;
- deterministische Zertifikate bei wiederholter Ausführung;
- zwei wertgleiche Teilbäume an unterschiedlichen Pfaden;
- getrennte absolute Vorkommensbereiche und Zertifikate;
- Ablehnung eines veralteten Positionstextes vor der Faktorisierung;
- Wurzelpfad-Parität mit demselben Ersetzungs- und Replayvertrag;
- Verweigerung vor der Projektion, wenn die globale Autorität die deklarierten
  Obergrenzen nicht deckt;
- separate Abrechnung beider Formatter-Snapshots;
- separate Abrechnung der UTF-8-Payload beider struktureller Hashläufe;
- fail-closed Preflight, wenn die deklarierte Text- und Hash-Payloadreserve nicht
  in die Gesamtarbeitsautorität passt;
- Ablehnung eines ungültigen Kindindex vor dem Projektor.

Fokussierter Gradle-Aufruf:

```bash
./gradlew --no-daemon \
  :regelsuche-search:classes \
  :app:test \
  --tests 'de.regelsuche.polynomial.ExactNestedFactorization*Test'
```

Vor dem Merge bleiben zusätzlich der checkout-eigene `ciCheck`-Lebenszyklus,
die isolierte JMH-/SymPy-Autorität und der vollständige
Maven-/Produkt-/Docker-Vertrag verbindlich.

## Abgrenzung

Der Slice aktiviert die Faktorisierung noch nicht als Standardstrategie der
Suche oder der Workbench. Er führt auch noch keinen abgeleiteten Makrocache und
keinen eingefrorenen On-Demand-/Cache-/No-Factorization-Vergleich aus. Diese
Folgestufen können nun jedoch dieselbe occurrence-gebundene
Transformationsevidence und dieselbe strukturelle Ersetzungsautorität nutzen.
