# Gemeinsame pfadsichere AST-Auswahl und -Ersetzung

**Implementierungsstand: 28. August 2026**

## Zweck

`TreeExpressionEditor` macht den von `TreePosition` definierten Kindindexpfad
zur gemeinsamen strukturellen Autorität für lokale Regeln und den kommenden
verschachtelten Faktorisierungspfad aus #763:

```text
Expr-Wurzel + Pfad
  -> konkretes Teilvorkommen
  -> Ersetzung
  -> Neuaufbau nur der Vorfahrenkette
```

`LocalRewriteApplier` delegiert Auswahl und Ersetzung an diese Komponente.

## Vertrag

- Der leere Pfad bezeichnet die Wurzel.
- Bei `BinaryExpr` adressieren `0` und `1` die Operanden.
- Bei `FunctionExpr` adressiert der Index ein Argument.
- Negative oder `null`-Indizes sind `INVALID_PATH`; ein nicht vorhandener
  Abstieg ist `POSITION_NOT_PRESENT`.
- Navigation und Neuaufbau sind iterativ und verbrauchen bei tiefen Pfaden
  keinen rekursiven Java-Aufrufstapel.
- Nur Vorfahren des Ersatzes werden neu erzeugt. Alle unberührten Geschwister
  bleiben dieselben Objektinstanzen.
- Das Ergebnis bindet ausgewählten Teilbaum, neue Wurzel und Zahl der kopierten
  Vorfahren.

Der Editor verarbeitet ausschließlich `Expr`-Objekte. `TreePosition.text`
bleibt beim Aufrufer als Anzeige- und Stalenessschutz; der Editor parst oder
formatiert keinen Text und autorisiert keine mathematische Transformation.

## Qualifikation

Die Tests decken Wurzel- und verschachtelte Ersetzung, erhaltene
Geschwisteridentitäten, ungültige und fehlende Pfade sowie einen 8.000 Kanten
tiefen Pfad ab. Die bestehende `LocalRewriteApplierTest`-Suite bleibt für die
String- und AST-Einstiegspunkte verbindlich.

## Folgeintegration

Der nächste Slice verwendet denselben Pfadvertrag für
`ExactParsedSubtermProjector`, exakte Faktorisierung, `replaceAt` und Replay.
Projektions-, Faktorisierungs-, Ersetzungs- und Replayarbeit müssen dabei in
einer nicht zurücksetzbaren Gesamtbilanz bleiben.
