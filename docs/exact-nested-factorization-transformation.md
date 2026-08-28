# Exakte Faktorisierung an verschachtelten AST-Vorkommen

**Implementierungsstand: 28. August 2026**

## Zweck

Der Wurzelpfad aus #780 kann verifier-ausgestellte Faktoren exakt rendern,
erneut parsen und im ursprünglichen Polynomring rekonstruieren. #782 und #783
binden nun außerdem ein konkretes parsererzeugtes Teilvorkommen an seinen
Quellbereich und projizieren es ohne numerischen Reparse in ein lokales
`ExactParsedTerm`.

`ExactNestedFactorizationTransformationPipeline` verbindet diese Stufen mit der
gemeinsamen strukturellen Ersetzung aus #784:

```text
ExactParsedTerm der vollständigen Wurzel
  + TreePosition(path, text)
  + FactorizationEngine<ExactRational>
  -> Wurzel-Preflight
  -> ExactParsedSubtermProjector
  -> ExactParsedFactorizationPipeline
  -> ExactFactorizationTransformationPipeline
  -> TreeExpressionEditor.replaceAt
  -> Prüfung der unberührten Umgebung
  -> Pfad-Replay und struktureller Replay-Hash
  -> occurrence-gebundene Transformationsevidence
```

Damit kann erstmals eine allgemeine verifizierte univariate Faktorisierung an
einer verschachtelten Stelle ausgeführt werden, ohne einen zweiten Pfadvertrag,
einen Formatter-Reparse der ursprünglichen Koeffizienten oder eine ungebundene
Stringersetzung einzuführen.

## Eine nicht zurücksetzbare Arbeitsautorität

Die globale Policy begrenzt:

- Pfadtiefe;
- Knoten der vollständigen Wurzel;
- Knoten des Ersatzteilbaums;
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
Strukturvergleich setzen bereits innerhalb dieser Grenze fort.

Die vorher reservierte Restarbeit deckt anschließend:

- erneute Auswahl und Text-Stalenessprüfung am Anwendungszeitpunkt;
- Knotenzählung des Ersatzes;
- Navigation und Kopie der Vorfahrenkette;
- Kontrolle aller unberührten Geschwisterreferenzen;
- erneute Pfadauflösung in der neuen Wurzel;
- unabhängige Wiederholung der Ersetzung;
- zwei iterative strukturelle SHA-256-Verpflichtungen.

Die Result-Evidence enthält nur tatsächlich verbrauchte Arbeit. Die Reserve
stellt sicher, dass diese Arbeit vor ihrer Ausführung autorisiert war.

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

## Strukturelle Ersetzung und Replay

Der Ersatz ist genau die AST-Wurzel des durch #780 erneut exakt geparsten und
rekonstruierten Faktorausdrucks. `TreeExpressionEditor` baut nur die
Vorfahrenkette des ausgewählten Vorkommens neu auf.

Die Pipeline prüft anschließend:

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
`double`-Wert eines `NumberExpr`. Numerische mathematische Autorität wird daraus
nicht abgeleitet; sie bleibt an den exakten Parser- und Rekonstruktionsnachweis
gebunden.

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
- Ablehnung eines ungültigen Kindindex vor dem Projektor.

## Abgrenzung

Der Slice aktiviert die Faktorisierung noch nicht als Standardstrategie der
Suche oder der Workbench. Er führt auch noch keinen abgeleiteten Makrocache und
keinen eingefrorenen On-Demand-/Cache-/No-Factorization-Vergleich aus. Diese
Folgestufen können nun jedoch dieselbe occurrence-gebundene
Transformationsevidence und dieselbe strukturelle Ersetzungsautorität nutzen.
