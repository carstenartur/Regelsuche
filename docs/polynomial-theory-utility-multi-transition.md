# Typisierte Übergangs- und Arbeitsverträge der Polynomstudie

Status: Laufzeitgrundlage vor Ergebnisintegration und Candidate-Freeze

Bezug: Issue #748

## Problem

Ein Studienergebnis kann mehrere mathematisch gleiche, aber an verschiedenen
AST-Positionen erzeugte Übergänge enthalten. Ein einzelner Übergangszähler und
ein Sammelhash verlieren dabei die entscheidenden Informationen:

- welche konkrete Position verändert wurde,
- welche Quell- und Ergebniswurzel zu diesem Übergang gehören,
- welcher verifizierte Transformations- und Backendpfad verwendet wurde,
- ob der Übergang durch Berechnung oder Cache-Replay entstand,
- welche kanonische Arbeit der einzelne Übergang verbrauchte.

Vor einer neuen Candidate-Freeze werden diese Informationen deshalb als
eigenständige unveränderliche Laufzeitwerte modelliert.

## `PolynomialTheoryUtilityWorkBreakdown`

Der Arbeitsvektor hält primitive Arbeit getrennt von mechanischer Arbeit. Die
mechanischen Dimensionen sind:

1. Matching,
2. Quellvalidierung,
3. Faktorisierung,
4. Verifikation,
5. Rendering,
6. Reparse,
7. Rekonstruktion,
8. Ersetzung der Fundstelle,
9. Cache-Lookup,
10. Cache-Einfügung,
11. Cache-Verdrängung,
12. Cache-Replay,
13. Evidenzerzeugung.

Alle Werte müssen nichtnegativ sein. Summen werden mit exakter
Überlaufprüfung gebildet. `covers` vergleicht zwei Arbeitsvektoren
komponentenweise; eine spätere Ergebnisaggregation kann daher keinen lokalen
Aufwand durch Umverteilung zwischen Kategorien verdecken.

## `PolynomialTheoryUtilityTransitionOutcome`

Ein Übergang bindet mindestens:

- seinen kontinuierlichen Index innerhalb eines späteren Ergebnisses,
- die SHA-256-Identität des exakten Ausführungseingangs,
- den numerischen AST-Pfad,
- Quell- und Ergebnisexpression der Fundstelle,
- Quell- und Ergebnisexpression der gesamten Wurzel,
- die eingefrorenen Transformations- und Backendidentitäten,
- getrennte Quell- und Übergangsevidenz,
- Cache-Disposition und Cache-Lineage,
- den lokalen Arbeitsvektor.

Die Übergangsidentität ist ein SHA-256 über alle diese Felder. Zwei gleiche
Faktorisierungen an verschiedenen Fundstellen sind deshalb verschiedene
Evidenzobjekte. Auch eine geänderte Reihenfolge erhält später durch die
kontinuierlichen Indizes eine andere Identität.

## Verhältnis zu `TreePosition`

Der gespeicherte Zahlenpfad ist ausschließlich eine Evidenzprojektion des
vorhandenen `TreePosition`-Modells. Der neue Vertrag führt keine zweite
Navigation, keine zweite AST-Ersetzung und keinen zweiten Staleness-Guard ein.
Die mathematischen Adapter müssen weiterhin die gemeinsame lokale
Rewrite-Infrastruktur verwenden und deren geprüften Pfad in das Ergebnis
übernehmen.

## Cache-Semantik

Drei Zustände sind zulässig:

- `CACHE_DISABLED`: keine Lineage und keinerlei Cache-Arbeit,
- `CACHE_MISS_INSERTED`: Lookup, Einfügung und Faktorisierung; optional eine
  explizit identifizierte Verdrängung,
- `CACHE_HIT_REPLAYED`: Lookup und Replay, aber keine neue Faktorisierung,
  Einfügung oder Verdrängung.

Cache-Einträge und verdrängte Einträge werden kryptografisch identifiziert.
Die Revision muss exakt der eingefrorenen Studienrevision entsprechen. Ein
cacheloses Profil darf auch dann keine Cache-Lineage tragen, wenn der
mathematische Übergang korrekt wäre.

## Fail-closed-Bindung

`validateAgainst` prüft einen Übergang gegen:

- den erwarteten Listenindex,
- den exakten Ausführungseingang,
- den unveränderten Formation-Fall,
- das ausgewählte eingefrorene Profil.

Damit werden Rebinding, erfundene Backend- oder Transformationsidentitäten,
falsche Quellwurzeln und unzulässige Cache-Zustände abgewiesen.

## Umfang dieses Schritts

Dieser Schritt liefert bewusst nur die beiden elementaren Verträge und ihre
Negativtests. Er verändert noch nicht:

- `CandidateResult`,
- den 30-Run-Orchestrator,
- `CandidateBatch`,
- die Candidate-Freeze-Serialisierung,
- einen mathematischen Profiladapter,
- die versiegelte Qualifikation.

Die nächste Tranche integriert die typisierten Werte in ein neues
`CandidateResult`-Schema. Erst danach wird die Candidate-Freeze neu aufgebaut.
Diese Reihenfolge verhindert, dass ein großer Ergebnis-/Runner-Umbau und die
Definition seiner elementaren Evidenzwerte in einem schwer prüfbaren Commit
vermischt werden.
