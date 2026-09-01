# Resultweite Mess-Evidenz der Polynomstudie

Status: vollständiger target-blinder Messbegleiter vor Candidate-Freeze

Bezug: Issue #748

## Zweck

Die elementaren Verträge der vorherigen Slices bewahren jeweils genau eine
Transformationstrace, einen Faktorisierungsversuch oder ein Cacheereignis. Für
eine kanonische Candidate-Freeze genügt das noch nicht. Ohne eine resultweite
Grenze könnte ein Aufrufer einzelne Beobachtungen auslassen, verdoppeln,
umordnen oder einem anderen `CandidateResult` zuordnen und dennoch formal
gültige Einzelwerte besitzen.

`PolynomialTheoryUtilityCandidateMeasurements` schließt diese Lücke. Der
content-adressierte Vertrag bindet genau ein bereits typisiertes
`PolynomialTheoryUtilityCandidateResult` an:

- die eingefrorene Formation-Annahmemenge,
- die aus allen Transformationstraces abgeleiteten normalisierten Annahmen,
- die kanonische AST-Größe des Quellausdrucks,
- genau eine positionsgleiche Trace je Ergebnisübergang,
- alle Faktorisierungsversuche in Ausführungsreihenfolge,
- alle Cacheereignisse in Ausführungsreihenfolge.

Die versiegelte Qualifikation wird dabei nicht geöffnet. Erwartete Ergebnisse,
Referenzausdrücke, Reduzierbarkeitslabels und Produktentscheidungen sind weder
Eingaben noch Felder dieses Vertrags.

## Identität

Die Messidentität enthält:

- Schema und Studienidentität,
- die vollständige `CandidateResult`-Identität,
- die Formation-Annahmemengen-ID,
- die kanonische Quell-AST-Knotenzahl,
- jede normalisierte tatsächlich von einer Trace getragene Annahme,
- jede Trace-, Attempt- und Event-Identität in exakter Listenreihenfolge.

Alle Detailfelder der Einzelbeobachtungen sind bereits Bestandteil ihrer
eigenen SHA-256-Identitäten. Der resultweite Vertrag wiederholt diese Daten
nicht als konkurrierende Autorität, sondern bindet deren geordnete und
vollständige Zusammensetzung.

## Transformationstraces

Die Anzahl der Traces muss exakt der Anzahl der typisierten
Ergebnisübergänge entsprechen. Trace `i` muss Übergang `i` binden. Der Vertrag
weist fehlende, zusätzliche, duplizierte, umgeordnete oder fremde Traces zurück.

Die resultweiten Annahmen sind keine frei editierbare Liste. Sie werden als
sortierte, duplikatfreie Vereinigung der Annahmen aller gebundenen Traces
gebildet. Bei Formation-Annahmemenge `NONE` darf keine Trace eine Annahme
erfinden. Die Formation-ID bleibt auch bei einem negativen Ergebnis sichtbar,
selbst wenn kein erfolgreicher Pfad eine Annahme tatsächlich verwendet.

Die Summe der primitiven Expansionen darf die vom `CandidateResult` retained
primitive Arbeit nicht überschreiten. Nicht im erfolgreichen Pfad enthaltene
primitive Arbeit kann weiterhin verworfene Versuche darstellen und wird nicht
nachträglich als Lineage ausgegeben.

## Faktorisierungsversuche

Attempt-Indizes müssen bei null beginnen und ohne Lücke der Listenposition
entsprechen. Jede Attempt-Identität ist eindeutig und wird erneut gegen das
exakte Resultat und dessen vollständiges eingefrorenes Profil geprüft.

Die resultweite Grenze verlangt außerdem:

- ein faktorisierungsfreies Profil besitzt keine Attempts,
- Faktorisierungsarbeit und das Vorhandensein wenigstens eines Attempts stimmen
  überein,
- jeder On-Demand- oder Cache-Miss-Übergang besitzt genau einen erzeugenden
  Faktorisierungsversuch,
- ein Cache-Hit-/Replay-Übergang besitzt keinen neuen erzeugenden Versuch,
- derselbe Übergang kann nicht durch mehrere Attempts beansprucht werden.

Negative Attempts ohne Kandidat oder Übergang bleiben erhalten. Dadurch können
`UNSUPPORTED`, `BUDGET_INCONCLUSIVE`, technische Fehler und verifizierte
`NO_CHANGE`-Ausgänge später vollständig ausgewertet werden.

## Cacheereignisse

Event-Indizes müssen ebenfalls kontinuierlich der Listenposition entsprechen.
Für jeden an einen Übergang gebundenen Cachepfad ist genau eine kanonische
Ereignisfolge zulässig:

```text
CACHE_MISS_INSERTED ohne Eviction:
  LOOKUP_MISS, INSERTION

CACHE_MISS_INSERTED mit Eviction:
  LOOKUP_MISS, INSERTION, EVICTION

CACHE_HIT_REPLAYED:
  LOOKUP_HIT, REPLAY

CACHE_DISABLED:
  keine Ereignisse
```

Gebundene Ereignisgruppen folgen der Ergebnisübergangsreihenfolge. Ein
ungebundener Lookup-Hit oder Lookup-Miss bleibt zulässig, wenn danach kein
validierter Übergang entsteht. Einfügung, Verdrängung oder Replay ohne
Übergangslineage werden dagegen abgewiesen.

Die Existenz von Lookup-, Insert-, Eviction- und Replay-Ereignissen muss jeweils
mit positiver Arbeit in der entsprechenden Komponente des
`PolynomialTheoryUtilityWorkBreakdown` übereinstimmen. Umgekehrt darf retained
Cachearbeit nicht ohne das dazugehörige Ereignis verschwinden. Die Höhe der
Arbeitskomponente ist weiterhin kanonische Arbeit und nicht mit der bloßen
Ereignisanzahl gleichgesetzt.

## Abgeleitete Messwerte

Der Vertrag stellt ohne zusätzliche gespeicherte Zähler bereit:

- `generatedTransitionCount`,
- die geordnete Liste und Summe der Pfadtiefen,
- die geordnete Liste und Summe der primitiven Expansionslängen,
- Faktorisierungsanfrage- und Kandidatenanzahl,
- Cache-Hits, -Misses, -Einfügungen, -Verdrängungen und -Replays,
- Quell- und geordnete Ergebnis-AST-Knotenzahlen,
- die vorzeichenbehaftete AST-Größe jedes Übergangs,
- die geordnete primitive Regel-Lineage.

Diese Werte werden aus den identitätsgebundenen Einzelbeobachtungen berechnet.
Es gibt daher keine zweite frei manipulierbare Zählerschicht, die von den
konkreten Requests, Kandidaten, Traces oder Events abweichen könnte.

## Fail-closed-Grenzen

Insbesondere werden zurückgewiesen:

- ein fremdes oder manipuliertes `CandidateResult`,
- eine falsche Formation- oder Annahmemengenbindung,
- eine erfundene Quell-AST-Größe,
- fehlende, zusätzliche, duplizierte oder umgeordnete Traces,
- Trace-Annahmen außerhalb der sichtbaren Formation,
- primitive Expansion oberhalb der retained Arbeit,
- fehlende oder mehrdeutige Faktorisierungsproduzenten,
- Attempts ohne entsprechende Faktorisierungsarbeit,
- unvollständige, umgeordnete oder fremde Cachefolgen,
- Cachearbeit ohne Event beziehungsweise Event ohne Arbeit,
- verwaiste Cachemutation oder verwaistes Replay,
- eine gefälschte Messidentität.

## Nächster Schritt

Nach Qualifikation und Merge dieses Vertrags kann die Candidate-Freeze neu
aufgebaut werden. Sie darf ausschließlich das vollständig validierte
600-Resultat-Batch zusammen mit genau einer resultweiten Messidentität je Zeile
serialisieren. Erst danach werden die vier mathematischen Profiladapter
angeschlossen und die target-blinden Resultate eingefroren.

Die spätere Qualifikationsöffnung ergänzt erst dann erwartete Ausgänge,
zusätzliche Reichweite, native/externe Übereinstimmung, Cache-Amortisation und
die mechanische Default-/Opt-in-/Nullentscheidung. Dieser Slice trifft keine
dieser Aussagen.
