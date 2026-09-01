# Kanonischer Candidate-Freeze der Polynomtheorie-Nutzenstudie

Status: vollständige target-blinde Ergebnis- und Messserialisierung vor
Qualifikationsöffnung

Bezug: Issue #748

## Zweck

Die vorherigen Verträge binden jede der 600 Ausführungszeilen an ein typisiertes
Resultat und genau einen vollständigen Messbegleiter. Diese In-Memory-Grenze
reicht für eine spätere unabhängige Qualifikation jedoch nicht aus. Ohne
kanonische Bytes könnte ein nachfolgender Prozess nur Hashes oder abgeleitete
Summen erhalten und müsste fehlende Resultat-, Pfad- oder Cache-Evidenz
rekonstruieren.

`PolynomialTheoryUtilityCandidateFreeze` serialisiert deshalb ausschließlich
das validierte `PolynomialTheoryUtilityCandidateMeasurementBatch` in ein
versioniertes Artefakt:

```text
polynomial-theory-utility-candidate-freeze-v1.json
```

Schema und Status lauten:

```text
regelsuche.polynomial-theory-utility-candidate-freeze/v1
CANDIDATES_FROZEN_QUALIFICATION_NOT_OPENED
HASH_ONLY_NOT_OPENED
```

Der Slice führt keinen mathematischen Profiladapter aus und öffnet die
versiegelte Qualifikation nicht.

## Gebundene Artefakte

Der Freeze enthält für jede bereits eingefrorene Quelle Pfad, Bytelänge und
SHA-256:

- Präregistrierung,
- target-blinde Formation,
- versiegelte Qualifikation,
- Ausführungsplan,
- 600 target-blinde Ausführungseingaben.

Die Qualifikationsdatei wird weder geladen noch kopiert. Ihre Bindung bleibt auf
den bereits vor der Profilausführung festgelegten Pfad, die Länge und den Hash
beschränkt. Ein Ausgabeverzeichnis, in dem eine Datei oder ein symbolischer Link
mit dem Qualifikationsdateinamen vorhanden ist, wird vor dem Schreiben
abgewiesen.

## Vollständige Zeilen

Die kanonische Reihenfolge bleibt die eingefrorene run-major Reihenfolge:

```text
5 Profile × 6 Checkpoints × 20 Fälle = 600 Zeilen
```

Jede Zeile enthält den vollständigen target-blinden Zustand:

- den exakten Ausführungseingang,
- terminalen Status, Detailcode und Verifier-Ausgang,
- den vollständigen kanonischen Arbeitsvektor,
- alle occurrence-gebundenen Übergänge,
- die resultweite Messidentität und Formation-Annahmemenge,
- Pfadtiefen, AST-Messungen und primitive Regel-Lineage,
- alle Faktorisierungsanfragen, Kandidaten und Verifier-Reports,
- alle Cache-Lookups, Hits, Misses, Einfügungen, Verdrängungen und Replays.

Transition-Traces referenzieren den bereits in derselben Zeile serialisierten
Übergang über dessen Inhaltsidentität. Der Freeze erzeugt keine zweite
mathematische Transformationsautorität.

Abgeleitete Messwerte werden als deterministische Projektion der bereits
validierten Rohbeobachtungen ausgegeben. Sie können daher direkt ausgewertet
werden, ohne die geordneten Trace-, Attempt- und Eventlisten zu ersetzen.

## Kanonische Bytes

Die Serialisierung verwendet den gemeinsamen `JsonWriter` mit einer festen
Feldreihenfolge. Listen behalten ihre fachliche Reihenfolge. Jede Ausgabe endet
mit genau einem LF-Zeichen.

Der Artefaktkonstruktor erzeugt die Darstellung erneut aus dem gebundenen
Messbatch und prüft:

- bytegenaue Übereinstimmung mit der kanonischen Darstellung,
- positive und exakte UTF-8-Bytelänge,
- den SHA-256 der vollständigen Bytes,
- gültige Unicode-Eingabe ohne stillschweigende Ersatzzeichen.

`bytes()` liefert eine defensive Kopie. `verify` und `requireVerified`
vergleichen ausschließlich mit den kanonischen eingefrorenen Bytes. Eine
geänderte Resultat-, Mess-, Trace-, Attempt-, Event- oder Eingabeidentität
verändert damit zwangsläufig den Freeze-Inhalt und dessen Hash.

Das Schreiben erfolgt über `AtomicJsonFile`: zunächst in eine temporäre Datei,
anschließend durch atomaren Ersatz, soweit das Dateisystem dies unterstützt.
Die geschriebenen Bytes werden unmittelbar erneut gegen das Artefakt geprüft.

## Fail-closed-Grenzen

Der Freeze weist insbesondere zurück:

- ein fremdes oder unvollständiges 600-Zeilen-Messbatch,
- veränderte kanonische JSON-Daten,
- falsche Bytelängen oder Inhalts-Hashes,
- ungültige Unicode-Surrogatfolgen,
- manipulierte oder abgeschnittene Ausgabebytes,
- eine im Ausgabeordner vorhandene versiegelte Qualifikation.

Die vollständige Resultat-/Messkonsistenz selbst bleibt Autorität der
vorherigen Verträge. Der Freeze nimmt deren bereits geprüfte Objekte entgegen
und serialisiert sie ohne eine parallele fachliche Modellhierarchie.

## Charakterisierung

Der fokussierte Test baut einmal die vollständige 600-Zeilen-Matrix auf und
prüft:

- deterministische Wiederholung mit identischen Bytes und Hashes,
- genau 600 geordnete Zeilen einschließlich erster und letzter Position,
- alle fünf Artefaktbindungen,
- die ausschließlich hashbasierte Qualifikationsbindung,
- das Fehlen von Sollausgängen, Referenzausdrücken und Entscheidungen,
- eine nichtleere verifizierte On-Demand-Transition mit zwei primitiven
  Pfadschritten und geordnetem Faktorisierungsreport,
- einen nichtleeren Cache-Miss-Pfad mit Lookup, Einfügung und vollständiger
  Entry-/Transition-Lineage,
- Ablehnung gefälschter Hashes, Längen und kanonischer Daten,
- Tamper-Erkennung und defensive Byteausgabe,
- explizite Ablehnung ungültiger Unicode-Surrogatfolgen,
- atomisches Schreiben und die Sperre gegen eine Qualifikationsdatei oder
  einen Qualifikations-Symlink im Ausgabeordner.

Die Testmatrix verwendet 598 terminale Nullresultate sowie zwei synthetische,
vollständig typisierte Evidenzzeilen. Die beiden positiven Zeilen dienen nur der
Charakterisierung der bereits festgelegten Serialisierung und begründen keine
mathematische Profilwirkung oder Studienaussage.

## Nächster Schritt

Nach sequenziellem Merge und vollständiger Qualifikation der Mess- und
Freeze-Verträge kann der erste mathematische Profiladapter implementiert
werden. `ON_DEMAND_VERIFIED_FACTORIZATION` muss dann Resultate, Pfade,
Faktorisierungsreports und Arbeitswerte in exakt diese bereits festgelegte
Grenze liefern.

Die Qualifikation darf erst geöffnet werden, nachdem die endgültigen
Candidate-Freeze-Bytes aller obligatorischen Profile feststehen. Erst ein
getrennter Qualifikations- und Entscheidungsschritt darf zusätzliche
Reichweite, Cache-Amortisation oder eine Default-/Opt-in-/Nullentscheidung
ableiten.

## Claim-Grenze

Dieser Slice belegt ausschließlich, dass vollständige target-blinde Resultate
und ihre Mess-Evidenz deterministisch, content-adressiert und fail-closed
eingefroren werden können. Er belegt weder Faktorisierungserfolg noch
Suchnutzen, Backend-Parität, Cache-Nutzen, mathematische Neuheit oder
Überlegenheit gegenüber einem Computeralgebrasystem.
