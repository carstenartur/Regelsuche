# Job-Steuerung für Suchläufe

`de.regelsuche.jobs.SearchJobManager` ist eine schlanke Lifecycle-Verwaltung über `TransformationSearchService`. Sie ermöglicht parallel laufende, einzeln pausierbare/abbrechbare Suchläufe und persistente Checkpoints.

## Modell

`SearchJob` enthält Ausdruck, `InputType`, Profil-Name, Zustand (`QUEUED|RUNNING|PAUSED|DONE|CANCELLED|FAILED`), Fortschrittsmetriken (`exploredStates`, `discoveredSuccesses`, `bestExpression`, `bestImprovement`) und Zeitstempel.

## API

```java
SearchJobManager manager = new SearchJobManager(job -> {
    SearchProfile profile = SearchProfile.valueOf(job.profile());
    return new TransformationSearchService(
        engine, graphStore, profile.heuristic(scorer), notifier, profile.newStrategy()
    );
});

SearchJob job = manager.submit("(x + 0) * 1", InputType.TERM, "FAST_SIMPLIFY", List.of());
manager.pause(job.id());
manager.resume(job.id());
manager.cancel(job.id());

manager.checkpoint(Path.of("jobs.json"));      // persistiert alle Snapshots
SearchJobManager restored = new SearchJobManager(factory);
restored.restore(Path.of("jobs.json"));        // restauriert als PAUSED
```

## Semantik

* **submit** legt den Job an, startet ihn auf dem ServiceFactory-Service und gibt einen Schnappschuss zurück.
* **pause** signalisiert kooperativ; die laufende Strategie läuft bis zu ihrem nächsten Beobachterpunkt, dann fällt der Status auf `PAUSED`. Falls die Strategie keine kooperativen Punkte hat, wird der Service heruntergefahren und der Job auf `PAUSED` gesetzt.
* **resume** startet den Job auf einem frischen Service neu. Wenn ein `SearchCheckpointRepository` konfiguriert ist, übernimmt der Manager den besten bisher gefundenen Ausdruck (`checkpoint.resumeSeed()`) als neuen Input statt von der Original-Eingabe zu beginnen. Der vollständige strategiespezifische Suchstack (z.B. komplette Beam-Frontier oder MCTS-Baum) wird **nicht** wiederhergestellt; das ist eine bewusste Vereinfachung. Siehe [`docs/checkpointing.md`](checkpointing.md).
* **cancel** beendet endgültig (`CANCELLED`).
* **checkpoint/restore** schreibt/liest die Jobtabelle als JSON. Restaurierte Jobs landen auf `PAUSED`, nicht automatisch im Lauf; `resume()` muss explizit gerufen werden.

## Aktuelle Grenzen

* Der Service-Factory-Block trägt die volle Verantwortung für Profile/Heuristiken; der Manager kennt keine Profile-Details.
* Keine Job-Prioritäten / Slots: alle Jobs konkurrieren im jeweils erzeugten Executor; für globale Drosselung muss die Factory einen geteilten Executor injizieren.
* Keine Live-Subscriptions: Fortschritts-Updates werden erst beim Abschluss (`finalizeRun`) eingetragen. Streaming-Updates wären eine sinnvolle Erweiterung.

## Tests

Die Tests `SearchJobManagerTest` decken Start/Done, Cancel und Checkpoint/Restore ab.
