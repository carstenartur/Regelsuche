# Fortsetzbare Beweispläne

Stand 25. November 2026. Die inkrementellen Einstiegspunkte verwenden die vorhandene Vierkomponenten-Residualgrammatik und die endliche Grammatik der geschachtelten Logik. Jeder Cursor gehört zu einer Anfrage und lebt bis zum Schließen im Arbeitsspeicher.

## Residualalternativen

`ResidualPlanController.search(source, context, mode, selectorMask, formationBudget, replayBudget, searchBudget)` durchsucht die Alternativen einer Quelle mit `MoveSearch.Scheduling.STAGED_INCREMENTAL`. Der Zielausdruck steht der abschließenden Suche zur Verfügung. Bildung und Auswahl der Residualpaare verwenden ausschließlich die Quelle und die explizite Auswahlmaske. Wer den Cursor einzeln steuern möchte, öffnet `ResidualPlanProvider.provider().openSession(state, context)` und ruft `next(allowance)` auf. `READY` erlaubt den nächsten Teilschritt. `LIMIT` erlaubt die Fortsetzung desselben Cursors bei verbrauchtem Zeitscheibenbudget. `close()` gibt seine vorbereiteten Daten frei.

Der Cursor bildet die Quellkomponenten und anschließend je eine lokal geprüfte Wirkung. Alle Alternativen desselben Cursors nutzen diese Daten. Paarwahl, gemeinsame exakte Komposition und unabhängige Rekonstruktion des gewählten Paars folgen in eigenen Phasen. Die vier typisierten Löcher und vier Pflichten (Disjunktheit, vollständige Überdeckung, Residualsumme null und Äquivalenz) stammen aus `SchematicProofPlan`. `ResidualPlanController.Session.openPlan()` zeigt sie an, solange das gewählte Paar auf Prüfung wartet.

Das v3-Providerkonto erfasst Vorbereitung als `LOAD`, Auswahl und Komposition als `MATCH` und die Rekonstruktion als vorausbezahlte `EVIDENCE`-Phasen. Auch fehlgeschlagene Versuche und abgebrochene Rekonstruktionen bleiben bezahlt. Jede Phase ist atomar und kann das einzelne Pullbudget überschreiten. Die konfigurierten Grenzen für Bildungsarbeit und für eine einzelne Rekonstruktion bleiben wirksam. Ein erschöpfter Rekonstruktionsversuch lässt die übrigen Paare zu und macht die abschließende Vollständigkeitsaussage ergebnisoffen. Der Empfänger prüft jeden angebotenen Zug erneut anhand von exakter Eingabe, Quellwirkungen, Bindungen und Beweiswurzel. Zustandsannahmen, Fähigkeiten, Kontextannahmen und Suchphase gehören zur Anfragebindung.

Für `(a^2+b^2)*(c^2+d^2)` und Auswahlmaske 7 entstehen zwei verschiedene Zielausdrücke. Eine Vorbereitung kostet 3.907 Einheiten, die übrige Bildung 2.126. Jede unabhängige Rekonstruktion kostet hier 4.794 Einheiten. Dispatch-, Phasen- und Empfängerkosten kommen hinzu. Der Vergleich mit dem vorhandenen Controller prüft die identische Ergebnismenge und den einmaligen Vorbereitungsaufwand im selben Cursor.

## Geschachtelte Logik

`ScopedLogicDiscoveryDomain.openPlans(initial)` öffnet die bestehende Quellgrammatik an einem frischen Zustand. Pro Abruf erscheint höchstens ein `ScopedProofSearch.Proposal`. Der Cursor behält Fakten, bereits gebildete Beweispräfixe, Runde und Regelposition. Arbeit einer atomaren Regel bleibt nach einem Abruf sichtbar. Das bisherige `discover` zieht denselben Cursor bis zum Ende und behält die ursprüngliche Reihenfolge und die Arbeitsstände bei.

`receivePlan(receivingObligation, proposal)` vergleicht die vollständige Verpflichtung einschließlich Ziel, Deklarationen, Anforderungsart, Provenienz und geschachtelten Bindern. Der bestehende Lean-Empfänger prüft den konkreten Graphen im Kernel und baut den Beweis im getrennten Projekt neu auf. Der Test mit echten Projekten läuft im vorhandenen Proof-Container. Ohne diese Projekte liefert die lokale Prüfung für einen gebildeten Graphen kein bestätigtes Zertifikat.
