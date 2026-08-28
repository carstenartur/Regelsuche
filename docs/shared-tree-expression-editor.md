# Gemeinsame pfadsichere AST-Auswahl und -Ersetzung

**Implementierungsstand: 28. August 2026**

## Zweck

`TreePosition` definiert bereits den stabilen Kindindexpfad für lokale
Transformationen. Die zugehörige Navigation und Ersetzung war bisher jedoch als
private Implementierung in `LocalRewriteApplier` eingeschlossen. Der
verschachtelte exakte Faktorisierungspfad aus #763 darf keine zweite, leicht
abweichende Pfadsemantik einführen.

`TreeExpressionEditor` ist deshalb die gemeinsame strukturelle Autorität für:

```text
Expr-Wurzel
  + TreePosition.path
  -> konkretes Teilvorkommen
  -> Ersetzung genau dieses Vorkommens
  -> Neuaufbau ausschließlich der Vorfahrenkette
```

`LocalRewriteApplier` verwendet dieselbe Komponente. Die folgende
Faktorisierungsintegration kann damit auf identische Navigation und identische
Teilbaumersetzung zurückgreifen.

## Pfadvertrag

- der leere Pfad bezeichnet die Wurzel;
- bei `BinaryExpr` bezeichnet `0` den linken und `1` den rechten Operanden;
- bei `FunctionExpr` bezeichnet der Index das Argument;
- negative oder `null`-Indizes sind ungültig;
- ein Index außerhalb des Knotens oder ein Abstieg durch ein Blatt ergibt
  `POSITION_NOT_PRESENT`;
- Pfade und Ergebnislisten werden unveränderlich kopiert.

Die Komponente verarbeitet ausschließlich AST-Objekte. `TreePosition.text`
bleibt beim Aufrufer und dient weiterhin als Anzeige- und Stalenessschutz. Text
autorisiert keine mathematischen Werte und wird von `TreeExpressionEditor`
weder geparst noch formatiert.

## Iterative Ersetzung

Navigation und Neuaufbau sind iterativ. Während der Navigation wird für jede
Pfadkante nur der Elternknoten mit dem ausgewählten Kindindex festgehalten.
Anschließend wird die Vorfahrenkette in umgekehrter Reihenfolge aufgebaut.
Dadurch benötigt ein tiefer Pfad keinen rekursiven Java-Aufrufstapel.

Für jeden neu aufgebauten Vorfahren gilt:

- nur das Kind auf dem ausgewählten Pfad wird ersetzt;
- alle nicht betroffenen Geschwister bleiben dieselben Objektinstanzen;
- Funktionsargumentlisten werden nur für die betroffenen Vorfahren kopiert;
- bei einer Wurzelersetzung wird kein Vorfahr kopiert.

Das Ergebnis bewahrt das ursprüngliche Teilvorkommen, die neue Wurzel und die
Zahl der kopierten Vorfahren. Fehlende und syntaktisch ungültige Pfade bleiben
getrennte Zustände.

## Abgrenzung

Die Komponente:

- realisiert keine Regel;
- prüft keine mathematische Äquivalenz;
- parst und formatiert keinen Ausdruck;
- erzeugt kein Faktorisierungs- oder Beweiszertifikat;
- entscheidet nicht, ob ein ausgewählter `TreePosition` veraltet ist.

Diese Verantwortungen bleiben bei den jeweiligen Pipelines. Der Editor liefert
nur die eine wiederverwendbare strukturelle Operation, auf die deren Evidence
verweisen kann.

## Qualifikation

Die Tests charakterisieren:

- Wurzelersetzung ohne kopierten Vorfahren;
- verschachtelte Ersetzung durch Funktions- und Binärknoten;
- Erhalt unberührter Geschwisterinstanzen;
- getrennte ungültige und nicht vorhandene Pfade;
- einen 8.000 Kanten tiefen Pfad ohne rekursive Ersetzung;
- unverändertes Verhalten der bestehenden String- und AST-APIs von
  `LocalRewriteApplier`.

## Nächster Schritt

Der nächste #763-Slice kann nun dieselbe Komponente verwenden:

```text
ExactParsedSubtermProjector.Result
  -> exakte Faktorisierung und Rekonstruktion
  -> TreeExpressionEditor.replaceAt
  -> erneute Pfad- und Replayprüfung
  -> occurrence-gebundene Transformationsevidence
```

Die dort verbrauchte Navigations-, Projektions-, Faktorisierungs-, Ersetzungs-
und Replayarbeit muss weiterhin in einer nicht zurücksetzbaren Gesamtbilanz
geführt werden.
