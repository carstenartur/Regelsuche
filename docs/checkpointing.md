# Checkpointing & Resume

`SearchJobManager.resume(jobId)` startet ein pausiertes Suchjob nicht
mehr „bei Null", sondern setzt es vom letzten Checkpoint fort.

## Was wird persistiert?

`SearchCheckpoint` (Paket `de.regelsuche.checkpoint`) bündelt:

| Feld                    | Bedeutung                                                  |
|-------------------------|------------------------------------------------------------|
| `jobId`                 | Schlüssel, identifiziert den Job.                          |
| `originalExpression`    | Der Ausdruck, mit dem der Job initial gestartet wurde.     |
| `profile`               | `SearchProfile` (FAST_SIMPLIFY, DISCOVERY, …).             |
| `heuristicName`         | Name der `SearchHeuristic`.                                |
| `frontier`              | Liste noch zu erkundender Ausdrücke (besten zuerst).       |
| `visitedHashes`         | Bereits gesehene kanonische Hashes — gegen Zyklen.         |
| `bestPaths`             | Aktuelle Top-Lösungen (Ausdruck, Score, letzte Regel).     |
| `randomSeed`            | Seed für stochastische Strategien (MCTS).                  |
| `createdAt` / `updatedAt` | Zeitstempel für UI/Audit.                                |

`SearchCheckpoint.resumeSeed()` liefert den Ausdruck, mit dem ein
Resume gestartet werden sollte: die Spitze der Frontier, fällt auf den
besten bekannten Pfad zurück und schließlich auf das Original.

## Repositories

| Klasse                                       | Persistenz                                      |
|----------------------------------------------|-------------------------------------------------|
| `SearchCheckpointRepository`                 | Interface.                                      |
| `InMemorySearchCheckpointRepository`         | `ConcurrentHashMap` – Default für Tests.        |
| `JsonFileSearchCheckpointRepository`         | Schreibt/liest *eine* JSON-Datei – einfach zu auditieren und zu sichern. |

Beim `SearchJobManager.pause()` und nach jedem Lauf ruft die
`finalizeRun`-Phase intern `checkpointRepository.save(...)` auf;
`resume()` ruft `findByJobId(...)` und – falls vorhanden – baut die
Eingabe aus `resumeSeed()` neu auf, statt den Originalausdruck zu
verwenden.

## Beispielsequenz

```java
SearchCheckpointRepository repo =
    new JsonFileSearchCheckpointRepository(Path.of("build/checkpoints.json"));
SearchJobManager manager = new SearchJobManager(serviceFactory, repo);

SearchJob job = manager.submit("(x+1)^4", InputType.TERM, "DISCOVERY", List.of());
// ... irgendwann später
manager.pause(job.id());
// Java-Prozess wird beendet, JSON-Datei wird gesichert.

// Neuer Prozess, Repository wird automatisch aus der Datei hydriert
manager.resume(job.id());
// Suche läuft mit der gespeicherten Frontier und den bekannten Hashes weiter.
```

## Grenzen

* Der Restart kann nicht alle Strategie-internen Zustände wiederherstellen
  (Beam-Knoten in voller Tiefe, interne Sortierungen). Pragmatisch wird
  ab der besten bekannten Stelle weitergesucht und die `visitedHashes`
  verhindert Doppelarbeit.
* Stochastische Strategien (`RandomMonteCarloSearchStrategy`) werden über
  `randomSeed` reproduzierbar; Tree-Statistiken werden nicht persistiert.
* Sehr große `visitedHashes`-Mengen sollten regelmäßig gerollt werden –
  z.B. durch ein anderes `SearchCheckpointRepository`, das Hashes
  komprimiert (z.B. Bloom-Filter).
