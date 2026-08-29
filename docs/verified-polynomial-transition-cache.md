# Verifizierter Polynom-Transition-Cache

**Implementierungsstand: 29. August 2026**

## Zweck

`VerifiedPolynomialTransitionCacheStore` bildet die erste konkrete, typisierte
Cache- und Replay-Grenze aus #748. Der Store beschleunigt ausschließlich die
Wiederverwendung einer bereits vollständig verifizierten exakten
Polynomtransformation.

Der Store ist **keine mathematische Autorität**. Er faktorisiert nicht, bewertet
keine Identität und ersetzt keinen Verifier. Ein Eintrag darf nur aus einem
erfolgreichen `ExactFactorizationTransformationPipeline.Result` entstehen.
Damit sind vor der Retention bereits gebunden:

- parserausgestellte exakte Quelle und Literal-Evidence;
- typisierte Faktorisierungsanfrage und Engine-Vorschlag;
- unabhängige Verifikation des ausgewählten Kandidaten;
- deterministisches Rendern der exakten rationalen Faktoren;
- erneutes exaktes Parsen einschließlich Literalzertifikaten;
- Rekonstruktion im ursprünglichen Polynomring;
- die abschließende verifiergebundene Transformationsidentität;
- die vollständige, stufengetrennte Ableitungsarbeit.

## Autoritative Evidenzkette

Jede gespeicherte Transition enthält dieselbe geordnete primitive Kette wie die
On-Demand-Ausführung:

```text
EXACT_SOURCE_EVIDENCE
EXACT_FACTORIZATION_PIPELINE
VERIFIER_SELECTED_CANDIDATE
EXACT_FACTOR_RENDERING
EXACT_REPARSE
EXACT_POLYNOMIAL_RECONSTRUCTION
VERIFIER_BOUND_TRANSFORMATION
```

`EXACT_REPARSE` bindet nicht nur den gerenderten Text, sondern auch die
parserausgestellten exakten Literalzertifikate und ihre Quellbereiche. Die
Transition-ID bindet Quelle, Ziel, alle Zertifikate, Transformationsart,
primitive Expansion und ursprüngliche Ableitungsarbeit.

## Exakte Lookup-Identität

Ein Lookup wird ausschließlich aus folgendem Tupel gebildet:

```text
cacheId
cacheRevision
sourceEvidenceHash
sourceExpression
```

Es gibt keine Normalisierung, keine Revisionssuche, kein unscharfes Matching und
kein stilles Neuanbinden. Ein abweichendes Zeichen, eine andere Revision oder
eine andere Source-Evidence führt zu einem sichtbaren Miss.

## Keine Freigabe vor erfolgreichem Replay

Retention, Lookup und Replay besitzen getrennte issuer-owned Ergebnistypen. Sie
haben keine öffentlichen Konstruktoren.

`RetentionResult` gibt ausschließlich zertifizierte Metadaten frei:

- Lookup-Anfrage;
- Entry- und Transition-ID;
- Retentionsgeneration und Replay-Binding-ID;
- Anzahl der gebundenen Beobachtungslineages;
- optionale opake Eviction-Evidence.

Es enthält weder die gespeicherte Transition noch deren primitive Expansion.
`LookupResult` bindet einen internen Entry-Snapshot, besitzt aber keinen
öffentlichen Entry-Accessor. Der Store bietet außerdem keine öffentliche
Entry-Aufzählung und kein unverbuchtes `findExact`.

Nur ein issuer-owned `ReplayResult` mit Status `REPLAYED` gibt die
`VerifiedTransition`, ihre primitive Evidenzkette und die ursprüngliche
Ableitungsarbeit frei.

## Store-Lebenszeit, Retentionsgeneration und stale Replay

Die inhaltliche Entry-ID bleibt content-addressed. Zusätzlich erhält jede
konkrete Einfügung eine monoton wachsende `retentionGeneration`. Daraus entsteht
ein eigener `replayBindingId`.

Ein `LookupResult` bindet genau diesen Replay-Binding-Identifier und zusätzlich
intern die Store-Instanz, die es ausgestellt hat. Der Store-Lebenszeitbezug ist
eine nicht serialisierte Capability und verändert die deterministischen
Evidence-Hashes nicht. Dadurch gelten folgende Fälle fail-closed:

- der Eintrag wurde verdrängt;
- unter demselben Schlüssel wurde eine andere Transition eingefügt;
- derselbe content-addressed Eintrag wurde nach einer Verdrängung erneut
  eingefügt;
- ein alter Lookup wird gegen eine spätere Retentionslebenszeit abgespielt;
- ein Lookup einer anderen Store-Instanz wird eingereicht, selbst wenn Entry-ID,
  Retentionsgeneration und Replay-Binding-ID identisch sind.

Insbesondere kann eine identische Wiedereinfügung einen zuvor stale gewordenen
Lookup nicht wieder gültig machen. Eine zusätzliche Beobachtungslineage am noch
vorhandenen Eintrag erhält dagegen dieselbe Retentionsgeneration, weil die
mathematische Transition und ihre Cache-Lebenszeit unverändert bleiben.

## Begrenzter Lebenszyklus

Der Store besitzt zwei unabhängige Grenzen:

- Eintragskapazität, standardmäßig 128;
- Lineagekapazität je Eintrag, standardmäßig 256.

Beide Grenzen sind konstruktiv beschränkt. Beobachtungen begrenzen außerdem die
Anzahl und Gesamtgröße von Provenienz- und Annahmewerten. Damit kann weder eine
unbegrenzte Zahl von Cache-Einträgen noch eine unbegrenzte Zahl von
Beobachtungen an einem einzelnen Eintrag akkumulieren.

Die Eintragsverdrängung ist deterministisch FIFO. Wird die Lineagegrenze
erreicht, bleibt der vorhandene Eintrag unverändert und `retain` liefert den
sichtbaren Status:

```text
LINEAGE_LIMIT_REACHED
```

Es gibt kein stilles Abschneiden und keine heimliche Verdrängung einer älteren
Lineage. Eine Verdrängung liefert nur eine opake `Eviction` mit Entry-, Lookup-
und Replay-Binding-IDs. Sie gibt weder die verdrängte Transition noch deren
primitive Evidenz frei.

## Retentionsstatus

```text
INSERTED
LINEAGE_ADDED
UNCHANGED
LINEAGE_LIMIT_REACHED
```

`UNCHANGED` bedeutet, dass exakt dieselbe content-addressed Beobachtung bereits
vorhanden war. Nur `INSERTED` darf einen FIFO-Eintrag verdrängen.

## Replaystatus

```text
REPLAYED
LOOKUP_MISS
STALE_LOOKUP
FOREIGN_LOOKUP
```

`LOOKUP_MISS`, `STALE_LOOKUP` und `FOREIGN_LOOKUP` enthalten keine freigegebene
Transition und keine primitiven Evidenzschritte.

## Arbeitsrechnung

Der Store trennt drei Größen:

1. `retainedDerivationWork`: ursprüngliche mathematische Ableitungsarbeit;
2. `lookupWork`: exakte Schlüsselbildung und Indexzugriff;
3. `replayWork`: Store-Lebenszeitprüfung, Retentionsprüfung,
   Transitionprüfung und tatsächlich ausgegebenes Ziel/Evidence.

`actualExecutionWork` ist exakt

```text
lookupWork + replayWork
```

Jeder Replay-Versuch verbucht genau eine Store-Authority-Prüfung. Bei
`LOOKUP_MISS`, `STALE_LOOKUP` und `FOREIGN_LOOKUP` werden keine Ziel-Code-Units
und keine primitiven Evidenzschritte als ausgeführt verbucht. Ein fremder Lookup
führt auch keinen Entry- oder Retentionsvergleich im empfangenden Store aus. Die
Arbeit beschreibt damit den tatsächlichen fehlgeschlagenen Replay-Versuch und
nicht hypothetische Ausgabe, die nie freigegeben wurde.

## Determinismus

Alle mathematisch oder lebenszyklusrelevanten serialisierbaren Ergebnisse tragen
SHA-256-Zertifikate mit längengepräfixter UTF-8-Kodierung. Gebunden werden unter
anderem:

- vollständiger exakter Lookup-Schlüssel;
- content-addressed Transition-ID;
- Retentionsgeneration und Lineageanzahl;
- Retentionsstatus und opake Eviction-Evidence;
- Lookupstatus und Lookuparbeit;
- Replaystatus und tatsächliche Replayarbeit;
- ausschließlich bei Erfolg freigegebene Transition und primitive Expansion.

Die nicht serialisierte Store-Capability verhindert Cross-Store-Replay, ohne die
reproduzierbaren Zertifikate mit Zufallswerten oder Objektadressen zu belasten.
Betriebszähler wie Hits, Misses und Evictions sind sichtbar, begründen aber
keine mathematische Aussage.

## Qualifikation

Die fokussierten Tests prüfen:

- identische verifierautorisierte Transition und primitive Expansion;
- Trennung von Ableitungs-, Lookup- und Replayarbeit;
- exakten Revisions-Miss;
- private, ausschließlich store-ausgestellte Retention-, Lookup- und
  Replay-Ergebnisse;
- fehlende Entry-, Transition-, Aufzählungs- und Direktlookup-Bypässe;
- Cross-Store-Ablehnung trotz identischer Entry-ID und Retentionsgeneration;
- idempotente Retention;
- zusätzliche Beobachtungslineage;
- sichtbare Lineagegrenze;
- deterministische FIFO-Verdrängung mit opaker Eviction-Evidence;
- stale Replay nach Verdrängung;
- weiterhin stale Replay nach identischer Wiedereinfügung;
- unterschiedliche Lookup-Zertifikate für unterschiedliche
  Retentionsgenerationen;
- keine Ausgabe- oder Evidenzarbeit bei fehlgeschlagenem Replay;
- Ablehnung nicht transformierter Ergebnisse;
- Ablehnung normalisierter oder übergroßer Identitäts- und
  Beobachtungsmaterialien.

Vor dem Merge muss der exakte Head sämtliche checkout-eigenen Gradle-,
JMH-/SymPy- und Maven-/Produkt-/Docker-Autoritäten bestehen.

## Bewusste Grenze

Dieser Baustein

- routet noch keine gewöhnlichen Mining- oder Lernergebnisse automatisch;
- klassifiziert noch keine geminte Identität als theorieabgedeckt;
- wählt noch keine Default-Strategie für Suche oder Workbench;
- führt noch nicht den eingefrorenen Fünf-Profil-Vergleich aus;
- behauptet keine allgemeine Faktorvollständigkeit oder CAS-Überlegenheit.

Diese Integrations- und Nutzenfragen bleiben Bestandteil von #748. Der Store
liefert dafür eine begrenzte, exakt gebundene und messbare Replay-Autorität.
