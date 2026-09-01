# Faktorisierungs- und Cache-Beobachtungen der Polynomstudie

Status: rohe content-adressierte Beobachtungen vor Resultaggregation

Bezug: Issue #748

## Zweck

Die vorregistrierte Nutzenstudie muss nicht nur erfolgreiche Übergänge
aufbewahren. Sie muss auch sichtbar machen:

- wie viele Faktorisierungsanfragen ausgeführt wurden,
- welche Kandidaten ein Backend lieferte,
- welcher Kandidat ausdrücklich ausgewählt und verifiziert wurde,
- welcher konkrete Ergebnisübergang daraus entstand,
- welche Anfragen ohne Kandidat, unsupported oder inkonklusiv endeten,
- welche einzelnen Cache-Lookups, Hits, Misses, Einfügungen, Verdrängungen und
  Replays stattfanden,
- welchem Übergang ein Cache-Ereignis zuzurechnen ist oder ob es ohne späteren
  Übergang endete.

Bloße Summenzähler reichen dafür nicht. Sie erlauben weder eine Prüfung der
Reihenfolge noch eine Bindung an Request-, Kandidaten-, Transition-, Entry- und
Evidenzidentitäten.

## `PolynomialTheoryUtilityFactorizationAttempt`

Ein Faktorisierungsversuch bindet:

- kontinuierlichen Attempt-Index,
- exakte Ausführungseingangsidentität,
- eingefrorene Backendidentität,
- Request- und Request-Evidenzidentität,
- geordnete eindeutige Kandidatenidentitäten,
- optionale explizite Auswahl,
- optionale Identität des daraus entstandenen Ergebnisübergangs,
- Verifier-Ausgang,
- Report-Evidenzidentität.

`NONE` bedeutet, dass kein Kandidat ausgewählt beziehungsweise kein Übergang
erzeugt wurde. Eine Auswahl muss in der retained Kandidatenliste vorkommen und
darf nur mit `VERIFIED` verbunden sein. Eine Übergangsidentität erfordert eine
ausgewählte Kandidatenidentität. Ein ausgewählter, verifizierter Kandidat darf
dennoch ohne Übergang enden, beispielsweise wenn die rekonstruierte Darstellung
ein strukturelles `NO_CHANGE` ergibt.

Eine leere Kandidatenliste bleibt zulässig, damit negative, unsupported oder
budget-inkonklusive Versuche nicht verloren gehen. Die Kandidatenreihenfolge ist
Identitätsmaterial. Eine Umordnung erzeugt eine andere Attempt-Identität, selbst
wenn dieselben Kandidaten enthalten sind.

`validateAgainst` verlangt den positionsgleichen Attempt-Index, den exakten
Resultateingang und das vollständige eingefrorene Profil. Die reine Engine-ID
reicht nicht, weil mehrere Profile dieselbe native Engine verwenden können. Ein
referenzierter Übergang muss zum Resultat gehören und dieselbe Backendidentität
tragen. Ein faktorisierungsfreies Profil wird abgewiesen.

## `PolynomialTheoryUtilityCacheEvent`

Jede Cache-Operation ist ein eigenes geordnetes Ereignis:

```text
LOOKUP_HIT
LOOKUP_MISS
INSERTION
EVICTION
REPLAY
```

Ein Ereignis bindet:

- kontinuierlichen Event-Index,
- exakte Ausführungseingangsidentität,
- optionale Ergebnisübergangsidentität,
- Ereignisart,
- eingefrorene Cache-Revision,
- betroffene content-adressierte Entry-Identität,
- Ereignisevidenz.

`validateAgainst` akzeptiert Ereignisse nur für das exakte eingefrorene
`READ_WRITE`-Cacheprofil und nur unter der eingefrorenen Revision. Ein Lookup
ist entweder `LOOKUP_HIT` oder `LOOKUP_MISS`; Einfügung, Verdrängung und Replay
bleiben eigenständige Operationen.

Ist ein Ereignis an einen Übergang gebunden, wird die Lineage sofort geprüft:

- `LOOKUP_HIT` und `REPLAY` verlangen einen `CACHE_HIT_REPLAYED`-Übergang und
  dessen Entry-ID,
- `LOOKUP_MISS` und `INSERTION` verlangen einen
  `CACHE_MISS_INSERTED`-Übergang und dessen neue Entry-ID,
- `EVICTION` verlangt denselben Miss-/Insert-Übergang und exakt dessen
  verdrängte Entry-ID.

Ein Ereignis ohne Übergangsidentität bleibt zulässig. Dadurch können etwa ein
Cache-Miss oder eine erfolglose Berechnung sichtbar bleiben, obwohl kein
validierter Ergebnisübergang entstand.

## Bewusste Trennung

Dieser Slice erzeugt nur elementare Beobachtungswerte. Er entscheidet noch
nicht, ob eine konkrete Ereignisfolge vollständig und arbeitskonsistent ist.

Der folgende resultweite Messvertrag muss deshalb zusätzlich verlangen:

- kontinuierliche Attempt- und Event-Reihenfolge,
- eindeutige Beobachtungsidentitäten,
- vollständige Cache-Sequenzen für jede Übergangs-Disposition,
- Lookup-, Insert-, Eviction- und Replay-Zähler passend zur typisierten Arbeit,
- genau eine positionsgleiche Transition-Trace für jeden Übergang,
- vollständige Bindung an CandidateResult und Formation.

Diese Reihenfolge vermeidet einen großen Vertrag, der elementare
Beobachtungstypen, Resultaggregation und Candidate-Freeze gleichzeitig
definiert.

## Claim-Grenze

Die beiden Verträge belegen weder Faktorisierungserfolg noch Cache-Nutzen. Sie
stellen lediglich sicher, dass spätere positive, negative und inkonklusive
Ausführungen mit ihren konkreten Request-, Kandidaten-, Transition-, Entry- und
Evidenzidentitäten erhalten werden können.

Runner, CandidateResult, CandidateBatch, Candidate-Freeze, mathematische
Adapter, Qualifikation und Produktentscheidung bleiben unverändert.
