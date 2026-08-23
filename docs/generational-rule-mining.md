# Generationenbasiertes Regelmining

**Implementierungsstand: 23. August 2026**

Regelsuche besitzt nun einen ausführbaren Entwicklungslauf, der gefundene
Transformationen über mehrere strikt getrennte Generationen wiederverwendet.
Der Lauf beantwortet damit erstmals praktisch die Frage, ob aus Suchpfaden
gelernte Regeln in einer späteren Runde weiter entfernte Darstellungen
erschließen können.

Der Lauf ist bewusst ein **Entwicklungs- und Rediscovery-Experiment**. Er
verändert weder das Standardregelinventar noch den maschinengebundenen
Capability-Status `PROMOTION`. Exakt geprüfte Kandidaten werden ausschließlich
in einem lokalen, content-addressierten Schatteninventar aktiviert.

## Ablauf

```text
frozen input inventory I_n
  -> alle Aufgaben der Generation n ausführen
  -> Suchpfade generalisieren
  -> Candidate Validation
  -> Counterexample Search
  -> positive und negative Holdouts
  -> keine unaufgelösten Annahmen
  -> exakter Polynom-Identitätsnachweis
  -> ausführbaren DynamicPatternOperator kompilieren
  -> Generation vollständig abschließen
  -> neues Schatteninventar I_(n+1)
```

Ein Kandidat kann niemals eine Aufgabe derselben Generation beeinflussen. Erst
nachdem sämtliche Aufgaben abgeschlossen sind, werden erfolgreiche Operatoren
in das nächste Inventar aufgenommen. Der Lauf prüft diese Grenze zusätzlich,
indem keine neu erzeugte Regel-ID in den tatsächlich verwendeten Regel-IDs der
gleichen Generation vorkommen darf.

## Aktueller Drei-Generationen-Lauf

### Generation 0: Seed-Pfade

Die erste Generation verwendet die bereits vorhandenen, runtime-blinden
Hidden-Rule-Aufgaben. Sichtbar sind nur konkrete Quell- und Zielausdrücke,
primitive Engines und Holdouts. Versteckte Referenzregeln oder Familiennamen
werden für Mining, Validierung und Aktivierung nicht benötigt.

### Generation 1: erste Komposition

Die exakt akzeptierten Regeln aus Generation 0 bilden das eingefrorene
Schatteninventar. Eine neue Aufgabe erfordert zwei zuvor gelernte
Transformationen nacheinander. Aus diesem Pfad kann Regelsuche eine neue
komponierte Pattern-Regel gewinnen.

### Generation 2: rekursive Komposition

Die zweite Kompositionsaufgabe verschachtelt dieselbe Struktur erneut und wird
mit dem Inventar aus Generation 1 ausgeführt. Ein erfolgreicher Kandidat ist
damit nicht lediglich ein erneut benanntes Seed-Makro, sondern beruht auf der
Wiederverwendung bereits gelernter Transformationen.

Der allgemeine Kampagnenbericht vergleicht zusätzlich ein leeres
Schatteninventar mit dem akkumulierten Inventar auf einer tiefer verschachtelten,
vorher nicht zur Kandidatenbildung verwendeten Darstellung. Er enthält:

- `baselineReached`;
- `accumulatedReached`;
- `newlyReachableUnderBudget`;
- tatsächlich verwendete Regel-IDs und den gefundenen Pfad.

## Strenger kumulativer Reachability-Audit

Der Vergleich „leer gegen vollständig gelernt“ reicht nicht aus, um eine
Verbesserung **zwischen** späteren Generationen zu belegen. Deshalb führt
`GenerationalRuleMiningReachabilityAudit` einen getrennten, strengeren Replay
durch:

```text
Inventar aus Generation 0 + 1
  gegen
Inventar aus Generation 0 + 1 + 2
```

Beide Seiten erhalten dieselbe, absichtlich enge maximale Suchtiefe `2` und
dieselbe dreifach verschachtelte, nicht zur Kandidatenbildung verwendete
Darstellung. Der Audit gilt nur dann als bestanden, wenn alle Bedingungen
zugleich erfüllt sind:

1. Generation 1 hat in ihrem retained Suchpfad mindestens eine Regel aus
   Generation 0 verwendet.
2. Generation 2 hat mindestens eine Regel aus Generation 1 verwendet.
3. Das Inventar bis Generation 1 erreicht den Audit-Endpunkt unter Tiefe `2`
   nicht.
4. Das Inventar bis Generation 2 erreicht ihn unter genau demselben Budget.
5. Der erfolgreiche Audit-Pfad verwendet tatsächlich eine neu kompilierte Regel
   aus Generation 2.

Damit wird nicht nur gezeigt, dass irgendein gelerntes Seed-Makro nützlich ist.
Der Audit prüft ausdrücklich, ob eine spätere gelernte Komposition die
budgetierte Erreichbarkeit gegenüber dem bereits gelernten Vorgängerinventar
erweitert.

## Aktivierungskriterien

Ein Kandidat erhält `EXACT_SHADOW_ELIGIBLE` nur, wenn alle folgenden Bedingungen
erfüllt sind:

1. Der Suchlauf hat einen Kandidaten eingefroren.
2. Die Candidate-Validation ist positiv.
3. Die endliche Gegenbeispielsuche hat kein Gegenbeispiel gefunden.
4. Alle positiven Holdouts sind erreichbar und direkt äquivalent.
5. Auf keinem negativen Holdout wird die Regel angewendet.
6. Die Regel besitzt keine unaufgelösten Annahmen.
7. `ExactPolynomialPatternIdentityVerifier` bestätigt exakt identische
   Polynomnormalformen.
8. `DynamicOperatorCompiler` kann einen ausführbaren Operator erzeugen.

Nicht unterstützte Divisionen, Funktionen, Bedingungen, Budgetüberschreitungen,
fehlgeschlagene Holdouts und technische Fehler bleiben mit einem eigenen
Terminalstatus im Bericht sichtbar. Sie werden nicht stillschweigend verworfen.

## Inventaridentität und Rückrollbarkeit

Jede Inventarversion bindet per SHA-256:

- die Repository-Revision;
- die vorherige Inventaridentität;
- Generation und Kandidatenidentität;
- Quell- und Zielpattern;
- exakten Proof-Hash;
- Operator-Regel-ID und Provenienz-Hash.

Das Schatteninventar ist damit unveränderlich nachvollziehbar. Wird ein
Kandidat entfernt oder ein Pattern, Proof-Budget beziehungsweise Vorgänger
geändert, entsteht eine andere Inventaridentität. Da das Produktionsinventar
nicht verändert wird, ist ein Rückrollen lediglich die Auswahl einer früheren
Inventarwurzel.

## Reproduktion

Der fokussierte Lauf einschließlich beider Reportartefakte wird durch den Test
reproduziert:

```bash
./gradlew :app:test \
  --tests de.regelsuche.docs.GenerationalRuleMiningCampaignTest
```

Die kanonischen Ergebnisse liegen anschließend unter:

```text
app/build/reports/generational-rule-mining/campaign.json
app/build/reports/generational-rule-mining/cumulative-reachability-audit.json
```

Der öffentliche String-Adapter für den exakten Patternnachweis besitzt einen
eigenen fokussierten Test:

```bash
./gradlew :regelsuche-learning:test \
  --tests de.regelsuche.evolution.ExactPolynomialPatternVerificationServiceTest
```

Der vollständige Repositoryvertrag bleibt:

```bash
./gradlew --no-configuration-cache ciCheck
```

## Aussagegrenze

Ein positiver kumulativer Audit belegt nur:

> Unter dem eingefrorenen Aufgaben-, Regel-, Verifikations- und Suchbudget kann
> Regelsuche exakt geprüfte gelernte Pattern-Regeln generationenweise
> wiederverwenden, und eine spätere Generation kann eine Darstellung erreichen,
> die das bereits gelernte Vorgängerinventar unter demselben Tiefenbudget nicht
> erreicht.

Er belegt keine externe mathematische Neuheit, keine allgemeine Überlegenheit,
keine formale Beweisabdeckung außerhalb des exakten Polynomfragments und keine
Produktionsfreigabe. Die breitere historische Diagnose ist unter
[Search Intelligence](search-intelligence.md) dokumentiert; das streng
preregistrierte held-out Flagship-Experiment bleibt unter Issue #521 getrennt.

## Siehe auch

- [Regel-Entdeckung](rule-discovery.md)
- [Promotion exakt bewiesener gelernter Pattern-Regeln](learned-pattern-rule-promotion.md)
- [Sicherer Regelvorbereitungskoordinator](safe-rule-preparation-coordinator.md)
- [Discovery- und Forschungsstand](discovery-status.md)
