# Primitive Pfad- und Struktur-Evidenz der Polynomstudie

Status: eigenständiger Messbaustein vor Resultatintegration und Candidate-Freeze

Bezug: Issue #748

## Problem

Der Übergangsvertrag bindet die mathematische Transformation, AST-Fundstelle,
Backend-, Cache- und Arbeitsautorität. Aus einem Übergang allein lässt sich aber
nicht rekonstruieren:

- wie viele Suchkanten der retained Pfad besitzt,
- welche primitiven Regeln in welcher Reihenfolge ausgeführt wurden,
- welche primitiven Schritte zu derselben Suchkante gehören,
- welche normalisierten Annahmen der Pfad tatsächlich verwendet,
- wie sich die kanonische AST-Größe durch den Übergang verändert.

Ein bloßer Regel-ID-Satz oder eine primitive Schrittanzahl wäre dafür
unzureichend. Er würde Reihenfolge, Wiederholungen und Makrogrenzen verlieren.

## `PolynomialTheoryUtilityTransitionTrace`

Der neue content-adressierte Vertrag bindet genau einen bereits typisierten
`PolynomialTheoryUtilityTransitionOutcome` an:

- positive Pfadtiefe,
- eine geordnete unveränderliche Primitive-Step-Liste,
- normalisierte und sortierte Annahmen,
- kanonische Quell- und Ergebnis-AST-Knotenzahlen.

Die Trace-Identität enthält die Übergangsidentität, Pfadtiefe, beide
AST-Messwerte, jede Primitive-Step-Identität in Listenreihenfolge und jede
normalisierte Annahme.

Der Vertrag führt keine zweite mathematische Transformation ein. Er ergänzt den
vorhandenen Übergang ausschließlich um Pfad- und Mess-Evidenz.

## Primitive Schritte und Kanten

Jeder `PrimitiveStep` bindet:

- seinen kontinuierlichen Index in der vollständigen primitiven Expansion,
- den Index der zugehörigen Suchkante,
- die exakte Übergangsidentität,
- die primitive Regel- oder Stufenidentität,
- einen SHA-256-Evidenzverweis.

Suchkanten beginnen bei null, erscheinen in nicht fallender Reihenfolge und
dürfen keine Lücke besitzen. Jede Kante muss mindestens einen primitiven Schritt
enthalten. Die letzte beobachtete Kante bestimmt deshalb die Pfadtiefe exakt.

Damit bleiben zwei verschiedene Größen getrennt:

```text
pathDepth                = Anzahl der Suchkanten
primitiveExpansionLength = Anzahl der expandierten primitiven Schritte
```

Ein Makro kann somit Pfadtiefe eins und eine längere primitive Expansion
besitzen. Wiederholte Primitive bleiben als verschiedene indexierte Schritte
erhalten.

Die Primitive-Step-Anzahl darf die am Übergang retained primitive Arbeit nicht
überschreiten. Die Differenz darf beispielsweise Arbeit für verworfene
primitive Versuche darstellen; sie wird nicht als Teil des erfolgreichen
Pfades erfunden.

## Annahmen

Die Factory akzeptiert textuelle Annahmen und überführt sie mit der gemeinsamen
`AssumptionSignature` in eine sortierte, duplikatfreie Normalform. Der direkte
Record-Konstruktor akzeptiert ausschließlich diese Normalform.

Damit werden unter anderem Schreibvarianten wie

```text
0 != x
x ≠ 0
x != 0
```

als dieselbe retained Annahme behandelt. Die Trace behauptet nicht, dass eine
Annahme bewiesen ist; Beweis-, Deklarations- und Guard-Status bleiben getrennte
Evidenzachsen.

## AST-Messung

Quell- und Ergebnisgröße werden mit der gemeinsamen
`ExpressionCanonicalizer.astNodeCount`-Semantik bestimmt und beim Aufbau erneut
gegen die gebundenen Übergangsausdrücke geprüft. `astNodeGrowth` ist die
vorzeichenbehaftete Differenz:

```text
transformedAstNodeCount - sourceAstNodeCount
```

Damit kann die spätere Studie längere, aber fähigkeitsstiftende
Zwischendarstellungen von echter struktureller Kompression unterscheiden.

## Fail-closed-Regeln

Der Vertrag weist insbesondere zurück:

- leere Primitive-Lineage,
- null oder negative Pfadtiefe,
- falsche oder wiederverwendete Primitive-Indizes,
- fallende Kantenindizes oder Kantenlücken,
- Pfadtiefe ohne entsprechende letzte Kante,
- einen primitiven Schritt aus einem anderen Übergang,
- mehr Pfadschritte als retained primitive Arbeit,
- unsortierte oder nicht normalisierte Annahmen,
- erfundene AST-Knotenzahlen,
- veränderte Trace- oder Primitive-Step-Identitäten,
- Rebinding an einen anderen Resultatübergang.

## Noch nicht Teil dieses Schritts

Dieser Slice verändert weder Runner noch CandidateResult oder Batch. Noch
offen bleiben die resultweiten Messungen für:

- Faktorisierungsanfragen und Kandidaten,
- Cache-Lookups, Hits, Misses, Einfügungen, Verdrängungen und Replays,
- die vollständige Zuordnung aller Traces zu einem Resultat,
- die kanonische Candidate-Freeze,
- mathematische Profiladapter und Qualifikation.

Der nächste Messvertrag fasst diese resultweiten Beobachtungen zusammen und
fordert eine positionsgleiche Trace für jeden retained Übergang. Erst danach
ist eine neue Candidate-Freeze zulässig.
