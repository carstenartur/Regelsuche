# Targetfreie Repräsentationssuche

`TargetFreeRepresentationSearch` bildet Kandidaten ohne Zielausdruck und ohne
Zugriff auf einen Katalog bekannter Formen. Die Klasse ist der erste
wiederverwendbare Produktionsbaustein für die R1- und R2-Tracks aus Issue #663.

## Informationsgrenze

Die Suche erhält ausschließlich:

```text
Ausgangsausdruck
explizites Rewrite-Regelinventar
festes Arbeitsbudget
```

Sie erhält insbesondere nicht:

```text
gewünschte Zielform
bekannte Struktur-ID
Katalogmuster oder Katalogkonsequenzen
Post-Freeze-Regeln
Expertenbewertung
```

Für R2 wird das Regelinventar durch
`RepresentationDiscoveryInformationBoundary` so gebildet, dass Rule-Packs der
zurückgehaltenen bekannten Strukturen während der Kandidatenbildung nicht
sichtbar sind.

## Suchverfahren

Der erste Slice verwendet eine deterministische, budgetierte Breitensuche:

1. Der Ausgangsausdruck wird parser-/formatter-kanonisch geschrieben, aber nicht
   algebraisch auf eine bevorzugte Form reduziert.
2. Alle vom sichtbaren `AstRewriteTransformationEngine` erzeugten
   Transformationen werden deterministisch sortiert.
3. Jede unterschiedliche normalisierte Darstellung wird einmal als Zustand
   behalten.
4. Die erste erhaltene Lineage ist aufgrund der Breitensuche eine kürzeste
   Rewrite-Tiefe unter der eingefrorenen Transformationsreihenfolge.
5. Duplikate und Zustandsbudget-Verwerfungen bleiben als Übergänge sichtbar.
6. Alle erreichten Nicht-Wurzel-Zustände bilden den einzufrierenden
   Kandidatenbestand.
7. Zusätzlich wird eine targetfreie Pareto-Front über die rohen
   Beschreibungsdimensionen ausgewiesen.

Die Pareto-Front ist kein universeller Einfachheitsscore. Verwendet werden
getrennt Token-, AST-, Operator-, Zahlenbit- und semantische Wertkosten sowie
wiederverwendbare Teilstruktur. Längere Zustände bleiben im vollständigen
begrenzten Kandidatenbestand erhalten, damit eine erst nach dem Freeze
erkennbare Wissensbrücke nicht durch eine reine Kürzungsheuristik verloren geht.

## Reproduzierbare Evidence

`SearchResult` bindet content-addressed:

- normalisierten Ausgangsausdruck;
- exaktes sichtbares Regelinventar;
- sämtliche Budgets;
- Zustände und deren erste Lineage;
- primitive Regel-IDs, Annahmen, Pack- und Lizenzinformationen;
- akzeptierte, duplizierte und budgetbedingt verworfene Übergänge;
- explizite Trunkierung;
- targetfreie Pareto-Zustände.

Die Zustandsidentität ist repräsentationsbezogen. Algebraisch äquivalente, aber
unterschiedlich geschriebene Formen bleiben getrennt, weil genau diese
Darstellungsunterschiede Gegenstand der Discovery sind.

## End-to-End-Szenario

`TargetFreeSymPyBridgeDiscoveryScenario` verwendet den R2-Track mit und ohne den
explizit aktivierten Pack `sympy-trigonometry`.

Ausgangsausdruck:

```text
sin(x)^2 + (cos(x)^2 + 0)
```

Die Suche enumeriert unter demselben katalogblinden Core-Regelinventar mehrere
Kandidaten, ohne nach einer Pythagoras-Form zu fragen. Erst nach dem
content-addressed Freeze werden die Kandidaten gegen den SymPy-abgeleiteten
Katalog klassifiziert.

Der Nachweis trennt:

- Pack deaktiviert: kein SymPy-Treffer, keine Folgefähigkeit;
- Pack aktiviert, Evidenz unter Schwelle: Strukturtreffer, aber keine
  Freischaltung;
- Pack aktiviert, symbolisch bestätigte Lineage: Freischaltung von
  `rule:sympy.trig.pythagorean`;
- Folgeausführung: der produktive AST-Motor erzeugt mit dieser erst
  post-freeze sichtbaren Regel den Zustand `1`.

Das Szenario schreibt:

```text
regelsuche-discovery/build/reports/representation-discovery/
  target-free-sympy-bridge.json
```

Die Datei verwendet festes LF, enthält die vollständige begrenzte
Such-Evidence und besitzt einen überprüften SHA-256-Inhaltshash.

## Reproduktion

```bash
./gradlew --no-daemon \
  :regelsuche-discovery:test \
  --tests de.regelsuche.discovery.representation.TargetFreeRepresentationSearchTest \
  --tests de.regelsuche.discovery.representation.TargetFreeSymPyBridgeDiscoveryScenarioTest
```

oder:

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-discovery -am \
  -Dtest=TargetFreeRepresentationSearchTest,TargetFreeSymPyBridgeDiscoveryScenarioTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Claim-Grenze

Dieser Slice zeigt eine echte, targetfreie Kandidatenbildung und eine
post-freeze erkannte, ausführbare Wissensbrücke unter einem kleinen festen
Budget. Er zeigt noch nicht:

- Überlegenheit auf einem held-out Korpus;
- externe mathematische Neuheit;
- globale Kürzestheit;
- allgemeine optimale Suchsteuerung;
- Transfer einer generalisierten Brücke auf eine unbekannte Strukturklasse.

Diese stärkeren Nachweise bleiben Aufgaben von #663, #620, #235 und der
späteren eingefrorenen Evaluation.
